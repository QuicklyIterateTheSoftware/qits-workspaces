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
 * @param preamble markdown: the reason/goal authored at creation
 * @param result markdown: the outcome authored at resolution
 * @param resolvedAt when the workspace was resolved (null while ACTIVE)
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
    String preamble,
    String result,
    Instant resolvedAt) {}
