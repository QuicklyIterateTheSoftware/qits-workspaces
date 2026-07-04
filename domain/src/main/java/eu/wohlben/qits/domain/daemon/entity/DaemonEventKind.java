package eu.wohlben.qits.domain.daemon.entity;

/** What a daemon event reports. */
public enum DaemonEventKind {
  /** A supervisor state transition (READY, CRASHED, RESTARTING, …). */
  STATUS_CHANGED,
  /** A log observer classified output as an error. */
  ERROR_DETECTED
}
