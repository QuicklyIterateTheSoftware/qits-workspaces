package eu.wohlben.qits.workspaces.control;

/**
 * What a workspace <em>is</em>, by row id — the postures {@link WorkspaceContainerFactory} asks
 * about before it decides what the container is made of. Two questions today: whether it runs in
 * admin mode (the host's docker socket), and whether it is the project wrapper's main workspace
 * (the richer editor image and the editor's environment).
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

  /**
   * Whether this workspace is the <b>project wrapper's main workspace</b> — the per-project
   * singleton {@code WorkspaceService.createMainWorkspace} maintains, and the one workspace the web
   * editor runs in.
   *
   * <p><b>Derived, never stored.</b> There is no column and no migration behind this: a workspace is
   * the wrapper's main one when its repository's archetype is {@code PROJECT} and its branch is that
   * repository's main branch — two facts the registry already answers and one the row already
   * carries. A column would be a fourth copy of an answer three places already hold, and it would go
   * stale the day a repository's main branch is renamed.
   *
   * <p><b>It obeys the same reproducibility rule as {@link #isAdmin}</b>, and more sharply, because
   * it changes more of the spec: the image AND two environment variables. A spec that differs from
   * what is running is a {@code Recreate.ifChanged} <em>replacement</em>, so this answer has to be
   * the same at every ensure — see {@code PersistedWorkspacePostures} for what makes it so even
   * while the registry is unreachable.
   *
   * <p>A {@code default} rather than a second abstract method, and the reason is mechanical as well
   * as semantic: this interface is written as a lambda by every hand-built test factory, so a second
   * abstract method would break all of them at once. <b>False is the right default</b> — a port that
   * does not answer is a plain workspace, which is what every workspace was before an editor
   * existed.
   */
  default boolean isWrapperMain(Long rowId) {
    return false;
  }
}
