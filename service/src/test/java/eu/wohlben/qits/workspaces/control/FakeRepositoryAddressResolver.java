package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The test-side {@link RepositoryAddressResolver}: an in-memory repository id → {@code (projectId,
 * name)} registry, standing in for the repositories context's alias table.
 *
 * <p>Unregistered ids resolve to empty, which is the production fallback too — the daemon then
 * id-addresses {@code /git/<repositoryId>}. So a test that does not care about name-addressing can
 * simply not register anything.
 */
@ApplicationScoped
public class FakeRepositoryAddressResolver implements RepositoryAddressResolver {

  private final Map<String, ProjectScopedName> names = new ConcurrentHashMap<>();

  @Override
  public Optional<ProjectScopedName> resolve(String repoId) {
    return Optional.ofNullable(names.get(repoId));
  }

  /** Give {@code repoId} a project-scoped address. */
  public void register(String repoId, String projectId, String name) {
    names.put(repoId, new ProjectScopedName(projectId, name));
  }

  public void clear() {
    names.clear();
  }
}
