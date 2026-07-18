package eu.wohlben.qits.domain.repository.control;

import java.util.List;
import java.util.Map;

/**
 * The per-workspace container runtime — the sibling of {@link GitExecutor} for the <em>other</em>
 * on-disk mutation qits performs. A workspace is a branch (host-side, in the bare origin) plus a
 * container that owns a clone of it under {@code /workspace}; every action script, dependency
 * install, dev server, daemon and coding-agent command runs inside that container via {@code exec},
 * so nothing untrusted ever touches the host home dir or its credentials.
 *
 * <p>An interface (implemented by {@link DockerExecutor}) so it stays runtime-agnostic — docker
 * today, rootless podman or a remote node later — and so tests can supply a fake without a real
 * container engine.
 */
public interface ContainerRuntime {

  /** The exit code and combined stdout/stderr of a finished container command. */
  record ExecResult(int exitCode, String output) {}

  /** A discovered workspace container, read back from its {@code qits.*} labels. */
  record ContainerInfo(String name, String workspaceId, String branch, String parent) {}

  /** The deterministic container name for a workspace — no {@code inspect} round-trip needed. */
  String containerName(String workspaceId, String repoId);

  /**
   * Creates and starts the workspace's container ({@code docker run -d … sleep infinity}) on the
   * shared {@code qits-net} with the {@code qits.repository}/{@code qits.workspace}/{@code
   * qits.branch}/{@code qits.parent} labels that startup reconciliation reads back. Returns the
   * container name. Throws on failure.
   *
   * <p>The container publishes <em>no</em> host ports: qits and every workspace container share one
   * Docker network, so the daemon web-view proxy reaches a container port by its container name
   * over that network (see {@link #resolveTarget}). That removes the create-time port-publishing
   * constraint entirely — a daemon can gain a web-view port after its container exists and still be
   * reachable without a recreation.
   */
  String run(String repoId, String workspaceId, String branch, String parent);

  /**
   * Where the qits process connects to reach {@code containerPort} inside {@code container} — the
   * daemon web-view proxy's origin. On the shared network this is the container's DNS name and the
   * real container port; the test fake maps it to {@code 127.0.0.1}. Null only when the target
   * cannot be resolved at all (e.g. the container is gone), in which case the proxy 502s.
   */
  ProxyOrigin resolveTarget(String container, int containerPort);

  /**
   * Runs a one-shot command inside the container and captures its output (mirrors {@link
   * GitExecutor#execAllowNonZero}), used for in-container git verbs and pid/signal handling. A null
   * {@code workdir} inherits the image's default; {@code env} adds {@code -e K=V} flags.
   */
  ExecResult exec(String container, String workdir, Map<String, String> env, String... argv);

  /**
   * {@link #exec} with a per-line tap: {@code onLine} receives each output line as it arrives (the
   * technical-process log stream), while the full output is still captured and returned. The
   * default delivers the lines only after completion — {@link DockerExecutor} overrides it with
   * genuine streaming; the test fake keeps the default (ordering is preserved either way).
   */
  default ExecResult exec(
      String container,
      String workdir,
      Map<String, String> env,
      java.util.function.Consumer<String> onLine,
      String... argv) {
    ExecResult result = exec(container, workdir, env, argv);
    if (onLine != null && !result.output().isEmpty()) {
      result.output().lines().forEach(onLine);
    }
    return result;
  }

  /**
   * {@link #run} with a per-line tap on the {@code docker run} output. The default ignores the tap
   * (the capturing run embeds its output in the failure exception); {@link DockerExecutor}
   * overrides it with genuine streaming.
   */
  default String run(
      String repoId,
      String workspaceId,
      String branch,
      String parent,
      java.util.function.Consumer<String> onLine) {
    return run(repoId, workspaceId, branch, parent);
  }

  /**
   * The {@code docker exec} argv <em>prefix</em> up to and including the container name — the
   * caller appends the command to run. {@code tty} selects {@code -it} (interactive PTY) vs {@code
   * -i} (plain pipe). This is the single seam the command registry prepends to every launched
   * script.
   */
  List<String> execArgv(String container, boolean tty, String workdir, Map<String, String> env);

