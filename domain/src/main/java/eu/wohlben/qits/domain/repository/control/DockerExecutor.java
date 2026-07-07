package eu.wohlben.qits.domain.repository.control;

import eu.wohlben.qits.domain.error.InternalServerErrorException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * {@link ContainerRuntime} backed by the {@code docker} CLI, shelled out via {@link ProcessBuilder}
 * — the sibling of {@link GitExecutor}, deliberately with no docker-java dependency. The runtime
 * binary is configurable ({@code qits.workspace.container-runtime}) so a rootless {@code podman}
 * can be dropped in without code changes; the argv shape below is the docker/podman common subset.
 */
@ApplicationScoped
public class DockerExecutor implements ContainerRuntime {

  private static final Logger LOG = Logger.getLogger(DockerExecutor.class);

  @ConfigProperty(name = "qits.workspace.container-runtime", defaultValue = "docker")
  String runtime;

  /**
   * Assembles the {@code docker run} argv (with the always-on cross-cutting config — credential
   * volume, {@code qits.*} labels, host alias, host uid). This executor only prepends the runtime
   * binary + {@code run} and shells it out.
   */
  @Inject WorkspaceContainerFactory containerFactory;

  /**
   * Create the shared credential volume once at startup so it exists before any workspace container
   * mounts it — and so an operator can run the one-time login before the first workspace is
   * created. Best-effort: a missing/broken runtime just logs, exactly like the rest of this
   * executor.
   */
  void onStart(@Observes StartupEvent event) {
    ensureClaudeVolume();
  }

  /** Idempotent {@code docker volume create}; no-op when the volume name is blank. */
  void ensureClaudeVolume() {
    String claudeVolume = containerFactory.claudeVolume();
    if (claudeVolume == null || claudeVolume.isBlank()) {
      return;
    }
    ExecResult result = runCapturing(null, List.of(runtime, "volume", "create", claudeVolume));
    if (result.exitCode() != 0) {
      LOG.warnf(
          "Could not ensure shared agent credential volume '%s' (agent auth may be unavailable): %s",
          claudeVolume, result.output());
    }
  }

  @Override
  public String containerName(String workspaceId, String repoId) {
    // repoId is a UUID; the short prefix keeps the name readable and well under docker's length
    // cap while still being effectively unique per repo. Prefix keeps the first char alphanumeric.
    String shortRepo = repoId.length() > 8 ? repoId.substring(0, 8) : repoId;
    return "qits-ws-" + workspaceId + "-" + shortRepo;
  }

