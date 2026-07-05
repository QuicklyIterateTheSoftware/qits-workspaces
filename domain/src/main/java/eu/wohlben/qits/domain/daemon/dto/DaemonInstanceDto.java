package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;

/**
 * One of the repository's daemons in a workspace with its supervised runtime state. {@code
 * commandId} is the current (or most recent) registry command backing the instance — the
 * log/terminal re-attach target — and null if the daemon never ran in this JVM. {@code proxyPath}
 * is the qits-origin base path the daemon's app is served under ({@code
 * /daemon/{workspaceId}/{daemonId}/}); its presence is the web-viewable flag (set iff the
 * definition declares an {@code httpPort}) — combine with a live {@code status} before framing it.
 */
public record DaemonInstanceDto(
    RepositoryDaemonDto daemon,
    DaemonStatus status,
    int restartCount,
    String commandId,
    String proxyPath) {}
