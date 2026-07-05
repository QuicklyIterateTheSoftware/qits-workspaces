package eu.wohlben.qits.domain.repository.entity;

/**
 * The runtime state of a workspace's container, distinct from the lifecycle {@link WorkspaceStatus}
 * (which records whether the <em>unit of work</em> is ACTIVE or resolved). A workspace's real work
 * lives in its durable branch; the container is a recreatable cache of it, so the container can be
 * absent for mundane reasons without the workspace being dead. This status reflects that cache.
 *
 * <p>{@link #RUNNING} is computed live from the actual container listing; {@link #STOPPED}, {@link
 * #PROVISIONING} and {@link #FAILED} are persisted so the UI can offer a recreate action and, on
 * failure, tell the user <em>why</em> (see {@code Workspace.runtimeError}).
 */
public enum WorkspaceRuntimeStatus {
  /** The container exists and can be executed against. */
  RUNNING,
  /** No container right now, but the branch survives; re-provision lazily on next use. */
  STOPPED,
  /** A re-provision (docker run + clone) is in flight. */
  PROVISIONING,
  /** The last re-provision attempt failed; {@code Workspace.runtimeError} holds the reason. */
  FAILED
}
