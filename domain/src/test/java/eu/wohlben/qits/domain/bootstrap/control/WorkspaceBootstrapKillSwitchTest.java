package eu.wohlben.qits.domain.bootstrap.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceReadyForDaemonsRecorder;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.bootstrap.autorun-enabled=false} suppresses the provision-time chain — a fresh
 * provision passes straight through to daemon auto-start with no run recorded. Manual runs stay
 * available (not covered here; they don't consult the switch by construction).
 */
@QuarkusTest
@TestProfile(WorkspaceBootstrapKillSwitchTest.KillSwitchProfile.class)
public class WorkspaceBootstrapKillSwitchTest {

  public static class KillSwitchProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Map<String, String> overrides = new HashMap<>();
        overrides.put(
            "qits.repositories.data-dir",
            Files.createTempDirectory("qits-bootstrap-killswitch-test").toString());
        overrides.put("qits.bootstrap.autorun-enabled", "false");
        return overrides;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject BootstrapCommandService bootstrapCommandService;
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceReadyForDaemonsRecorder readyRecorder;

  @Test
  public void killSwitchPassesFreshProvisionStraightThrough() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Bootstrap Kill Switch", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    bootstrapCommandService.create(repo.id, "install", null, "echo hi", null, null, null);
    readyRecorder.clear();

    workspaceService.ensureContainer(repo.id, "work");

    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline && readyRecorder.countFor(repo.id, "work") == 0) {
      Thread.sleep(50);
    }
    assertTrue(
        readyRecorder.countFor(repo.id, "work") >= 1,
        "the switched-off runner still releases daemon auto-start");
    assertTrue(
        bootstrapRunService.listForWorkspace(repo.id, "work").isEmpty(),
        "no bootstrap command ran");
  }
}
