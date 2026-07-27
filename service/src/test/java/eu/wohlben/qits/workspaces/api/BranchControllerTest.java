package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.GitExecutor;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The branch-level integrate and cleanup routes, ported from the monorepo's {@code
 * RepositoryControllerTest} along with the endpoints themselves.
 *
 * <p>One sibling test did not come with them: {@code testBranchesReportCanCleanup} asserted on the
 * {@code canCleanup} flag of {@code GET /branches}, which is a repositories-context route. It stays
 * with that route in {@code qits-projects}, which computes the flag through its {@code
 * WorkspaceLookup} SPI.
 */
@QuarkusTest
public class BranchControllerTest {

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject GitExecutor git;

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture; those routes are not part of this jar, so the same state is set up directly — as
   * {@link WorkspaceControllerTest} does. The origin has {@code master} with two commits and a
   * diverged {@code feature}.
   */
  private String createProjectAndRepository() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      workspaceService.createMainWorkspace(repoId, "master");
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  private void createWorkspace(String repoId, String id, String parent, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(id, parent, branch, null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  /** The branch names present in the repository's bare origin. */
  private String originBranches(String repoId) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return git.exec(
        originPath.toFile(), "git", "for-each-ref", "--format=%(refname:short)", "refs/heads");
  }

  @Test
  public void testIntegrateBranchDefaultsToMainBranch() {
    String repoId = createProjectAndRepository();

    // No target given → integrate "feature" into the repo's configured main branch (master).
    // feature only adds feature.txt relative to the merge base, so this is a clean merge.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", null, null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitHash", not(emptyOrNullString()))
        .body("hasConflicts", equalTo(false));
  }

  @Test
  public void testIntegrateBranchIntoExplicitTarget() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitHash", not(emptyOrNullString()));
  }

  @Test
  public void testIntegrateRejectsBranchIntoItself() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("master", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRejectsFlagLikeSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("-D", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRequiresSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateUnknownRepoReturns404() {
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/does-not-exist/branches/merge")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testIntegrateAutoCleansUpEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "auto-wt", "master", "auto-b");

    // Integrating a clean, dependent-free workspace into its parent removes it afterwards.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("auto-b", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false))
        .body("cleanedUp", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("auto-wt")));
  }

  @Test
  public void testIntegrateKeepsWorkspaceWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "pwt", "master", "pb");
    createWorkspace(repoId, "cwt", "pb", "cb");

    // pb still has a dependent workspace (cwt), so it must not be cleaned up after integration.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("pb", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("cleanedUp", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem("pwt"));
  }

  @Test
  public void testIntegratePlainBranchAutoCleansUp() throws Exception {
    String repoId = createProjectAndRepository();

    // "feature" is a plain branch (no workspace). Integrating it into master leaves it fully merged
    // with no dependents, so it is deleted afterwards — the same behaviour as a workspace branch.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false))
        .body("cleanedUp", equalTo(true));

    // The monorepo asserted this through GET /branches, which is a repositories-context route and
    // not in this jar; the origin's ref namespace is the same fact one layer down.
    org.junit.jupiter.api.Assertions.assertFalse(
        originBranches(repoId).lines().anyMatch(b -> b.equals("feature")),
        "feature should be deleted from the origin after a clean integration");
  }

  @Test
  public void testCleanupBranchRemovesEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "elig-wt", "master", "elig-b");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("elig-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("elig-wt")));
  }

  @Test
  public void testCleanupBranchRejectsUnmergedCommits() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "ahead-wt", "master", "ahead-b");
    // Advance ahead-b past master by integrating the diverged feature branch into it.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "ahead-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("ahead-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsBranchWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "par-wt", "master", "par-b");
    createWorkspace(repoId, "chi-wt", "par-b", "chi-b");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("par-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsMainBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("master", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRequiresBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("", null))
        .when()
        .post("/api/repositories/" + repoId + "/branches/cleanup")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
