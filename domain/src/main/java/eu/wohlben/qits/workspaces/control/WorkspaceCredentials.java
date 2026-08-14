package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The credential a workspace currently holds, by row id — what {@link WorkspaceContainerFactory}
 * reads when it composes a container's environment.
 *
 * <p><b>Why the factory looks this up instead of being handed it.</b> The orchestrator has no start
 * verb: a stopped container is started by presenting its spec <em>again</em> ({@code
 * ContainerRuntime#start}), and the ensure carries {@code Recreate.ifChanged}, so a spec whose
 * environment differs from the running container's is a spec change and the container is
 * <b>replaced</b> — its writable layer with it. A credential that arrived as an argument on the
 * provision path and was missing on the start path would therefore destroy a container on every
 * resume. So the credential has to be derivable from the workspace at <em>every</em> ensure, which
 * makes the row its carrier and this a lookup rather than a parameter. A present-but-stopped
 * container that is merely started keeps the credential it was launched with, byte for byte.
 *
 * <p>An interface, and injected as {@code Instance<T>}, for the same reason {@link
 * RepositoryAddressResolver} is: {@link WorkspaceContainerFactory} is built by hand in the unit
 * tests that assert what a container is made of, and those tests have no database. <b>Absent means
 * no environment is injected</b> — today's behaviour, and the same answer a workspace with no
 * credential gets.
 */
@FunctionalInterface
public interface WorkspaceCredentials {

  /** The credential commissioned for this workspace's current container, or empty. */
  Optional<WorkspaceCredential> forWorkspace(Long rowId);
}
