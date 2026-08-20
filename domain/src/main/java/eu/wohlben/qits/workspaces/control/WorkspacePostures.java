package eu.wohlben.qits.workspaces.control;

/**
 * Whether a workspace runs in admin mode, by row id — what {@link WorkspaceContainerFactory} asks
 * before it decides whether the container gets the host's docker socket.
 *
 * <p><b>Why the factory looks this up instead of being handed it.</b> Exactly the reason {@link
 * WorkspaceCredentials} gives, and it is worth repeating because the cost of getting it wrong is the
 * same: the orchestrator has no start verb, so a stopped container is started by presenting its spec
 * <em>again</em>, under {@code Recreate.ifChanged}. A posture that arrived as an argument on the
 * provision path and was missing on the start path would make every resume a spec change, and a spec
 * change replaces the container — writable layer and all. So the posture has to be derivable from
 * the workspace at <em>every</em> ensure, which makes the row its carrier and this a lookup rather
 * than a parameter threaded through {@link ContainerRuntime}.
 *
 * <p>An interface, injected as {@code Instance<T>}, because {@link WorkspaceContainerFactory} is
 * built by hand in the unit tests that assert what a container is made of and those tests have no
 * database. <b>Absent means no admin workspace exists</b> — no socket, which is the answer every
 * ordinary workspace gets and the only safe direction for an absence to fall.
 */
@FunctionalInterface
public interface WorkspacePostures {

  /** Whether this workspace's container is the admin kind. False for anything unknown. */
  boolean isAdmin(Long rowId);
}
