package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
 * <p>{@code git worktree add} runs with its working directory set to the bare origin. When {@code
 * qits.repositories.data-dir} is a <em>relative</em> path (as in dev), a relative workspace path
 * would be created nested under origin instead of the repo's workspaces directory — leaving {@code
 * list}/{@code merge}/{@code discard} unable to find it on disk. The other controller tests use an
 * absolute temp dir and so never exercised this case. This test pins the relative-data-dir
 * behaviour.
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

  private final String fixtureUrl;

  public WorkspaceRelativeDataDirTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Rel Workspace Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
  }

  @Test
  public void testFullLifecycleWithRelativeDataDir() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest("rel-01", "master", "rel-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Workspace must be discoverable on disk: its forked branch resolves to "rel-branch".
    // This is the assertion that fails when the path is created nested under origin.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'rel-01' }.workspace.branch",
            equalTo("rel-branch"));

    // merge + discard must also find the workspace on disk
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("master"))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/rel-01/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/rel-01/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }
}
