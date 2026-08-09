package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Resolves a repository's <b>project-scoped name</b> — the {@code (projectId, name)} pair that
 * addresses it as a served sibling under the git host's {@code /git/<projectId>/<name>} route.
 *
 * <p>This context needs it for exactly one thing: seeding {@code QITS_WORKSPACE_DAEMON_PROJECT_ID}
 * and {@code …_REPO_NAME} into a new container, so the in-container workspace-daemon can self-clone
 * name-addressed and committed relative submodule urls resolve natively. Registering and persisting
 * those aliases is the repositories context's job, hence the port.
 *
 * <p>Injected as {@code Instance<RepositoryAddressResolver>}. Empty is supported: {@link
 * RepositoryLookup} is the ordinary production source for the same pair, while an implementation
 * here lets an embedding override the git address registry.
 */
public interface RepositoryAddressResolver {

  /** A repository's project-scoped git-host address. */
  record ProjectScopedName(String projectId, String name) {}

  /**
   * The {@code (projectId, name)} for {@code repoId}, or empty when the repository or its project
   * is absent — the caller then asks {@link RepositoryLookup}.
   */
  Optional<ProjectScopedName> resolve(String repoId);
}
