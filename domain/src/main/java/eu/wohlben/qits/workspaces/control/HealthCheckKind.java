package eu.wohlben.qits.workspaces.control;

/**
 * How a service health check probes the service, ordered by dependency weight.
 *
 * <p>Part of the {@link QitsConfig} record tree — see {@link RestartPolicy} for why it lives here.
 */
public enum HealthCheckKind {
  /** {@code curl} against the container's loopback; the HTTP status is matched. */
  HTTP,
  /** A bare connect via bash's {@code /dev/tcp} builtin — works with zero extra tooling. */
  TCP,
  /** An arbitrary in-container script; exit 0 = healthy. The escape hatch. */
  COMMAND
}
