package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  private record Info(String repoId, String worktreeId, String branch, String parent, Path dir) {}

  private final Map<String, Info> byName = new ConcurrentHashMap<>();

  @Override
  public String containerName(String worktreeId, String repoId) {
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-wt-" + worktreeId + "-" + shortRepo;
  }

  @Override
  public String run(String repoId, String worktreeId, String branch, String parent) {
    String name = containerName(worktreeId, repoId);
    Path dir = Path.of(dataDir, repoId, "worktrees", worktreeId).toAbsolutePath();
    try {
      Files.createDirectories(dir.getParent());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    byName.put(name, new Info(repoId, worktreeId, branch, parent, dir));
    return name;
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
