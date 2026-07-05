package eu.wohlben.qits.domain.repository.control;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The per-worktree container runtime — the sibling of {@link GitExecutor} for the <em>other</em>
 * on-disk mutation qits performs. A worktree is a branch (host-side, in the bare origin) plus a
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
  record ContainerInfo(String name, String worktreeId, String branch, String parent) {}

  /** The deterministic container name for a worktree — no {@code inspect} round-trip needed. */
  String containerName(String worktreeId, String repoId);

  /**
   * Creates and starts the worktree's container ({@code docker run -d … sleep infinity}) with the
   * {@code qits.repository}/{@code qits.worktree}/{@code qits.branch}/{@code qits.parent} labels
   * that startup reconciliation reads back. Returns the container name. Throws on failure.
   *
   * <p>{@code publishPorts} are container ports published to an ephemeral localhost port on the
   * host ({@code -p 127.0.0.1:0:<port>}) — the host→container channel the daemon web-view proxy
   * targets. Publishing must happen at creation (docker cannot add ports to a live container), so
   * the caller passes every port the repository's daemon definitions currently declare; localhost
   * binding keeps them off the LAN (the browser reaches daemons through qits, never directly).
   */
  String run(
      String repoId,
      String worktreeId,
      String branch,
      String parent,
      Collection<Integer> publishPorts);

  /**
   * The ephemeral host port a published container port landed on ({@code docker port}), or null
   * when the container doesn't publish it — e.g. it predates the daemon definition declaring the
   * port, in which case it must be recreated to pick the mapping up.
   */
  Integer hostPort(String container, int containerPort);

  /**
   * Runs a one-shot command inside the container and captures its output (mirrors {@link
   * GitExecutor#execAllowNonZero}), used for in-container git verbs and pid/signal handling. A null
   * {@code workdir} inherits the image's default; {@code env} adds {@code -e K=V} flags.
   */
  ExecResult exec(String container, String workdir, Map<String, String> env, String... argv);

  /**
   * The {@code docker exec} argv <em>prefix</em> up to and including the container name — the
   * caller appends the command to run. {@code tty} selects {@code -it} (interactive PTY) vs {@code
   * -i} (plain pipe). This is the single seam the command registry prepends to every launched
   * script.
   */
  List<String> execArgv(String container, boolean tty, String workdir, Map<String, String> env);

  /** Whether a container with this name exists (running or stopped). */
  boolean exists(String container);

  /** Force-removes the container ({@code docker rm -f}); best-effort, never throws. */
  void rm(String container);

  /**
   * Restarts the container ({@code docker restart}) — the sledgehammer for a stuck process group.
   */
  void restart(String container);

  /** All workspace containers for a repository, read from their {@code qits.*} labels. */
  List<ContainerInfo> listWorktreeContainers(String repoId);

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
}
