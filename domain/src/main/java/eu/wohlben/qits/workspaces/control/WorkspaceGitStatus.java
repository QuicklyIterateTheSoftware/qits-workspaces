package eu.wohlben.qits.workspaces.control;

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
   *
   * <p><b>Empty is not "clean".</b> Every caller gating a destructive operation must treat unknown
   * as <em>dirty</em> and refuse: only an explicit {@code true} is permission to proceed. The host
   * no longer has a way to find out for itself — the {@code docker exec git status} it used to fall
   * back on moved into the daemon with the rest of the in-container git — so "I cannot tell" and
   * "there are no changes" are genuinely different answers and must not be collapsed.
   */
  Optional<Boolean> isClean(Long workspaceId);

  /**
   * The commit {@code workspaceId}'s checkout is currently on, as the daemon last reported it, or
   * {@link Optional#empty()} if unknown (no live daemon / not yet reported).
   *
   * <p>Arrives on the same {@code GitStatus} frame as {@link #isClean} — the daemon recomputes both
   * from one {@code git status --porcelain=v2 --branch} — so the two are always consistent with
   * each other and with the same instant. Comparing this against the origin's ref is how the host
   * answers "is this workspace fully pushed" without reaching into the container; the daemon
   * auto-pushes committed work, so a match is the steady state and a mismatch means work in flight.
   * Same fail-closed rule as {@link #isClean}: empty means refuse, not "in sync".
   */
  Optional<String> head(Long workspaceId);
}
