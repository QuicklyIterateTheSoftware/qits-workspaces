package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;

/**
 * One of the repository's daemons in a worktree with its supervised runtime state. {@code
 * commandId} is the current (or most recent) registry command backing the instance — the
 * log/terminal re-attach target — and null if the daemon never ran in this JVM.
 */
public record DaemonInstanceDto(
    RepositoryDaemonDto daemon, DaemonStatus status, int restartCount, String commandId) {}
