package eu.wohlben.qits.domain.daemon.entity;

/**
 * The supervisor's in-memory state of one daemon instance in a workspace. Not persisted — the
 * durable record of a run is its {@code Command} row; a JVM restart loses live state and the
 * instance reads as STOPPED (its commands are reconciled to INTERRUPTED like any other).
 */
public enum DaemonStatus {
  /** Process launched, readiness not yet observed. */
  STARTING,
  /** The ready pattern matched (or the grace period elapsed) — the daemon is considered up. */
  READY,
  /** The process is alive but an observer reported errors; reset only by restart or stop. */
  DEGRADED,
  /** Exited and a relaunch is scheduled (restart policy, backoff pending). */
  RESTARTING,
  /** Exited (or exhausted its restarts) without being asked to stop. */
  CRASHED,
  /** Not running: never started, stopped explicitly, or exited cleanly with no restart due. */
  STOPPED
}
