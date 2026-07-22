package eu.wohlben.qits.domain.repository.control;

/**
 * Informational liveness signal: whether a workspace's in-container {@code workspace-daemon}
 * currently holds an open dial-home socket (docs/epics/qits-workspace-daemon/). Framework-free (no
 * websockets type) so it lives in {@code domain}; the {@code service} module implements it over
 * {@code WorkspaceDaemonRegistry}, and {@link WorkspaceService} reads it as an {@code Instance<>}
 * that is simply empty in apps without the backend (e.g. {@code cli}, tests).
 *
 * <p><b>Part 1 is observational only.</b> {@link WorkspaceService#ensureContainer} may log this
 * signal but must never branch on it — the container reconciliation ladder (docker run-state → the
 * branch ref) is unchanged. Later parts, once the socket actually drives behaviour, may consult it.
 */
public interface WorkspaceDaemonLiveness {

  /**
   * Whether {@code workspace-daemon} for {@code workspaceId} has an open control socket right now.
   */
  boolean isDaemonLive(String workspaceId);
}
