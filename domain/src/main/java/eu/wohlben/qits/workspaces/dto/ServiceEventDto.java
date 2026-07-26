package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.ServiceEventKind;
import eu.wohlben.qits.workspaces.entity.ServiceEventSeverity;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import java.time.Instant;

/**
 * Something a service's supervisor or one of its log observers saw: a state transition or a
 * classified error. Sinks get the classification, the evidence ({@code logExcerpt}), and — for
 * observer findings — where the evidence sits in its source: {@code source} is {@code "output"} or
 * the tailed file's workspace-relative path, {@code anchorFrom}/{@code anchorTo} bound the excerpt
 * ({@code command_log_line} sequences for output, 1-based file line numbers for a tail), and {@code
 * sourceEpoch} marks the tail's rotation epoch. Anchor fields are null on plain status transitions.
 * Events are persisted as {@code service_event} rows, so the history survives the JVM.
 */
public record ServiceEventDto(
    String repoId,
    String workspaceId,
    String serviceId,
    String serviceName,
    ServiceEventKind kind,
    ServiceEventSeverity severity,
    ServiceStatus status,
    String summary,
    String logExcerpt,
    String commandId,
    String source,
    Long anchorFrom,
    Long anchorTo,
    Instant sourceEpoch,
    Instant timestamp) {}
