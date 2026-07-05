package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.repository.entity.WorkspaceEventType;
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
