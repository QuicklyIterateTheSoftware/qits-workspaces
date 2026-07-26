package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import java.time.Instant;

/** One entry in a workspace's history timeline. */
public record WorkspaceEventDto(
    WorkspaceEventType type,
    String branch,
    String parent,
    String target,
    String commit,
    String note,
    Instant at) {}
