package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import java.time.Instant;

/**
 * A workspace as a history list entry. Keyed by the surrogate {@code id} (not {@code workspaceId},
 * which is reusable once a workspace is resolved).
 */
public record WorkspaceHistoryDto(
    Long id,
    String workspaceId,
    String parent,
    WorkspaceStatus status,
    Instant createdAt,
    Instant resolvedAt) {}
