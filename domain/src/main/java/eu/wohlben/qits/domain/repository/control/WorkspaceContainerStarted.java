package eu.wohlben.qits.domain.repository.control;

/**
 * Fired when a workspace container completes a cold&#8594;RUNNING transition in {@link
 * WorkspaceService#ensureContainer} — either a fresh provision or an in-place restart of an Exited
 * container, distinguished by {@code freshProvision}. The already-running short-circuit
 * deliberately does <em>not</em> fire this (nothing changed, and that terminates the auto-start
 * reentrancy loop).
 *
 * <p>Consumed asynchronously in the {@code bootstrap} area ({@code WorkspaceBootstrapRunner}),
 * which runs the repository's bootstrap chain on a fresh provision and then announces {@link
 * WorkspaceReadyForDaemons} — the event daemon auto-start actually couples to. A plain restart (or
 * an empty chain) passes straight through. The dependency direction forbids the direct call ({@code
 * bootstrap.control} and {@code daemon.control} already depend on {@code repository.control}); this
 * event inverts it, mirroring the {@code WorkspaceChangeHint}/{@code WorkspaceChangePublisher}
 * pattern.
 *
 * <p>{@code technicalProcessId} correlates the asynchronous bootstrap/daemon phases with the
 * technical process that streamed the provision (the observer runs on the async observer thread, so
 * the correlation cannot ride a {@code ThreadLocal}). Null when the start wasn't process-tracked
 * (internal blocking callers like {@code CommandService.prepare}).
 *
 * <p>{@code freshProvision} is true only for the {@code docker run} + clone path — the transition
 * that leaves a bare checkout needing bootstrap. An in-place restart keeps its {@code /workspace}
 * clone and bootstrap state, so the chain does not re-run for it.
 */
public record WorkspaceContainerStarted(
    String repoId, String workspaceId, String technicalProcessId, boolean freshProvision) {}
