package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import java.util.List;

/**
 * One of the repository's daemons in a workspace with its supervised runtime state. {@code
 * commandId} is the current (or most recent) registry command backing the instance — the
 * log/terminal re-attach target — and null if the daemon never ran in this JVM. {@code proxyPath}
 * is the qits-origin base path the daemon's app is served under ({@code
 * /daemon/{workspaceId}/{daemonId}/} plus the definition's {@code webView.basePath} when set); its
 * presence is the web-viewable flag (set iff the definition declares a {@code webView}) — combine
 * with a live {@code status} before framing it. The proxy reaches the daemon's port over the shared
 * Docker network by container name, so a web-viewable daemon is reachable as soon as it is running
 * — no container recreation, regardless of when the port was configured. {@code health} carries the
 * latest result of each declared healthcheck (runtime-only, all-UNKNOWN until probed).
 */
public record DaemonInstanceDto(
    RepositoryDaemonDto daemon,
    DaemonStatus status,
    int restartCount,
    String commandId,
    String proxyPath,
    List<HealthCheckStatusDto> health) {}
