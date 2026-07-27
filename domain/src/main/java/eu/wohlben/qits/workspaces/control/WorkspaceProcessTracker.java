package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Frames a long-running workspace operation — bringing a container up, recreating it — as a
 * segmented, streamable technical process, so a UI can follow {@code docker-run} → {@code clone} →
 * {@code container-start} live instead of watching a spinner.
 *
 * <p>A port, not an implementation: the technical-process framework itself is a cross-context
 * streaming primitive (it also carries repository push/pull), so it stays with the owning
 * application and this context consumes only the seven verbs it actually calls.
 *
 * <p>Injected as {@code Instance<WorkspaceProcessTracker>} and genuinely optional. With no
 * implementation present the work still happens, on the same worker thread, with the same result —
 * only the streamed narration is absent and the returned process id is {@code null}, which the
 * ensure/recreate responses already permit.
 */
public interface WorkspaceProcessTracker {

  /** Begin tracking an operation on this workspace. */
  Handle begin(String repoId, String workspaceId, Long workspaceRowId);

  /**
   * The id of the operation currently live for this workspace, if any. Takes the workspace's own
   * id: this is the route-facing half of the port, and a workspace is addressed by its identifier.
   */
  Optional<String> activeFor(Long id);

  /** One tracked operation. Segment names are free-form and shared with the frontend. */
  interface Handle {

    /** The process id, as handed back to the caller of ensure/recreate. */
    String id();

    /** Open a segment; output appended afterwards belongs to it until it settles. */
    void openSegment(String name);

    /** Append one line of output to an open segment. */
    void appendLine(String segmentName, String line);

    /** Close a segment, successfully or not. */
    void settleSegment(String segmentName, boolean ok);

    /** Close a segment that had nothing to do, with the reason shown in its place. */
    void completeNoOp(String segmentName, String note);

    /** Terminal: the operation finished. */
    void finishProvision(boolean ok);

    /** Terminal: the operation failed, with the message shown to the user. */
    void failProvision(String message);
  }
}
