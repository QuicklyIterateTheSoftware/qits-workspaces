package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.workspaces.control.TestOrigin;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the workspace path bug.
 *
 * <p>Host git used to run with its working directory set to the bare origin, so when {@code
 * qits.repositories.data-dir} was a <em>relative</em> path (as in dev) a relative workspace path was
 * created nested under origin instead of the repo's workspaces directory — leaving {@code
 * list}/{@code merge}/{@code discard} unable to find it on disk. The mirror resolves its root to an
 * absolute path once, at construction, which is what makes the bug unreachable rather than fixed;
 * this test keeps a relative data dir exercised so the property stays asserted. The other
 * controller tests use an absolute temp dir and so never covered it.
 */
@QuarkusTest
@TestProfile(WorkspaceRelativeDataDirTest.TestProfile.class)
public class WorkspaceRelativeDataDirTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Deliberately relative (resolves under the module's target/ build dir).
      return Map.of("qits.repositories.data-dir", "target/qits-rel-workspace-test");
    }
  }

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture. Those routes belong to the projects and repositories contexts and are not part of this
   * jar, so the same state is set up directly instead — the endpoints under test here are the
   * workspace ones below, not the seeding ones.
   */
  @jakarta.inject.Inject eu.wohlben.qits.workspaces.control.WorkspaceIds workspaceIds;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.FakeRepositoryLookup repositories;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String createProjectAndRepository() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  @Test
  public void testFullLifecycleWithRelativeDataDir() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, "rel-01", "master", "rel-branch", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Workspace must be discoverable on disk: its forked branch resolves to "rel-branch".
    // This is the assertion that fails when the path is created nested under origin.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'rel-01' }.workspace.branch",
            equalTo("rel-branch"));

    // merge + discard must also find the workspace on disk. The target is "feature" rather than
    // "master": master is this repository's default branch, and the merge endpoint refuses that
    // one now (integrate is the only door). What is under test here is the on-disk path, which is
    // the same either way.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("feature"))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "rel-01") + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "rel-01") + "/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }
}
