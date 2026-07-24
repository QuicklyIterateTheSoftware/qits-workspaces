package eu.wohlben.qits.domain.service.entity;

/** What a daemon event reports. */
public enum ServiceEventKind {
  /** A supervisor state transition (READY, CRASHED, RESTARTING, …). */
  STATUS_CHANGED
}
