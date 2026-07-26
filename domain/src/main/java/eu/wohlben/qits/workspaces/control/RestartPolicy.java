package eu.wohlben.qits.workspaces.control;

/**
 * What the in-container supervisor does when a service's process exits on its own.
 *
 * <p>Part of the {@link QitsConfig} record tree, which is why it lives here rather than with the
 * services context that acts on it: this jar must be able to deserialize a whole {@code ConfigView}
 * off the daemon socket without depending on anything else. The constant <em>names</em> are the wire
 * contract — the daemon normalizes the config file's kebab-case values to these before sending.
 */
public enum RestartPolicy {
  /** Never relaunch; a non-zero exit settles the instance in CRASHED. */
  NEVER,
  /** Relaunch only on a non-zero exit (up to {@code maxRestarts}); a clean exit stops it. */
  ON_FAILURE,
  /** Relaunch on any exit, clean or not (up to {@code maxRestarts}). */
  ALWAYS
}
