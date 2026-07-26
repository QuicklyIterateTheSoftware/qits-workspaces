package eu.wohlben.qits.workspaces.control;

import java.time.Duration;
import java.util.Optional;

/**
 * Drives (and awaits) the in-container workspace-daemon's bootstrap chain — the
 * install/migrate/seed commands the daemon runs from its own {@code .qits-config.yml}, between the
 * self-clone and daemon start (docs/epics/qits-workspace-daemon/ Part 3). Framework-free so it
 * lives in {@code domain}; the real implementation is the backend {@code WorkspaceDaemonRegistry}
 * (service module), reached over the control socket. Apps without the backend impl (cli, tests with
 * no daemon) provide a test double or simply have no bean; the runner injects it as {@code
 * Instance<>}.
 *
 * <p>Two entry points share the same wait machinery. On a <b>fresh provision</b> the daemon runs
 * the chain autonomously (no request); the host only {@link #awaitBootstrap awaits} the terminal
 * outcome, feeding each step's progress to a {@link StepSink}. A <b>manual re-run</b> from the
 * workspace surface {@link #runBootstrap sends} the daemon a run request and then awaits the same
 * way.
 *
 * <p>Distinct from {@link WorkspaceDaemonProvisioner} (clone) and {@link WorkspaceConfigReader}
 * (read): this one runs the chain. The host records the streamed outcomes and gates service
 * auto-start on {@link Result#ok()} — a failed chain withholds {@code WorkspaceReadyForServices}.
 */
public interface WorkspaceBootstrapDriver {

  /**
   * Await the daemon's autonomous boot-time bootstrap chain for {@code workspaceId}, feeding {@code
   * sink} as steps stream in. Returns {@link Optional#empty()} when no daemon becomes live within
   * {@code connectTimeout} (the caller then withholds service auto-start — the chain never ran).
   *
   * @param repoId the workspace's repository (the container key; the socket-backed impl awaits by
   *     {@code workspaceId} alone and ignores it)
   * @param chainTimeout how long, once a daemon is live, to wait for the terminal {@code
   *     Bootstrapped}; a timeout resolves to a failed {@link Result}
   */
  Optional<Result> awaitBootstrap(
      String repoId,
      String workspaceId,
      StepSink sink,
      Duration connectTimeout,
      Duration chainTimeout);

  /**
   * Ask the daemon to re-run the chain (blank {@code name}) or a single step ({@code name}), then
   * await it the same way as {@link #awaitBootstrap}. Returns empty when no daemon is live to run
   * it.
   */
  Optional<Result> runBootstrap(
      String repoId, String workspaceId, String name, StepSink sink, Duration chainTimeout);

  /** The chain-complete outcome: {@code ok} false means a step failed and services stay off. */
  record Result(boolean ok) {}

  /**
   * Receives a bootstrap chain's per-step progress as the daemon streams it, so the host can settle
   * process segments, record run outcomes, and hint the UI. Callbacks arrive on the socket thread.
   */
  interface StepSink {

    /** A step is entering a phase: {@code CHECK}, {@code EXECUTE}, or {@code SKIP}. */
    void onStep(String name, String phase);

    /** A line of the step's streamed output. */
    void onLine(String name, String line);

    /**
     * A step's terminal outcome: {@code SKIPPED}, {@code SUCCEEDED}, or {@code FAILED}, with the
     * process exit code ({@code null} unknown).
     */
    void onOutcome(String name, String outcome, Integer exitCode);
  }
}
