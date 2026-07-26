package eu.wohlben.qits.workspaces.control;

/**
 * Fired when a freshly started workspace container is ready for service auto-start: immediately
 * after {@link WorkspaceContainerStarted} when there is nothing to bootstrap (a plain restart, an
 * empty chain, or the kill switch), or after the bootstrap chain completed successfully. A failed
 * chain deliberately never fires this — a dev server on an unbootstrapped checkout would just burn
 * its restart budget crash-looping, turning one clear failure into noise.
 *
 * <p>This exists because CDI gives two async observers of one event no ordering: bootstrap must
 * finish before service auto-start, so the sequencing is structural — {@code
 * WorkspaceBootstrapRunner} observes the container start and {@code ServiceLifecycleCoupler}
 * observes this. It lives in {@code repository.control} beside its trigger so both areas depend
 * only on the repository area.
 *
 * <p>{@code technicalProcessId} threads the provision's technical process through to the daemon
 * phase (see {@link WorkspaceContainerStarted}); null when the start wasn't process-tracked — a
 * manual chain re-run also passes null, and the coupler tolerates services that are already up.
 */
public record WorkspaceReadyForServices(
    String repoId, String workspaceId, String technicalProcessId) {}
