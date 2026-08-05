package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The invariant {@code createWorkspace} states — "Each workspace gets its own branch so two
 * workspaces never commit to the same branch" — held against the path that used to violate it.
 *
 * <p>The defect these tests pin down: the only uniqueness check was {@code
 * existsActiveByRepositoryAndWorkspaceId}, which guards the workspace <em>id</em>. The id is not the
 * branch — {@code branch} is a separate parameter that merely defaults to it — so two distinct,
 * legitimately unique ids could name one branch and nothing looked. The branch was protected only
 * incidentally, by {@code git branch} failing on an existing ref, and {@code adoptExisting} skips
 * exactly that step. It is a request-body field ({@code WorkspaceController.create} passes {@code
 * request.adoptExisting()}), so this was reachable over the public API, not an internal-only path.
 */
@QuarkusTest
public class TwoActiveWorkspacesOneBranchTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * The original reproduction: distinct ids, one branch, the second adopting so no ref creation
   * stands in the way. The id guard is satisfied throughout — {@code alpha != beta} — which is the
   * point: it answers the right question about the wrong field.
   */
  @Test
  public void refusesASecondActiveWorkspaceOnOneBranch() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);

    // First workspace creates the branch ref, the ordinary path.
    Workspace alpha =
        workspaceService.createWorkspace(repoId, "alpha", null, "feature-x", null, false);
    assertEquals("feature-x", alpha.branch);
    assertEquals(WorkspaceStatus.ACTIVE, alpha.status);

    // Second workspace: a DIFFERENT id, so the id-uniqueness guard is satisfied and passes — and
    // adoptExisting skips the ref creation that would otherwise have failed. Nothing else used to
    // look at the branch.
    ConflictException rejected =
        assertThrows(
            ConflictException.class,
            () -> workspaceService.createWorkspace(repoId, "beta", null, "feature-x", null, true),
            "a second ACTIVE workspace was allowed to claim a branch that already has one");
    assertEquals(409, rejected.statusCode());
    assertTrue(
        rejected.getMessage().contains("feature-x"),
        "the conflict should name the contested branch, was: " + rejected.getMessage());

    assertEquals(
        1,
        activeOn(repoId, "feature-x").size(),
        "exactly one ACTIVE workspace may own a branch: each gets its own container cloning it, and"
            + " the daemon auto-pushes per commit, so two would push to the same ref");
  }

  /** The id is still free to differ from the branch — it is the branch that is exclusive. */
  @Test
  public void allowsDistinctBranchesUnderDistinctIds() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);

    Workspace alpha =
        workspaceService.createWorkspace(repoId, "alpha", null, "feature-a", null, false);
    Workspace beta =
        workspaceService.createWorkspace(repoId, "beta", null, "feature-b", null, false);

    assertNotEquals(alpha.workspaceId, beta.workspaceId);
    assertEquals(1, activeOn(repoId, "feature-a").size());
    assertEquals(1, activeOn(repoId, "feature-b").size());
  }

  /**
   * Resolved rows accumulate — that is why V10 dropped V1's unique constraint — so resolving a
   * workspace must hand its branch back rather than block it forever.
   */
  @Test
  public void freesTheBranchOnceTheWorkspaceResolves() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);

    Workspace first =
        workspaceService.createWorkspace(repoId, "alpha", null, "feature-x", null, false);
    resolve(first.id);

    // The branch ref still exists in the origin, so the retake has to adopt it.
    Workspace second =
        workspaceService.createWorkspace(repoId, "beta", null, "feature-x", null, true);

    assertEquals("feature-x", second.branch);
    assertEquals(1, activeOn(repoId, "feature-x").size());
  }

  /** The branch is scoped per repository: the same name in another repository is untouched. */
  @Test
  public void scopesTheRuleToOneRepository() throws Exception {
    String repoOne = TestOrigin.create(dataDir);
    String repoTwo = TestOrigin.create(dataDir);
    repositories.register(repoOne);
    repositories.register(repoTwo);

    workspaceService.createWorkspace(repoOne, "alpha", null, "feature-x", null, false);
    workspaceService.createWorkspace(repoTwo, "alpha", null, "feature-x", null, false);

    assertEquals(1, activeOn(repoOne, "feature-x").size());
    assertEquals(1, activeOn(repoTwo, "feature-x").size());
  }

  /**
   * The ordinary "branch off" path onto an existing ref is a 409, not the 500 it used to raise from
   * {@code createBranchRefOnOrigin} — a caller who typo'd a name gets an answer it can act on.
   */
  @Test
  public void reportsAnExistingRefAsAConflict() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);

    workspaceService.createWorkspace(repoId, "alpha", null, "feature-x", null, false);
    resolve(activeOn(repoId, "feature-x").get(0).id);

    // The workspace is gone but its branch ref is not, so a non-adopting create still collides.
    ConflictException rejected =
        assertThrows(
            ConflictException.class,
            () -> workspaceService.createWorkspace(repoId, "beta", null, "feature-x", null, false));
    assertEquals(409, rejected.statusCode());
  }

  private List<Workspace> activeOn(String repoId, String branch) {
    return workspaceRepository.findActiveByRepositoryId(repoId).stream()
        .filter(w -> branch.equals(w.branch))
        .toList();
  }

  /** Soft-delete, the way discard/integrate leave a row: ACTIVE is the only status that claims. */
  @Transactional
  void resolve(Long workspaceRowId) {
    Workspace workspace = workspaceRepository.findById(workspaceRowId);
    workspace.status = WorkspaceStatus.INTEGRATED;
    workspaceRepository.persist(workspace);
  }
}
