package eu.wohlben.qits.domain.bootstrap.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.project.api.ProjectController;
import eu.wohlben.qits.domain.repository.api.WorkspaceController;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The workspace bootstrap surface: the config-declared chain (from the workspace's {@code
 * .qits-config.yml} — the only chain source since Part 5) listed in execution order with each
 * step's last run in this workspace (null before any run), the async run triggers (chain + single),
 * and the in-flight conflict. The surface reads the chain from the {@link
 * FakeWorkspaceConfigReader} staging; the runs execute for real against the {@code
 * FakeContainerRuntime} host-clone container, driven by the {@code FakeWorkspaceBootstrapDriver}
 * off the checkout's {@code .qits-config.yml}.
 */
@QuarkusTest
@TestProfile(WorkspaceBootstrapControllerTest.TestProfile.class)
public class WorkspaceBootstrapControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-ws-bootstrap-controller-test-repos");
        return Map.of(
            "qits.repositories.data-dir",
            tempDir.toString(),
            // No provision-time chain: the checkout's .qits-config.yml is staged only after
            // provisioning (the self-clone needs an empty directory), and an async autorun chain
            // could otherwise read it mid-staging and pollute lastRun. Manual runs are unaffected.
            "qits.bootstrap.autorun-enabled",
            "false");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 20_000;

  @Inject FakeWorkspaceConfigReader configReader;

  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private final String fixtureUrl;

  public WorkspaceBootstrapControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  @BeforeEach
  void resetStagedConfig() {
    configReader.clear();
  }

  private String repoWithWorkspace() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new ProjectController.CreateProjectRequest(
                    "WS Bootstrap Project", null, null, null))
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

  /** Stage the chain the surface lists (the workspace's in-container ConfigView). */
  private void stageChain(QitsConfig.BootstrapDecl... steps) {
    configReader.setConfig("work", new QitsConfig(null, null, null, List.of(), List.of(steps)));
  }

  /**
   * Stage the chain on both sides of a real run: the checkout's {@code .qits-config.yml} (the
   * {@code FakeWorkspaceBootstrapDriver}'s source — written after provisioning, since the
   * self-clone needs an empty directory) and the config reader (the surface's source).
   */
  private void stageChainInCheckout(String repoId, QitsConfig.BootstrapDecl... steps)
      throws Exception {
    workspaceService.ensureContainer(repoId, "work");
    StringBuilder yaml = new StringBuilder("version: 1\nbootstrap:\n");
    for (QitsConfig.BootstrapDecl step : steps) {
      yaml.append("  - name: ").append(step.name()).append('\n');
      yaml.append("    execute: ").append(step.execute()).append('\n');
    }
    Files.writeString(
        Path.of(dataDir, repoId, "workspaces", "work", ".qits-config.yml"), yaml.toString());
    stageChain(steps);
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
    stageChain(
        new QitsConfig.BootstrapDecl(null, "install", null, "echo install", null, null),
        new QitsConfig.BootstrapDecl("seed-db", "seed", null, "echo seed", null, null));

    given()
        .get(surface(repoId))
        .then()
        .statusCode(200)
        .body("chainRunning", equalTo(false))
        .body("entries", hasSize(2))
        .body("entries[0].step.name", equalTo("install"))
        // the config-declared id surfaces, defaulting to the step name when absent
        .body("entries[0].step.id", equalTo("install"))
        .body("entries[1].step.name", equalTo("seed"))
        .body("entries[1].step.id", equalTo("seed-db"))
        .body("entries[0].lastRun", nullValue())
        .body("entries[1].lastRun", nullValue());
  }

  @Test
  public void runAllExecutesTheChainAndRecordsOutcomes() throws Exception {
    String repoId = repoWithWorkspace();
    stageChainInCheckout(
        repoId, new QitsConfig.BootstrapDecl(null, "install", null, "echo install", null, null));

    given()
        .contentType(ContentType.JSON)
        .post(surface(repoId) + "/run")
        .then()
        .statusCode(200)
        .body("started", equalTo(true));

    assertEquals("SUCCEEDED", awaitOutcome(repoId, 0, "SUCCEEDED"));
    // The step ran in the container (the daemon's chain), not via a host Command row, so there is
    // no linked command audit row — its live output is the bootstrap:<name> process segment.
    given()
        .get(surface(repoId))
        .then()
        .statusCode(200)
        .body("entries[0].lastRun.outcome", equalTo("SUCCEEDED"))
        .body("entries[0].lastRun.commandId", nullValue());
  }

  @Test
  public void runSingleExecutesOnlyThatStep() throws Exception {
    String repoId = repoWithWorkspace();
    stageChainInCheckout(
        repoId,
        new QitsConfig.BootstrapDecl(null, "target", null, "echo target", null, null),
        new QitsConfig.BootstrapDecl(null, "other", null, "echo other", null, null));

    given()
        .contentType(ContentType.JSON)
        .post(surface(repoId) + "/target/run")
        .then()
        .statusCode(200)
        .body("started", equalTo(true));

    assertEquals("SUCCEEDED", awaitOutcome(repoId, 0, "SUCCEEDED"));
    given().get(surface(repoId)).then().statusCode(200).body("entries[1].lastRun", nullValue());
  }

  @Test
  public void concurrentRunsConflict() throws Exception {
    String repoId = repoWithWorkspace();
    stageChainInCheckout(
        repoId, new QitsConfig.BootstrapDecl(null, "slow", null, "sleep 5", null, null));

    given().contentType(ContentType.JSON).post(surface(repoId) + "/run").then().statusCode(200);
    // While the chain runs, a second trigger is rejected and the surface shows it in flight.
    given().contentType(ContentType.JSON).post(surface(repoId) + "/run").then().statusCode(400);
    given().get(surface(repoId)).then().statusCode(200).body("chainRunning", equalTo(true));

    assertEquals("SUCCEEDED", awaitOutcome(repoId, 0, "SUCCEEDED"));
  }
}
