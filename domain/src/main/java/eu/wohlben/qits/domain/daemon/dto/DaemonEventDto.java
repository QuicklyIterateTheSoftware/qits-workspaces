package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import java.time.Instant;

/**
 * Something a daemon's supervisor or one of its log observers saw: a state transition or a
 * classified error. Sinks get both the classification and the evidence ({@code logExcerpt}).
 * In-memory only — the feed shows recent events; the full history is the command's log.
 */
public record DaemonEventDto(
    String repoId,
    String worktreeId,
    String daemonId,
    String daemonName,
    DaemonEventKind kind,
    DaemonEventSeverity severity,
    DaemonStatus status,
    String summary,
    String logExcerpt,
    String commandId,
    Instant timestamp) {}
