package eu.wohlben.qits.domain.bootstrap.entity;

/** How a bootstrap command's most recent run in a workspace ended. */
public enum BootstrapOutcome {
  /** The check script exited non-zero — the command wasn't needed and never ran. */
  SKIPPED,
  /** The execute script exited 0. */
  SUCCEEDED,
  /** The execute script exited non-zero (or timed out) — the chain aborted here. */
  FAILED
}
