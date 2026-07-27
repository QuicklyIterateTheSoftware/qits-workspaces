package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

/**
 * The per-workspace container runtime — the sibling of {@link GitExecutor} for the <em>other</em>
 * on-disk mutation qits performs. A workspace is a branch (host-side, in the bare origin) plus a
 * container that owns a clone of it under {@code /workspace}; every action script, dependency
 * install, dev server, service and coding-agent command runs inside that container via {@code
 * exec}, so nothing untrusted ever touches the host home dir or its credentials.
 *
 * <p>An interface (implemented by {@link DockerExecutor}) so it stays runtime-agnostic — docker
 * today, rootless podman or a remote node later — and so tests can supply a fake without a real
 * container engine.
 */
public interface ContainerRuntime {

  /** The exit code and combined stdout/stderr of a finished container command. */
  record ExecResult(int exitCode, String output) {}

  /**
   * A discovered workspace container, read back from its {@code qits.*} labels. {@code running}
   * distinguishes a live container from a present-but-{@code Exited} one (a deliberate {@link
   * #stop} or an out-of-band death): the listing includes both (it drives reconcile/adoption, which
   * must see stopped containers), so callers that mean "actually running" — e.g. the workspace
   * runtime status — must filter on this flag rather than mere presence.
   */
  record ContainerInfo(
      String name, String workspaceId, String branch, String parent, boolean running) {}

  /** The deterministic container name for a workspace — no {@code inspect} round-trip needed. */
  String containerName(String workspaceId, String repoId);

  /**
   * Creates and starts the workspace's container ({@code docker run -d …}, whose process is the
   * image's {@code qits-workspace-daemon} ENTRYPOINT — no {@code sleep infinity} keep-alive) on the
   * shared {@code qits-net} with the {@code qits.repository}/{@code qits.workspace}/{@code
   * qits.branch}/{@code qits.parent} labels that startup reconciliation reads back. Returns the
   * container name. Throws on failure.
   *
   * <p>The container publishes <em>no</em> host ports: qits and every workspace container share one
   * Docker network, so the service web-view proxy reaches a container port by its container name
   * over that network (see {@link #resolveTarget}). That removes the create-time port-publishing
   * constraint entirely — a service can gain a web-view port after its container exists and still
   * be reachable without a recreation.
   */
  String run(String repoId, String workspaceId, Long rowId, String branch, String parent);

  /**
   * Where the qits process connects to reach {@code containerPort} inside {@code container} — the
   * service web-view proxy's origin. On the shared network this is the container's DNS name and the
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
      Long rowId,
      String branch,
      String parent,
      java.util.function.Consumer<String> onLine) {
    return run(repoId, workspaceId, rowId, branch, parent);
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

  /**
   * Gracefully stops a running container ({@code docker stop} — SIGTERM + grace) <em>without</em>
   * removing it, so the container and its {@code /workspace} clone survive for a later lossless
   * {@link #start}. This is the pause half of a deliberate workspace stop: unlike {@link #rm} it
   * preserves the working tree (uncommitted/untracked files and unpushed commits alike).
   * Best-effort, never throws.
   */
  void stop(String container);

  /**
   * Force-removes the container ({@code docker rm -f}); best-effort, never throws. Destroys the
   * container's writable layer / {@code /workspace} clone — use only when the work is being
   * discarded, not to pause a workspace (that is {@link #stop}).
   */
  void rm(String container);

  /**
   * Restarts the container ({@code docker restart}) — the sledgehammer for a stuck process group.
   */
  void restart(String container);

  /** All workspace containers for a repository, read from their {@code qits.*} labels. */
  List<ContainerInfo> listWorkspaceContainers(String repoId);

  // --- Per-workspace /workspace volumes -------------------------------------------------------
  //
  // A workspace's checkout lives on a per-workspace named volume mounted at /workspace (not the
  // container's ephemeral writable layer), so it SURVIVES container recreation (image update,
  // crash, prune, host restart) and is reattached on the next provision — the workspace-daemon then
  // skips its
  // self-clone on the already-populated volume. Rich qits.* labels (mirroring the container labels
  // plus qits.managed/qits.project) make dangling volumes detectable and reapable by name-free,
  // charset-safe reconcile. Gated by qits.workspace.persist-workspace; when off, /workspace reverts
  // to the ephemeral layer and none of these are exercised. See
  // docs/epics/qits-workspaces/features/2026-07-25_persistent-workspace-volume.md.

  /** A discovered per-workspace volume, read back from its {@code qits.*} labels. */
  record VolumeInfo(
      String name, String projectId, String repoId, String workspaceId, String branch) {}

  /** The deterministic per-workspace volume name (prefix + {@code workspaceId}); no round-trip. */
  String workspaceVolumeName(String workspaceId);

  /**
   * Create-if-absent the per-workspace {@code /workspace} volume with its {@code qits.*} labels
   * ({@code docker volume create --label …}); idempotent (a no-op on an existing volume, whose
   * labels are left as first created). Called by {@link #run} just before the container mounts it,
   * so the mount always attaches a labeled volume. Best-effort — a broken runtime just logs.
   */
  void ensureWorkspaceVolume(String repoId, String workspaceId, String branch, String parent);

  /**
   * Removes the per-workspace volume ({@code docker volume rm}); best-effort, never throws. The
   * container referencing it must be {@link #rm}'d first (docker refuses to remove an in-use
   * volume). This is the one destructive step that drops a persisted checkout — used only where the
   * work is being discarded (delete-container reclaim, branch discard/abandon, repo delete, GC).
   */
  void removeWorkspaceVolume(String workspaceId);

  /**
   * All qits-managed per-workspace volumes (filtered by {@code
   * label=qits.managed=workspace-volume}), with their labels read back — the input to the
   * dangling-volume reconcile.
   */
  List<VolumeInfo> listWorkspaceVolumes();

  // --- Service sessions ------------------------------------------------------------------------
  //
  // Deliberately absent. Services (dev servers) are spawned, supervised, restarted and stopped by
  // the in-container workspace-daemon, which owns the process and pushes every lifecycle transition
  // home over the control socket; ServiceSupervisor is only the host-side projection of that, and
  // WorkspaceServiceDriver the outbound half. The tmux-over-`docker exec` verbs that used to live
  // here (startService/serviceAlive/serviceExitCode/signalService/killService/serviceLogPath/
  // attachServiceCommand) were the pre-daemon host-exec supervisor, already dead code when this
  // repo was cut (migration-plan.md §3.3) and removed with it. There is no host-execution
  // fallback: a workspace with no live daemon cannot run a service, and that is the honest state.

}
