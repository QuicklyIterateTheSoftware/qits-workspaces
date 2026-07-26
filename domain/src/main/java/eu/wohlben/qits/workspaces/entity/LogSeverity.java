package eu.wohlben.qits.workspaces.entity;

/**
 * Per-line severity stamped on captured log lines at persist time (SERVICE commands' OUTPUT lines,
 * classified locally). Null on unclassified lines — routine output deliberately carries no
 * severity.
 *
 * <p>The command context owns the audit log this is stamped onto and declares the same three
 * values; this context declares them too because it supplies the classifier ({@link
 * eu.wohlben.qits.workspaces.control.LogLevelLineClassifier}) and must compile without that
 * context. The names are the contract — an application assembling both maps by {@code name()}.
 */
public enum LogSeverity {
  INFO,
  WARNING,
  ERROR
}
