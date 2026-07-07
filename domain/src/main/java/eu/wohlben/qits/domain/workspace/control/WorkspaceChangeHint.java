package eu.wohlben.qits.domain.workspace.control;

/**
 * A payload-free "something changed, re-read it" signal for one workspace's live channel. Fired at
 * the existing mutation choke-points (daemon status, daemon events, telemetry ingest, command
 * lifecycle) and delivered — via CDI async events — to the SSE boundary in the {@code service}
 * module, which pushes the {@link Topic} name to subscribed browsers. The hint carries no data: the
 * frontend reacts by re-fetching through the unchanged REST endpoints, so a dropped or missed hint
 * self-heals on the next hint or on reconnect.
 */
public record WorkspaceChangeHint(String repoId, String workspaceId, Topic topic) {

  /** The kind of change; maps 1:1 to a frontend query-invalidation. */
  public enum Topic {
    /** A daemon instance's status flipped (start, ready, exit, crash, restart, degrade). */
    DAEMONS,
    /** A daemon event row was persisted. */
    DAEMON_EVENTS,
    /** The workspace's telemetry buffers got new data (debounced — highest churn). */
    TELEMETRY,
    /** A command's lifecycle changed (started, exited, terminated). */
    COMMANDS
  }
}
