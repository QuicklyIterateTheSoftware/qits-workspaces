package eu.wohlben.qits.domain.service.dto;

import eu.wohlben.qits.domain.service.entity.HealthCheckKind;
import java.time.Instant;

/**
 * The latest result of one healthcheck — a live gauge read straight from the supervisor's in-memory
 * cache, never persisted. {@code detail} carries the failing evidence (HTTP code, exit code +
 * output excerpt); latency/timestamp/detail are null until the check first runs in this JVM.
 */
public record HealthCheckStatusDto(
    String name,
    HealthCheckKind kind,
    HealthCheckState state,
    Long lastLatencyMs,
    Instant lastCheckedAt,
    String detail) {}
