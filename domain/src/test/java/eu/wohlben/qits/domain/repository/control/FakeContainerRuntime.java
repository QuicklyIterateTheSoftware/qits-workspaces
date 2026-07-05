package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Test double for {@link ContainerRuntime} that emulates a per-worktree container as a local git
 * clone on the host, so the whole suite exercises the container-routed code paths without a real
 * docker (or the running {@code /git} server). A container is a clone at the old host worktree path
 * ({@code <data-dir>/<repoId>/worktrees/<worktreeId>}); {@code exec} runs the command there via
 * {@code env -C}, rewriting the {@code http://…/git/<repoId>} clone URL to the on-disk bare origin
 * and {@code /workspace} to the worktree dir. Because {@code exec} runs real host processes, the
 * {@code setsid}-based process-group termination the registry relies on works end-to-end too.
 *
 * <p>Replaces {@link DockerExecutor} globally in this module's {@code @QuarkusTest}s via {@link
 * Mock}. Real-docker behavior is covered separately by integration tests behind {@code skipITs}.
 */
@Mock
@ApplicationScoped
public class FakeContainerRuntime implements ContainerRuntime {

  private static final Pattern CLONE_URL = Pattern.compile("^https?://[^/]+/git/([^/]+)$");

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  private record Info(
      String repoId,
      String worktreeId,
      String branch,
      String parent,
      Path dir,
      Set<Integer> publishedPorts) {}

  private final Map<String, Info> byName = new ConcurrentHashMap<>();

  @Override
  public String containerName(String worktreeId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-wt-" + worktreeId + "-" + shortRepo;
  }

  @Override
  public String run(
      String repoId,
      String worktreeId,
      String branch,
      String parent,
      Collection<Integer> publishPorts) {
    String name = containerName(worktreeId, repoId);
    Path dir = Path.of(dataDir, repoId, "worktrees", worktreeId).toAbsolutePath();
    try {
      Files.createDirectories(dir.getParent());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    byName.put(
        name,
        new Info(
            repoId,
            worktreeId,
            branch,
            parent,
            dir,
            publishPorts == null ? Set.of() : Set.copyOf(publishPorts)));
    return name;
  }

  /**
   * Fake containers are host processes, so a "published" container port is reachable on the host
   * as-is — identity mapping, but only for ports declared at {@link #run} time, mirroring docker's
   * create-time-only publishing (a container predating the port returns null, like the real one).
   */
  @Override
  public Integer hostPort(String container, int containerPort) {
    Info info = byName.get(container);
    if (info == null || !info.publishedPorts().contains(containerPort)) {
      return null;
    }
    return containerPort;
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    // The agent auth check (AgentAuthStatus) must be deterministic here, not depend on the host's
    // real `claude` login: report signed in so chat launches take the chat path. Tests that
    // exercise the not-signed-in login redirect override AgentAuthStatus itself.
    if (argv.length >= 3
        && "claude".equals(argv[0])
        && "auth".equals(argv[1])
        && "status".equals(argv[2])) {
      return new ExecResult(0, "{\"loggedIn\": true, \"authMethod\": \"claudeai\"}");
    }
    Info info = byName.get(container);
    Path dir = info == null ? null : info.dir();
    List<String> cmd = new ArrayList<>();
    cmd.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      cmd.add("-C");
      cmd.add(wd);
    }
    if (env != null) {
      env.forEach((k, v) -> cmd.add(k + "=" + (v == null ? "" : v)));
    }
    for (String a : argv) {
      cmd.add(rewriteArg(a, dir));
    }
    return runCapturing(cmd);
  }

