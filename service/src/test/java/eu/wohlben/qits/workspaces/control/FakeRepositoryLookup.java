package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
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

  /**
   * The project every fake repository belongs to. One constant rather than a second map: no test
   * asserts on more than one project, and {@code SCMRelease} only needs the field to be
   * carried rather than to vary.
   */
  public static final String PROJECT_ID = "test-project";

  /**
   * How a fake repository's name is derived from its id. A registered id is opaque here — {@code
   * TestOrigin} mints one — so the name is derived rather than stored, which is enough to prove
   * the release flow carries a name that is NOT the id. That is the whole defect the field exists
   * for: on a real platform a self-seeded repository's id is a UUID and its name is not.
   */
  public static String nameOf(String repoId) {
    return "name-of-" + repoId;
  }

  private final Map<String, String> mainBranches = new ConcurrentHashMap<>();

  @Override
  public Optional<RepositoryView> find(String repoId) {
    String mainBranch = mainBranches.get(repoId);
    return mainBranch == null
        ? Optional.empty()
        : Optional.of(new RepositoryView(repoId, nameOf(repoId), PROJECT_ID, mainBranch));
  }

  @Override
  public List<RepositoryView> listByProject(String projectId) {
    if (!PROJECT_ID.equals(projectId)) {
      return List.of();
    }
    return mainBranches.entrySet().stream()
        .map(
            entry ->
                new RepositoryView(
                    entry.getKey(), nameOf(entry.getKey()), PROJECT_ID, entry.getValue()))
        .toList();
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
