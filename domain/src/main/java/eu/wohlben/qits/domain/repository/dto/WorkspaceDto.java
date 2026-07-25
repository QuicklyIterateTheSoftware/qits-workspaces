package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import java.time.Instant;

/**
 * @param ahead commits the workspace's branch has that its parent does not (commits in front)
 * @param behind commits the parent has that the workspace's branch does not (commits it trails by)
 * @param conflictsWithParent whether merging the parent into this branch would hit merge conflicts.
 *     Only computed (and ever {@code true}) when the branch has diverged from its parent (both
 *     ahead and behind); {@code false} for branches that can be fast-forwarded or merged cleanly.
 *     Drives the "cannot integrate cleanly" warning in the branch tree.
 * @param status the workspace's resolution state (ACTIVE, or INTEGRATED/ABANDONED for history)
 * @param runtimeStatus the container's runtime state (RUNNING/STOPPED/PROVISIONING/FAILED),
 *     independent of {@code status}: the branch is the source of truth, the container is a
 *     recreatable cache of it
 * @param runtimeError when {@code runtimeStatus} is FAILED, why the last re-provision failed
 * @param clean whether the workspace's in-container working tree is clean ({@code true}) or has
 *     uncommitted changes ({@code false}), as last reported by {@code workspace-daemon} over its
 *     socket; {@code null} when unknown — the daemon only reports while the container is RUNNING,
 *     so a STOPPED workspace (or one whose daemon hasn't reported yet) carries no clean/dirty badge
 * @param preamble markdown: the reason/goal authored at creation
 * @param result markdown: the outcome authored at resolution
 * @param resolvedAt when the workspace was resolved (null while ACTIVE)
 * @param daemonConnectedAt when the workspace's in-container {@code workspace-daemon} registered
 *     its control socket — the workspace's "connected since" (docs/epics/qits-workspace-registry/).
 *     {@code null} when unknown: like {@code clean}, the registry only knows it while the container
 *     is RUNNING, and it resets on each daemon (re)connect (it is not a durable first-registered
 *     time)
 * @param daemonVersion the release version of the daemon binary the running container is on (Maven
 *     {@code project.version}); {@code null} when unknown (no live daemon, or an older daemon image
 *     that announced none)
 * @param daemonBuildTime when that daemon binary was built — distinguishes floating {@code
 *     -SNAPSHOT} builds sharing one version; {@code null} when unknown
 * @param daemonOutdated whether this workspace's daemon build is strictly older than the newest one
 *     connected anywhere in the workspace registry — {@code true} means a newer workspace-daemon is
 *     available, so the UI shows a warning and offers a recreate. {@code null} when not comparable
 *     (no live daemon, no reported build time on either side) — no warning; only ever {@code true}
 *     or {@code null} in practice, since the newest and any tied daemons are simply not outdated
 *     (docs/epics/qits-workspace-registry/)
 */
public record WorkspaceDto(
    String workspaceId,
    String parent,
    String branch,
    Integer ahead,
    Integer behind,
    boolean conflictsWithParent,
    WorkspaceStatus status,
    WorkspaceRuntimeStatus runtimeStatus,
    String runtimeError,
    Boolean clean,
    String preamble,
    String result,
    Instant resolvedAt,
    Instant daemonConnectedAt,
    String daemonVersion,
    Instant daemonBuildTime,
    Boolean daemonOutdated) {}
