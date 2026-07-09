package eu.wohlben.qits.domain.repository.control;

/**
 * Fired when a workspace container completes a cold&#8594;RUNNING transition in {@link
 * WorkspaceService#ensureContainer} — either a fresh provision or an in-place restart of an Exited
 * container. The already-running short-circuit deliberately does <em>not</em> fire this (nothing
 * changed, and that terminates the auto-start reentrancy loop).
 *
 * <p>Consumed asynchronously in the {@code daemon} area ({@code DaemonLifecycleCoupler}) to bring a
 * repository's auto-start daemons up with the container. The dependency direction forbids the
 * direct call ({@code daemon.control} already depends on {@code repository.control}); this event
 * inverts it, mirroring the {@code WorkspaceChangeHint}/{@code WorkspaceChangePublisher} pattern.
 */
public record WorkspaceContainerStarted(String repoId, String workspaceId) {}
