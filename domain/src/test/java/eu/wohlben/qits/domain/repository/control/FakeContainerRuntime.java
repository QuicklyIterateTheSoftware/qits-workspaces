package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Test double for {@link ContainerRuntime} that emulates a per-workspace container as a local git
 * clone on the host, so the whole suite exercises the container-routed code paths without a real
 * docker (or the running {@code /git} server). A container is a clone at the old host workspace
 * path ({@code <data-dir>/<repoId>/workspaces/<workspaceId>}); {@code exec} runs the command there
 * via {@code env -C}, rewriting the {@code http://…/git/<repoId>} clone URL to the on-disk bare
 * origin and {@code /workspace} to the workspace dir. Because {@code exec} runs real host
 * processes, the {@code setsid}-based process-group termination the registry relies on works
 * end-to-end too.
 *
 * <p>Like real docker, operations against an <em>unknown</em> container (never {@code run}, or
 * already {@code rm}'d) fail instead of falling through to the host: {@code exec} returns a
 * non-zero {@code ExecResult}, {@code execArgv} yields an argv that fails when spawned, and {@code
 * startDaemon} throws. This is what catches a use-site that forgot {@code ensureContainer} now that
 * workspace creation no longer provisions eagerly.
 *
 * <p>Stands in for the container-level env {@link WorkspaceContainerFactory} sets at {@code docker
 * run} by applying {@link GitIdentity#envMap()} under each call's own env (per-call entries win,
 * mirroring a per-exec {@code -e} overriding container-creation env) — so commits made "in the
 * container" carry the configured identity exactly like in a real container.
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

  @Inject GitIdentity gitIdentity;

  private record Info(String repoId, String workspaceId, String branch, String parent, Path dir) {}

  private final Map<String, Info> byName = new ConcurrentHashMap<>();
  // Containers present but not running — the fake's stand-in for a host/docker restart's `Exited`
  // state. `run`/`start` clear membership, `rm` drops it, and the `markExited` test hook adds to
  // it.
  private final Set<String> stopped = ConcurrentHashMap.newKeySet();

  @Override
  public String containerName(String workspaceId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  @Override
  public String run(String repoId, String workspaceId, String branch, String parent) {
    String name = containerName(workspaceId, repoId);
    Path dir = Path.of(dataDir, repoId, "workspaces", workspaceId).toAbsolutePath();
    try {
      Files.createDirectories(dir.getParent());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    byName.put(name, new Info(repoId, workspaceId, branch, parent, dir));
    stopped.remove(name);
    return name;
  }

  /**
   * Fake containers run as host processes binding real host ports, so the proxy target is simply
   * {@code 127.0.0.1} + the port the daemon bound — the host-clone analogue of reaching a container
   * by its DNS name on the shared network. Any known container resolves (no create-time port set).
   */
  @Override
  public ProxyOrigin resolveTarget(String container, int containerPort) {
    if (!byName.containsKey(container)) {
      return null;
    }
    return new ProxyOrigin("127.0.0.1", containerPort);
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    Info info = byName.get(container);
    if (info == null) {
      // Mirror docker: exec against an unknown container fails, it doesn't run on the host. This
      // is what turns a use-site that forgot ensureContainer into a test failure instead of a
      // silent bad rewrite (literal /workspace on the host).
      return new ExecResult(1, "Error response from daemon: No such container: " + container);
    }
    // The agent auth check (AgentAuthStatus) must be deterministic here, not depend on the host's
    // real `claude` login: report signed in so chat launches take the chat path. Tests that
    // exercise the not-signed-in login redirect override AgentAuthStatus itself.
    if (argv.length >= 3
        && "claude".equals(argv[0])
        && "auth".equals(argv[1])
        && "status".equals(argv[2])) {
      return new ExecResult(0, "{\"loggedIn\": true, \"authMethod\": \"claudeai\"}");
    }
    Path dir = info.dir();
    List<String> cmd = new ArrayList<>();
    cmd.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      cmd.add("-C");
      cmd.add(wd);
    }
    // Container-level identity env first, per-call env after — later `env K=V` assignments win,
    // mirroring docker where a per-exec -e overrides container-creation env.
    gitIdentity.envMap().forEach((k, v) -> cmd.add(k + "=" + v));
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
    if (info == null) {
      // Mirror docker: the real argv is spawned later and fails then, so hand back an argv that
      // fails loudly at spawn time rather than one running on the host with a literal /workspace.
      return List.of("sh", "-c", "echo 'No such container: " + container + "' >&2; exit 1");
    }
    Path dir = info.dir();
    List<String> argv = new ArrayList<>();
    argv.add("env");
    String wd = rewriteWorkdir(workdir, dir);
    if (wd != null) {
      argv.add("-C");
      argv.add(wd);
    }
    // Container-level identity env first, per-call env after (later assignments win, like docker).
    gitIdentity.envMap().forEach((k, v) -> argv.add(k + "=" + v));
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
  public boolean isRunning(String container) {
    return byName.containsKey(container) && !stopped.contains(container);
  }

  @Override
  public void start(String container) {
    if (!byName.containsKey(container)) {
      throw new IllegalStateException("No such container: " + container);
    }
    stopped.remove(container);
  }

  /**
   * Test hook: mark a present container {@code Exited} (a host/docker-restart stand-in) without
   * touching its {@code /workspace} clone, so a subsequent {@link #start} is verifiably lossless.
   */
  public void markExited(String container) {
    if (byName.containsKey(container)) {
      stopped.add(container);
    }
  }

  @Override
  public void stop(String container) {
    // Pause in place: keep the container present (in byName) and its /workspace clone on disk, just
    // mark it Exited — so a subsequent start() is verifiably lossless, mirroring `docker stop`.
    if (byName.containsKey(container)) {
      stopped.add(container);
    }
  }

  @Override
  public void rm(String container) {
    stopped.remove(container);
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
  public List<ContainerInfo> listWorkspaceContainers(String repoId) {
    List<ContainerInfo> infos = new ArrayList<>();
    for (Info info : byName.values()) {
      if (info.repoId().equals(repoId)) {
        String name = containerName(info.workspaceId(), repoId);
        infos.add(
            new ContainerInfo(
                name, info.workspaceId(), info.branch(), info.parent(), !stopped.contains(name)));
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
    if (info == null) {
      // Mirror docker: DockerExecutor.startDaemon throws when its inner exec fails on an unknown
      // container.
      throw new IllegalStateException("No such container: " + container);
    }
    Path wd = info.dir();
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
      // Container-level identity env first, per-call env after (per-call wins, like docker).
      pb.environment().putAll(gitIdentity.envMap());
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
    return pid != null && processRunning(pid);
  }

  /**
   * Whether {@code pid} is genuinely running — alive per {@link ProcessHandle} and not a zombie.
   * {@code ProcessHandle.isAlive} counts zombies as alive, but a zombie is dead for everything the
   * fake models (it produces no output and holds no port; real docker's {@code tmux has-session}
   * would report the session gone). The distinction matters in sandboxes whose PID 1 never reaps
   * (e.g. {@code sleep infinity}): a killed detached daemon reparents there and stays a zombie
   * forever, which without this check reads as alive forever. See
   * docs/issues/resolved/2026-07-08_daemon-straggler-reap-test-fails-in-sandboxed-env.md.
   */
  public static boolean processRunning(long pid) {
    if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
      return false;
    }
    try {
      // /proc/<pid>/stat is "pid (comm) S ..." — comm may contain anything, so the state field is
      // the char after the LAST ')'.
      String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
      int close = stat.lastIndexOf(')');
      boolean zombie = close >= 0 && close + 2 < stat.length() && stat.charAt(close + 2) == 'Z';
      return !zombie;
    } catch (Exception e) {
      return true; // no /proc (non-Linux) — fall back to ProcessHandle's answer
    }
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
