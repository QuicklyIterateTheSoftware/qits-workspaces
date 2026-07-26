package eu.wohlben.qits.domain.service.entity;

/**
 * The supervisor's in-memory state of one service instance in a workspace. Not persisted — the
 * durable record of a run is its {@code Command} row; a JVM restart loses live state and the
 * instance reads as STOPPED (its commands are reconciled to INTERRUPTED like any other).
 */
public enum ServiceStatus {
  /** Process launched, readiness not yet observed. */
  STARTING,
  /** The ready pattern matched (or the grace period elapsed) — the service is considered up. */
  READY,
  /** Exited and a relaunch is scheduled (restart policy, backoff pending). */
  RESTARTING,
  /** Exited (or exhausted its restarts) without being asked to stop. */
  CRASHED,
  /** Not running: never started, stopped explicitly, or exited cleanly with no restart due. */
  STOPPED
}
