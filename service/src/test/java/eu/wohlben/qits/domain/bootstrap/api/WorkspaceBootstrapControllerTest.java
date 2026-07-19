package eu.wohlben.qits.domain.bootstrap.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.bootstrap.api.BootstrapCommandController.CreateBootstrapCommandRequest;
import eu.wohlben.qits.domain.project.api.ProjectController;
import eu.wohlben.qits.domain.repository.api.WorkspaceController;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The workspace bootstrap surface: the chain listed in execution order with each command's last run
 * in this workspace (null before any run), the async run triggers (chain + single), and the
 * in-flight conflict. Runs execute for real against the {@code FakeContainerRuntime} host-clone
 * container.
 */
@QuarkusTest
@TestProfile(WorkspaceBootstrapControllerTest.TestProfile.class)
public class WorkspaceBootstrapControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-ws-bootstrap-controller-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 20_000;

  private final String fixtureUrl;

  public WorkspaceBootstrapControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String repoWithWorkspace() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRequest("WS Bootstrap Project", null))
            .post("/api/projects")
            .then()
            .statusCode(200)
            .extract()
            .path("project.id");
    String repoId =
        given()
            .contentType(ContentType.JSON)
            .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null, null))
            .post("/api/projects/" + projectId + "/repositories")
            .then()
            .statusCode(200)
            .extract()
            .path("repository.id");
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("work", "master", "work", null))
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(200);
    return repoId;
  }

  private String createCommand(String repoId, String name, String execute) {
    return given()
        .contentType(ContentType.JSON)
        .body(new CreateBootstrapCommandRequest(name, null, execute, null, null, null))
        .post("/api/repositories/" + repoId + "/bootstrap-commands")
        .then()
        .statusCode(200)
        .extract()
        .path("command.id");
  }

  private String surface(String repoId) {
    return "/api/repositories/" + repoId + "/workspaces/work/bootstrap-commands";
  }

  private String awaitOutcome(String repoId, int entryIndex, String expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    String last = null;
    while (System.currentTimeMillis() < deadline) {
      last =
          given()
              .get(surface(repoId))
              .then()
              .statusCode(200)
              .extract()
              .path("entries[" + entryIndex + "].lastRun.outcome");
      if (expected.equals(last)) {
        return last;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last: " + last);
  }

  @Test
  public void listShowsChainWithNullLastRunBeforeAnyRun() {
    String repoId = repoWithWorkspace();
    createCommand(repoId, "install", "echo install");
    createCommand(repoId, "seed", "echo seed");

    given()
        .get(surface(repoId))
        .then()
        .statusCode(200)
        .body("chainRunning", equalTo(false))
        .body("entries", hasSize(2))
        .body("entries[0].command.name", equalTo("install"))
        .body("entries[1].command.name", equalTo("seed"))
        .body("entries[0].lastRun", nullValue())
        .body("entries[1].lastRun", nullValue());
  }

  @Test
  public void runAllExecutesTheChainAndRecordsOutcomes() throws Exception {
    String repoId = repoWithWorkspace();
    createCommand(repoId, "install", "echo install");

    given()
        .contentType(ContentType.JSON)
        .post(surface(repoId) + "/run")
        .then()
        .statusCode(200)
        .body("started", equalTo(true));

    assertEquals("SUCCEEDED", awaitOutcome(repoId, 0, "SUCCEEDED"));
    String commandId =
        given()
            .get(surface(repoId))
            .then()
            .statusCode(200)
            .extract()
            .path("entries[0].lastRun.commandId");
    // The execute left an ordinary command audit row with its log.
    given()
        .get("/api/commands/" + commandId)
        .then()
        .statusCode(200)
        .body("command.actionName", equalTo("install"));
  }

  @Test
  public void runSingleExecutesOnlyThatCommand() throws Exception {
    String repoId = repoWithWorkspace();
    String targetId = createCommand(repoId, "target", "echo target");
    createCommand(repoId, "other", "echo other");

    given()
        .contentType(ContentType.JSON)
        .post(surface(repoId) + "/" + targetId + "/run")
        .then()
        .statusCode(200)
        .body("started", equalTo(true));

    assertEquals("SUCCEEDED", awaitOutcome(repoId, 0, "SUCCEEDED"));
    given().get(surface(repoId)).then().statusCode(200).body("entries[1].lastRun", nullValue());
  }

  @Test
  public void concurrentRunsConflict() throws Exception {
    String repoId = repoWithWorkspace();
    createCommand(repoId, "slow", "sleep 5");

    given().contentType(ContentType.JSON).post(surface(repoId) + "/run").then().statusCode(200);
    // While the chain runs, a second trigger is rejected and the surface shows it in flight.
    given().contentType(ContentType.JSON).post(surface(repoId) + "/run").then().statusCode(400);
    given().get(surface(repoId)).then().statusCode(200).body("chainRunning", equalTo(true));
  }
}