  @Override
  public String run(
      String repoId,
      String workspaceId,
      String branch,
      String parent,
      Collection<Integer> publishPorts) {
    String name = containerName(workspaceId, repoId);
    // The factory owns the argv shape and the always-on cross-cutting config (credential volume,
    // qits.* labels, host alias, host uid); this executor only prepends the runtime + `run` verb.
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.addAll(
        containerFactory
            .forWorkspace(repoId, workspaceId, branch, parent, publishPorts)
            .toRunArgv());

    ExecResult result = runCapturing(null, argv);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start container " + name + ": " + result.output());
    }
    return name;
  }

  @Override
  public ExecResult exec(
      String container, String workdir, Map<String, String> env, String... argv) {
    List<String> command = new ArrayList<>(execArgv(container, false, workdir, env));
    for (String arg : argv) {
      command.add(arg);
    }
    return runCapturing(null, command);
  }

  @Override
  public List<String> execArgv(
      String container, boolean tty, String workdir, Map<String, String> env) {
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("exec");
    argv.add(tty ? "-it" : "-i");
    if (workdir != null && !workdir.isBlank()) {
      argv.add("-w");
      argv.add(workdir);
    }
    if (env != null) {
      for (Map.Entry<String, String> e : env.entrySet()) {
        argv.add("-e");
        argv.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
      }
    }
    argv.add(container);
    return argv;
  }

  @Override
  public Integer hostPort(String container, int containerPort) {
    ExecResult result =
        runCapturing(null, List.of(runtime, "port", container, containerPort + "/tcp"));
    if (result.exitCode() != 0) {
      return null;
    }
    // One line per bound address, e.g. "127.0.0.1:32768" (IPv6 lines look like "[::1]:32768").
    for (String line : result.output().split("\n")) {
      int colon = line.lastIndexOf(':');
      if (colon < 0) {
        continue;
      }
      try {
        return Integer.parseInt(line.substring(colon + 1).trim());
      } catch (NumberFormatException ignored) {
        // fall through to the next line
      }
    }
    return null;
  }

  @Override
  public boolean exists(String container) {
    return runCapturing(null, List.of(runtime, "container", "inspect", container)).exitCode() == 0;
  }

  @Override
  public boolean isRunning(String container) {
    ExecResult result =
        runCapturing(
            null, List.of(runtime, "container", "inspect", "-f", "{{.State.Running}}", container));
    // A missing container inspects non-zero; a present one prints "true"/"false" for its run state.
    return result.exitCode() == 0 && "true".equals(result.output().trim());
  }

  @Override
  public void start(String container) {
    ExecResult result = runCapturing(null, List.of(runtime, "start", container));
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start container " + container + ": " + result.output());
    }
  }

  @Override
  public void rm(String container) {
    ExecResult result = runCapturing(null, List.of(runtime, "rm", "-f", container));
    if (result.exitCode() != 0) {
      LOG.debugf("Failed to remove container %s: %s", container, result.output());
    }
  }

  @Override
  public void restart(String container) {
    runCapturing(null, List.of(runtime, "restart", container));
  }

  @Override
  public List<ContainerInfo> listWorkspaceContainers(String repoId) {
    ExecResult result =
        runCapturing(
            null,
            List.of(
                runtime,
                "ps",
                "-a",
                "--filter",
                "label=qits.repository=" + repoId,
                "--format",
                // The trailing qits.worktree column is a one-release back-compat read: containers
                // provisioned before the worktree→workspace rename carry the old label, so a
                // reconcile can still adopt them instead of forcing a recreate. Remove once no
                // pre-rename containers remain.
                "{{.Names}}\t{{.Label \"qits.workspace\"}}\t{{.Label \"qits.branch\"}}\t{{.Label"
                    + " \"qits.parent\"}}\t{{.Label \"qits.worktree\"}}"));
    if (result.exitCode() != 0) {
      LOG.warnf("Failed to list containers for repo %s: %s", repoId, result.output());
      return List.of();
    }
    List<ContainerInfo> infos = new ArrayList<>();
    for (String line : result.output().split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      String[] parts = line.split("\t", -1);
      String name = parts.length > 0 ? parts[0] : "";
      String workspaceId = parts.length > 1 ? parts[1] : "";
      String branch = parts.length > 2 ? emptyToNull(parts[2]) : null;
      String parent = parts.length > 3 ? emptyToNull(parts[3]) : null;
      String legacyWorkspaceId = parts.length > 4 ? parts[4] : "";
      if (workspaceId.isBlank()) {
        workspaceId = legacyWorkspaceId; // pre-rename container labelled qits.worktree
      }
      if (!workspaceId.isBlank()) {
        infos.add(new ContainerInfo(name, workspaceId, branch, parent));
      }
    }
    return infos;
  }

  // --- Daemon sessions (tmux) -----------------------------------------------------------------
  //
  // Each daemon runs in a detached tmux session on a dedicated socket (-L qits-<id>): a fresh
  // server
  // per daemon inherits this exec's -e env at first start with no shared-server staleness, and
  // isolates daemons from one another. `pipe-pane` mirrors the pane to a logfile qits tails. Paths
  // derive from the daemon id (a server-generated UUID — safe to interpolate); the startScript is
  // never interpolated (it rides as the positional arg $1 to avoid quoting/injection issues).

  /** Container-side run directory for daemon session state (logs, scripts, exit codes). */
  private static final String DAEMON_DIR = "/tmp/qits-daemons";

  private static String socket(String daemonId) {
    return "qits-" + daemonId;
  }

  @Override
  public String daemonLogPath(String daemonId) {
    return DAEMON_DIR + "/" + daemonId + ".log";
  }

  @Override
  public void startDaemon(
      String container, String daemonId, String script, Map<String, String> env) {
    String sock = socket(daemonId);
    String base = DAEMON_DIR + "/" + daemonId;
    // $1 is the untrusted startScript (written to a file, then run by the pane); everything else is
    // built from the safe id. The pane records its exit code so daemonExitCode can tell a clean
    // stop
    // from a crash. `new-session … \; pipe-pane` is one atomic tmux call so the pane's very first
    // line is mirrored (a separate pipe-pane races the pane's first output and would drop it).
    String launcher =
        "set -e; mkdir -p "
            + DAEMON_DIR
            + "; printf '%s\\n' \"$1\" > "
            + base
            + ".sh; tmux -L "
            + sock
            + " kill-server 2>/dev/null || true; : > "
            + base
            + ".log; rm -f "
            + base
            + ".exit; tmux -L "
            + sock
            + " new-session -d -s main -x 200 -y 50 -c /workspace \"bash "
            + base
            + ".sh; echo \\$? > "
            + base
            + ".exit\" \\; pipe-pane -t main -o \"cat >> "
            + base
            + ".log\"";
    ExecResult result =
        exec(container, "/workspace", env, "bash", "-lc", launcher, "qits-daemon", script);
    if (result.exitCode() != 0) {
      throw new InternalServerErrorException(
          "Failed to start daemon session " + daemonId + ": " + result.output());
    }
  }

  @Override
  public boolean daemonAlive(String container, String daemonId) {
    return exec(
                container,
                null,
                Map.of(),
                "tmux",
                "-L",
                socket(daemonId),
                "has-session",
                "-t",
                "main")
            .exitCode()
        == 0;
  }

  @Override
  public Integer daemonExitCode(String container, String daemonId) {
    ExecResult r = exec(container, null, Map.of(), "cat", DAEMON_DIR + "/" + daemonId + ".exit");
    if (r.exitCode() != 0) {
      return null;
    }
    try {
      return Integer.valueOf(r.output().trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Override
  public boolean signalDaemon(String container, String daemonId, String signal) {
    String sock = socket(daemonId);
    // The pane leader is the process-group leader (tmux #{pane_pid}); signal the whole group so a
    // compound script's children get it too. The signal name is a controlled value.
    String cmd =
        "p=$(tmux -L "
            + sock
            + " list-panes -t main -F '#{pane_pid}' 2>/dev/null); [ -n \"$p\" ] || exit 1;"
            + " kill -s "
            + signal
            + " -- -\"$p\"";
    return exec(container, null, Map.of(), "sh", "-c", cmd).exitCode() == 0;
  }

  @Override
  public void killDaemon(String container, String daemonId) {
    String sock = socket(daemonId);
    // SIGKILL the pane's process group, then tear the server down. Both best-effort.
    String cmd =
        "p=$(tmux -L "
            + sock
            + " list-panes -t main -F '#{pane_pid}' 2>/dev/null);"
            + " [ -n \"$p\" ] && kill -s KILL -- -\"$p\" 2>/dev/null;"
            + " tmux -L "
            + sock
            + " kill-server 2>/dev/null; true";
    exec(container, null, Map.of(), "sh", "-c", cmd);
  }

  @Override
  public String attachDaemonCommand(String daemonId) {
    // `exec` so the docker-exec shell becomes the tmux client, keeping the pgid the registry
    // recorded valid; a group-kill on close then only detaches this client, never the detached
    // daemon server on the -L socket.
    return "exec tmux -L " + socket(daemonId) + " attach -t main";
  }

  private static String emptyToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private ExecResult runCapturing(Path cwd, List<String> command) {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd.toFile());
    }
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
