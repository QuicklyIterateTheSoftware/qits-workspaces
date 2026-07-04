package eu.wohlben.qits.domain.repository.control;

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
   */
  String run(String repoId, String worktreeId, String branch, String parent);

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
}