  /** Whether a container with this name exists (running or stopped). */
  boolean exists(String container);

  /**
   * Whether a container with this name exists <em>and</em> is currently running — the
   * live-container guard {@link #exists} can't give, since {@code docker container inspect}
   * succeeds for exited containers too. A host/docker restart leaves qits containers present but
   * {@code Exited}, so the "already provisioned?" check must key off run state, not mere presence.
   */
  boolean isRunning(String container);

  /**
   * Starts a present-but-stopped container ({@code docker start}) — the lossless recovery for a
   * container that died out-of-band (e.g. a host restart): it keeps the {@code /workspace} clone
   * and any unpushed commits, unlike a re-provision that re-clones from origin. Throws on failure.
   */
  void start(String container);

  /** Force-removes the container ({@code docker rm -f}); best-effort, never throws. */
  void rm(String container);

  /**
   * Restarts the container ({@code docker restart}) — the sledgehammer for a stuck process group.
   */
  void restart(String container);

  /** All workspace containers for a repository, read from their {@code qits.*} labels. */
  List<ContainerInfo> listWorkspaceContainers(String repoId);

  // --- Daemon sessions: long-runners decoupled from the qits JVM ------------------------------
  //
  // A daemon must outlive a qits restart and stay observable across one. These methods run it as a
  // detached session inside the container (a tmux session for docker; a plain setsid process for
  // the
  // test fake) whose combined output is mirrored to {@link #daemonLogPath} — the durable line
  // stream
  // qits tails for the ready-pattern, observers, and per-line persistence. Liveness and stop go
  // through the session, not a host-side client, so killing qits leaves the daemon running and a
  // fresh qits reconciles it from {@link #daemonAlive}.

  /**
   * Launch {@code script} as a detached daemon session named by {@code daemonId} inside {@code
   * container}, running on a PTY in {@code /workspace}. {@code env} is applied to the session and
   * inherited by everything it forks. Combined stdout/stderr is mirrored to {@link #daemonLogPath};
   * the session's exit code (when it ends on its own) is recorded for {@link #daemonExitCode}. A
   * stale same-id session is cleared first. Best-effort — throws only on a runtime failure.
   */
  void startDaemon(String container, String daemonId, String script, Map<String, String> env);

  /** Whether the daemon session named {@code daemonId} is currently running. */
  boolean daemonAlive(String container, String daemonId);

  /**
   * The exit code recorded when the daemon session ended on its own, or null if it is still running
   * or was killed before recording one (a kill is treated as a failure by the caller).
   */
  Integer daemonExitCode(String container, String daemonId);

  /**
   * Deliver {@code signal} (e.g. {@code TERM}) to the daemon session's process group — the graceful
   * half of a stop. Returns false if no session is running.
   */
  boolean signalDaemon(String container, String daemonId, String signal);

  /** Force-stop the daemon session: SIGKILL its process group and tear the session down. */
  void killDaemon(String container, String daemonId);

  /**
   * The container-side path of the daemon's mirrored combined-output log — the {@code tail -F}
   * target qits follows for the ready-pattern, observers, and persistence.
   */
  String daemonLogPath(String daemonId);

  /**
   * The container-side shell command that opens an <em>interactive</em> PTY onto the running daemon
   * session — {@code tmux attach} for docker. Run inside a {@code docker exec -it} client (the
   * command registry's PTY path) so the browser can drive full-screen apps (e.g. Quarkus dev's
   * {@code [r]}/{@code [e]} keys). This is the terminal half of the split introduced by Increment 2
   * of tmux-backed daemons: the background {@link #daemonLogPath} tail keeps feeding the durable
   * pipeline (observers/ready/persistence), while this attach client is ephemeral — killing it only
   * detaches the client, leaving the detached daemon session running.
   */
  String attachDaemonCommand(String daemonId);
}
