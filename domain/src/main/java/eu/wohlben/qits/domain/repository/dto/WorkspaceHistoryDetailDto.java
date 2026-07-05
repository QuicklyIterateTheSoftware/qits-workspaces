package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.command.dto.CommandDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import java.time.Instant;
import java.util.List;

/** A workspace's full history: its narrative, event timeline, and the commands that ran in it. */
public record WorkspaceHistoryDetailDto(
    Long id,
    String workspaceId,
    String parent,
    WorkspaceStatus status,
    String preamble,
    String result,
    Instant createdAt,
    Instant resolvedAt,
    List<WorkspaceEventDto> events,
    List<CommandDto> commands) {}
