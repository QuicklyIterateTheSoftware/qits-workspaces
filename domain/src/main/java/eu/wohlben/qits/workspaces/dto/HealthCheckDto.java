package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.control.HealthCheckKind;

/** One healthcheck of a service definition, as returned to clients. */
public record HealthCheckDto(
    String name,
    HealthCheckKind kind,
    Integer port,
    String path,
    String expectStatus,
    String command,
    Long intervalMs,
    Long timeoutMs,
    Integer healthyThreshold,
    Integer unhealthyThreshold,
    Long initialDelayMs) {}
