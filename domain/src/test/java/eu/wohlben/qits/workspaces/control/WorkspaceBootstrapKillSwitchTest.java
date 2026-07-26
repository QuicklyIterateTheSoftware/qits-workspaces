package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.bootstrap.autorun-enabled=false} suppresses the provision-time chain — a fresh
 * provision passes straight through to service auto-start with no run recorded, even when the
 * checkout declares a chain. Manual runs stay available (not covered here; they don't consult the
 * switch by construction).
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
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceReadyForServicesRecorder readyRecorder;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Test
  public void killSwitchPassesFreshProvisionStraightThrough() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Bootstrap Kill Switch", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    // A declared chain exists (committed before the workspace fork, so the checkout carries it) —
    // the kill switch, not its absence, is what suppresses the run.
    Path origin = Path.of(dataDir, repo.id, "origin");
    Path worktree = Files.createTempDirectory("qits-config-commit");
    git.exec(null, "git", "clone", origin.toString(), worktree.toString());
    git.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    git.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(
        worktree.resolve(".qits-config.yml"),
        "version: 1\nbootstrap:\n  - name: 'install'\n    execute: 'echo hi'\n");
    git.exec(worktree.toFile(), "git", "add", ".qits-config.yml");
    git.exec(worktree.toFile(), "git", "commit", "-m", "stage qits config");
    git.exec(worktree.toFile(), "git", "push", "origin", "HEAD:master");
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    readyRecorder.clear();

    workspaceService.ensureContainer(repo.id, "work");

    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline && readyRecorder.countFor(repo.id, "work") == 0) {
      Thread.sleep(50);
    }
    assertTrue(
        readyRecorder.countFor(repo.id, "work") >= 1,
        "the switched-off runner still releases service auto-start");
    assertTrue(
        bootstrapRunService.listForWorkspace(repo.id, "work").isEmpty(),
        "no bootstrap command ran");
  }
}
