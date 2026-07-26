package eu.wohlben.qits.domain.service.dto;

import eu.wohlben.qits.domain.service.entity.HealthCheckKind;

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