  @Override
  public List<String> execArgv(
      String container, boolean tty, String workdir, Map<String, String> env) {
    Info info = byName.get(container);
    Path dir = info == null ? null : info.dir();
    List<String> argv = new ArrayList<>();
    argv.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      argv.add("-C");
      argv.add(wd);
    }
    if (env != null) {
      env.forEach((k, v) -> argv.add(k + "=" + (v == null ? "" : v)));
    }
    return argv;
  }

  @Override
  public boolean exists(String container) {
    return byName.containsKey(container);
  }

  @Override
  public void rm(String container) {
    Info info = byName.remove(container);
    if (info != null && Files.exists(info.dir())) {
      try (var paths = Files.walk(info.dir())) {
        paths
            .sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (Exception ignored) {
                    // best effort
                  }
                });
      } catch (Exception ignored) {
        // best effort
      }
    }
  }

  @Override
  public void restart(String container) {
    // no-op: nothing to restart for a host-clone stand-in
  }

  @Override
  public List<ContainerInfo> listWorktreeContainers(String repoId) {
    List<ContainerInfo> infos = new ArrayList<>();
    for (Info info : byName.values()) {
      if (info.repoId().equals(repoId)) {
        infos.add(
            new ContainerInfo(
                containerName(info.worktreeId(), repoId),
                info.worktreeId(),
                info.branch(),
                info.parent()));
      }
    }
    return infos;
  }

  // --- Daemon sessions: emulate the tmux model with a plain detached (setsid) host process
  // --------
  //
  // No tmux on the test host: a daemon session is a setsid'd shell (new session => group-killable
  // and
  // detached, so it survives like the real tmux session) that runs the script with output
  // redirected
  // to a host logfile and its exit code recorded. State lives on disk (pidfile/exitfile), so a
  // fresh
  // supervisor can reconcile a still-alive daemon exactly like it reads back tmux has-session.

  private Path daemonRunDir() {
    return Path.of(dataDir, ".qits-daemons").toAbsolutePath();
  }

  @Override
  public String daemonLogPath(String daemonId) {
    return daemonRunDir().resolve(daemonId + ".log").toString();
  }

  @Override
  public String attachDaemonCommand(String daemonId) {
    // No tmux on the test host: an "attach" replays and follows the daemon's logfile, so the fake
    // yields a real streaming PTY (the follower's live view) without a terminal multiplexer. `exec`
    // keeps the recorded pgid valid for the on-close group-kill.
    return "exec tail -n +1 -f " + daemonLogPath(daemonId);
  }

  @Override
  public void startDaemon(
      String container, String daemonId, String script, Map<String, String> env) {
    Info info = byName.get(container);
    Path wd = info == null ? null : info.dir();
    try {
      Path dir = daemonRunDir();
      Files.createDirectories(dir);
      Path sh = dir.resolve(daemonId + ".sh");
      Files.writeString(sh, script);
      String log = dir.resolve(daemonId + ".log").toString();
      String pid = dir.resolve(daemonId + ".pid").toString();
      String exit = dir.resolve(daemonId + ".exit").toString();
      Files.deleteIfExists(Path.of(pid));
      Files.deleteIfExists(Path.of(exit));
      Files.writeString(Path.of(log), "");
      // setsid => the shell is a session/group leader (kill -- -pgid reaches its children) and
      // detaches from this JVM (survives like the tmux session). It records its own pid (the group
      // leader) then runs the script, mirroring output to the logfile and recording the exit code.
      String inner =
          "echo $$ > '"
              + pid
              + "'; bash '"
              + sh
              + "' > '"
              + log
              + "' 2>&1; echo $? > '"
              + exit
              + "'";
      ProcessBuilder pb = new ProcessBuilder("setsid", "bash", "-lc", inner);
      if (wd != null) {
        pb.directory(wd.toFile());
      }
      if (env != null) {
        env.forEach((k, v) -> pb.environment().put(k, v == null ? "" : v));
      }
      pb.redirectOutput(java.lang.ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD);
      pb.start();
      // The pidfile is written by the detached shell; wait briefly so daemonAlive/stop see it.
      long deadline = System.currentTimeMillis() + 2000;
      while (System.currentTimeMillis() < deadline && !Files.exists(Path.of(pid))) {
        Thread.sleep(20);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (Exception e) {
      throw new RuntimeException("fake startDaemon failed for " + daemonId, e);
    }
  }

  private Long daemonPid(String daemonId) {
    Path pid = daemonRunDir().resolve(daemonId + ".pid");
    try {
      if (!Files.exists(pid)) {
        return null;
      }
      String raw = Files.readString(pid).trim();
      return raw.isEmpty() ? null : Long.parseLong(raw);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public boolean daemonAlive(String container, String daemonId) {
    Long pid = daemonPid(daemonId);
    return pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  @Override
  public Integer daemonExitCode(String container, String daemonId) {
    Path exit = daemonRunDir().resolve(daemonId + ".exit");
    try {
      if (!Files.exists(exit)) {
        return null;
      }
      return Integer.valueOf(Files.readString(exit).trim());
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public boolean signalDaemon(String container, String daemonId, String signal) {
    Long pid = daemonPid(daemonId);
    if (pid == null) {
      return false;
    }
    return runCapturing(List.of("kill", "-s", signal, "--", "-" + pid)).exitCode() == 0;
  }

  @Override
  public void killDaemon(String container, String daemonId) {
    Long pid = daemonPid(daemonId);
    if (pid != null) {
      runCapturing(List.of("kill", "-s", "KILL", "--", "-" + pid));
    }
  }

  private String rewriteWorkdir(String workdir, Path dir) {
    if (workdir == null) {
      return null;
    }
    if (workdir.equals("/workspace") && dir != null) {
      return dir.toString();
    }
    return workdir;
  }

  /** Rewrite container-side references to their host equivalents. */
  private String rewriteArg(String arg, Path dir) {
    if ("/workspace".equals(arg) && dir != null) {
      return dir.toString();
    }
    Matcher m = CLONE_URL.matcher(arg);
    if (m.matches()) {
      return Path.of(dataDir, m.group(1), "origin").toAbsolutePath().toString();
    }
    return arg;
  }

  private ExecResult runCapturing(List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.redirectErrorStream(true);
    try {
      Process p = pb.start();
      String output;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
        output = reader.lines().collect(Collectors.joining("\n"));
      }
      int exitCode = p.waitFor();
      return new ExecResult(exitCode, output);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ExecResult(-1, "interrupted");
    } catch (Exception e) {
      return new ExecResult(-1, e.getMessage());
    }
  }
}
