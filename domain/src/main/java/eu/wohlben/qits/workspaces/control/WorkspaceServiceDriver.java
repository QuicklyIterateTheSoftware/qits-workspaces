package eu.wohlben.qits.workspaces.control;

import java.util.Map;

/**
 * Drives the in-container workspace-daemon's <b>services</b> (dev servers) and receives their
 * lifecycle events — the supervision half of the daemon's autonomous startup (clone → config →
 * bootstrap → <b>services</b>), inverted onto the control socket (docs/epics/qits-workspace-daemon/
 * Part 4). Framework-free so it lives in {@code domain}; the real implementation is the backend
 * {@code WorkspaceDaemonRegistry} (service module). Apps without the backend impl (cli, tests with
 * no daemon) provide a test double or have no bean; the host coordinator injects it as {@code
 * Instance<>} and falls back to the tmux path when it is absent (the degradation contract).
 *
 * <p>Unlike {@link WorkspaceBootstrapDriver} (a bounded await of a one-shot chain), services are
 * long-lived, so this is <b>push/continuous</b>: the daemon owns the process lifecycle
 * (spawn/restart/backoff/policy/group-kill) and streams every transition; the host {@linkplain
 * #subscribe subscribes} once at startup and projects the events into its display state machine,
 * process segments, and web-view proxy. The host issues only <b>subsequent</b> operations — {@link
 * #startService manual start} and {@link #signalService stop} — never auto-start, which the daemon
 * self-runs.
 */
public interface WorkspaceServiceDriver {

  /**
   * Ask the daemon to start a service now (a manual/subsequent start; auto-start is daemon-driven).
   * {@code script}/{@code env} carry the definition so a service not yet in the committed config
   * can still be started; blank {@code script} means "look it up in the in-container config by
   * name". A no-op when no daemon is live.
   */
  void startService(String workspaceId, String serviceName, String script, Map<String, String> env);

  /**
   * Ask the daemon to deliver {@code signal} (bare name, e.g. {@code TERM}) to a running service —
   * the stop request. A no-op when no daemon is live.
   */
  void signalService(String workspaceId, String serviceName, String signal);

  /**
   * Register a sink to receive every service's streamed lifecycle events and output, for the life
   * of the app. The host coordinator subscribes once at startup; on a socket reconnect the daemon
   * re-reports each running service's current state, so a qits restart re-adopts them through the
   * same sink.
   */
  void subscribe(ServiceEventSink sink);

  /**
   * Receives service events as the daemon streams them. Callbacks arrive on the socket thread and
   * carry {@code repoId} (which the backend learns from the daemon's {@code Hello}) so the host can
   * resolve the service <em>name</em> the daemon reports to the repository-scoped service
   * definition (its UUID, web-view port, etc.) it keys supervision state by — the daemon speaks
   * names, the host keys by (repoId, workspaceId, id).
   */
  interface ServiceEventSink {

    /**
     * A service's lifecycle transition: {@code STARTING}, {@code READY}, {@code RESTARTING}, {@code
     * CRASHED}, or {@code STOPPED}, with the process exit code ({@code null} unless it exited).
     */
    void onState(
        String repoId, String workspaceId, String serviceName, String state, Integer exitCode);

    /** A line of a service's streamed output ({@code stream} is {@code STDOUT}/{@code STDERR}). */
    void onLine(String repoId, String workspaceId, String serviceName, String stream, String line);
  }
}
