package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The test-side {@link RepositoryLookup}: an in-memory registry of repository id → main branch.
 *
 * <p>{@code RepositoryLookup} is a mandatory injection point, so some implementation must exist for
 * the CDI container to start. Standing in for the repositories context with a map is also what lets
 * these tests assert on main-branch behaviour without a repositories database — {@link
 * #setMainBranch} replaces {@code RepositoryService.setMainBranch}.
 */
@ApplicationScoped
public class FakeRepositoryLookup implements RepositoryLookup {

  private final Map<String, String> mainBranches = new ConcurrentHashMap<>();

  @Override
  public Optional<RepositoryView> find(String repoId) {
    String mainBranch = mainBranches.get(repoId);
    return mainBranch == null
        ? Optional.empty()
        : Optional.of(new RepositoryView(repoId, mainBranch));
  }

  /** Make {@code repoId} resolvable, with {@code master} as its main branch. */
  public void register(String repoId) {
    register(repoId, "master");
  }

  /** Make {@code repoId} resolvable with an explicit main branch. */
  public void register(String repoId, String mainBranch) {
    mainBranches.put(repoId, mainBranch);
  }

  /** Repoint an already-registered repository's main branch. */
  public void setMainBranch(String repoId, String mainBranch) {
    mainBranches.put(repoId, mainBranch);
  }

  /** Drop everything — call from {@code @BeforeEach} when a test needs a clean registry. */
  public void clear() {
    mainBranches.clear();
  }
}
