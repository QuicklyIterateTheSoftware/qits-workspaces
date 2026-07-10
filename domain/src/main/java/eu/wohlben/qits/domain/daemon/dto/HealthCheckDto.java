package eu.wohlben.qits.domain.daemon.dto;

import eu.wohlben.qits.domain.daemon.entity.HealthCheckKind;

/** One healthcheck of a daemon definition, as returned to clients. */
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
