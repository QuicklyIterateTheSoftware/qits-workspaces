package eu.wohlben.qits.domain.repository.control;

import java.util.Optional;

/**
 * The last working-tree cleanliness a workspace's in-container {@code workspace-daemon} reported
 * over its dial-home socket (docs/epics/qits-workspace-daemon/). Framework-free (no websockets
 * type) so it lives in {@code domain}; the {@code service} module implements it over {@code
 * WorkspaceDaemonRegistry}, and {@link WorkspaceService} reads it as an {@code Instance<>} that is
 * simply empty in apps without the backend (e.g. {@code cli}, tests).
 *
 * <p>The value is cached in-memory only while the daemon is connected (the container is RUNNING);
 * {@link Optional#empty()} means "unknown" — no daemon, or none has reported yet. The daemon
 * re-reports on every reconnect, so a qits restart self-heals within one socket round-trip.
 */
public interface WorkspaceGitStatus {

  /**
   * Whether {@code workspaceId}'s working tree is currently clean ({@code git status --porcelain}
   * empty), or {@link Optional#empty()} if unknown (no live daemon / not yet reported).
   */
  Optional<Boolean> isClean(String workspaceId);
}
