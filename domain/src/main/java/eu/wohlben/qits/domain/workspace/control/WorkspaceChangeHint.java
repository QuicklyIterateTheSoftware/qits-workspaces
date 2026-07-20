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
    COMMANDS,
    /**
     * The bootstrap chain's state changed for this workspace — a chain started or ended, or a
     * command's last-run outcome was recorded. The frontend re-fetches the workspace bootstrap
     * surface.
     */
    BOOTSTRAP,
    /**
     * The workspace working tree changed on disk — a file was created, modified, deleted, or moved
     * (typically the coding agent scaffolding without a commit). Fired by {@code
     * WorkspaceWatchService} from a per-workspace {@code inotifywait}; the frontend re-fetches
     * {@code /files} and {@code /detection}.
     */
    FILES,
    /**
     * A technical process for this workspace started or completed (e.g. a container start). The
     * frontend re-fetches {@code /active-process} and — when an id comes back — opens the separate
     * payload-bearing process SSE stream; this channel stays hint-only.
     */
    PROCESS,
    /**
     * The workspace's persisted prompt draft changed — an autosave {@code PUT} or a {@code DELETE}
     * (Discard/Clear). Fired by {@code WorkspacePromptDraftService}; the frontend invalidates the
     * draft query so a draft edited on another device rehydrates the open view (applied only when
     * the local draft is pristine, so mid-typing is never clobbered).
     */
    PROMPT_DRAFT
  }
}
