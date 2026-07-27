package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import java.util.List;

/**
 * One of the workspace's config-declared services with its supervised runtime state. {@code
 * commandId} is the current (or most recent) registry command backing the instance — the
 * log/terminal re-attach target — and null if the service never ran in this JVM. {@code proxyPath}
 * is the qits-origin base path the service's app is served under ({@code
 * /workspaces/service/{workspaceId}/{serviceId}/} plus the definition's {@code webView.basePath}
 * when set);
 * its presence is the web-viewable flag (set iff the definition declares a {@code webView}) —
 * combine with a live {@code status} before framing it. The proxy reaches the service's port over
 * the shared Docker network by container name, so a web-viewable service is reachable as soon as it
 * is running — no container recreation, regardless of when the port was configured. {@code health}
 * carries the latest result of each declared healthcheck (runtime-only, all-UNKNOWN until probed).
 */
public record ServiceInstanceDto(
    ServiceDefinitionDto definition,
    ServiceStatus status,
    int restartCount,
    String commandId,
    String proxyPath,
    List<HealthCheckStatusDto> health) {}
