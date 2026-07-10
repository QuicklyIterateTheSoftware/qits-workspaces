package eu.wohlben.qits.domain.daemon.dto;

/**
 * The live verdict of one healthcheck. Runtime-only — never persisted (unlike {@code DaemonStatus},
 * which daemon events record), so it lives with the DTOs. UNKNOWN means "no verdict": before the
 * first result, after a stop/restart, or when the probe itself couldn't run — deliberately distinct
 * from UNHEALTHY so a broken probe doesn't masquerade as a down service.
 */
public enum HealthCheckState {
  HEALTHY,
  UNHEALTHY,
  UNKNOWN
}
