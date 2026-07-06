package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;

/**
 * One of the repository's daemons in a workspace with its supervised runtime state. {@code
 * commandId} is the current (or most recent) registry command backing the instance — the
 * log/terminal re-attach target — and null if the daemon never ran in this JVM. {@code proxyPath}
 * is the qits-origin base path the daemon's app is served under ({@code
 * /daemon/{workspaceId}/{daemonId}/} plus the definition's {@code webView.basePath} when set); its
 * presence is the web-viewable flag (set iff the definition declares a {@code webView}) — combine
 * with a live {@code status} before framing it. {@code needsContainerRecreate} is true when the
 * daemon is web-viewable and running but its workspace container doesn't publish the configured
 * port (publishing is container-create-time only) — the web view stays a 502 until the container is
 * recreated.
 */
public record DaemonInstanceDto(
    RepositoryDaemonDto daemon,
    DaemonStatus status,
    int restartCount,
    String commandId,
    String proxyPath,
    boolean needsContainerRecreate) {}
