package eu.wohlben.qits.domain.repository.control;

/**
 * Fired when a workspace container is about to be deliberately removed — {@code stopContainer} (the
 * graceful, lossless stop) or a discard. Unlike its sibling {@link WorkspaceContainerStarted}, this
 * is fired <em>synchronously</em> and <em>before</em> {@code containers.rm}, so an observer can
 * settle the workspace's daemons while the container (and its detached tmux sessions) still exists
 * — turning a deliberate stop into a clean STOPPED settle instead of a misread crash.
 *
 * <p>{@code graceful} distinguishes the two callers: {@code stopContainer} passes {@code true}
 * (send the stop signal and wait the grace window so dev servers flush), a discard passes {@code
 * false} (the work is being thrown away — settle bookkeeping only and let {@code rm} kill the
 * processes).
 */
public record WorkspaceContainerStopping(String repoId, String workspaceId, boolean graceful) {}
