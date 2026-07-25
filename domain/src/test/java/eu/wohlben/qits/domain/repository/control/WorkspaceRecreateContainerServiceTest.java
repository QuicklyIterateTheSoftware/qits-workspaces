package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Recreate-container: the operation that rolls a workspace onto a newer workspace-daemon build by
 * tearing its container down and re-provisioning it from the durable branch
 * (docs/epics/qits-workspace-registry/). Verifies the registry-only clean gate (only a
 * daemon-reported clean tree passes; dirty <em>and</em> unknown are both rejected 400) and the
 * teardown+reprovision mechanic (a fresh clone, committed work preserved via a pre-push).
 */
@QuarkusTest
@TestProfile(WorkspaceRecreateContainerServiceTest.TestProfile.class)
public class WorkspaceRecreateContainerServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-recreate-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject GitExecutor git;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject FakeWorkspaceGitStatus gitStatus;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Recreate Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  private WorkspaceDto workspaceDto(String repoId, String workspaceId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> workspaceId.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * ensureContainer, then wait for its async started event to land so a later await isn't fooled.
   */
  private void ensureRunning(String repoId, String workspaceId) throws InterruptedException {
    workspaceService.ensureContainer(repoId, workspaceId);
    assertTrue(startedRecorder.awaitCount(repoId, workspaceId, 1, 5_000));
    startedRecorder.clear();
  }

  @Test
  public void recreateTearsDownAndReprovisionsAFreshContainerWhenClean() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);

    // An untracked marker only a re-clone would drop — proves rm+reprovision, not restart-in-place.
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode());

    // Daemon reports the tree clean → the gate passes and recreate runs.
    gitStatus.report("feat", true);
    workspaceService.beginRecreateContainer(repoId, "feat");
    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "recreate fires a fresh-provision started event");

    assertTrue(containers.exists(container), "a fresh container is running after recreate");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertNotEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "the fresh clone dropped the untracked file — the old container was destroyed, not restarted");
  }

  @Test
  public void recreatePreservesCommittedWorkByPushingBeforeTeardown() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);

    // A committed-but-unpushed commit: recreate must push it before rm so the re-clone still has
    // it.
    containers.exec(
        container,
        "/workspace",
        Map.of(),
        "bash",
        "-lc",
        "echo hi > kept.txt && git add kept.txt && git commit -m local");
    String head =
        containers
            .exec(container, "/workspace", Map.of(), "git", "rev-parse", "HEAD")
            .output()
            .trim();

    gitStatus.report("feat", true);
    workspaceService.beginRecreateContainer(repoId, "feat");
    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000));

    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        head,
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        "recreate pushed the commit to origin before tearing the container down");
    assertEquals(
        head,
        containers
            .exec(container, "/workspace", Map.of(), "git", "rev-parse", "HEAD")
            .output()
            .trim(),
        "the recreated container has the pushed commit");
  }

  @Test
  public void recreateRefusesADirtyWorkspace() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");

    gitStatus.report("feat", false);
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.beginRecreateContainer(repoId, "feat"));
    assertTrue(ex.getMessage().contains("dirty"), ex.getMessage());

    // The rejected recreate never touched the container — the marker (and the container) survive.
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
  }

  @Test
  public void recreateRefusesAnUnknownWorkingTree() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");

    // No daemon has reported cleanliness → UNKNOWN, which recreate must reject just like dirty:
    // an unknowable tree is not a safe basis to destroy a container.
    gitStatus.forget("feat");
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.beginRecreateContainer(repoId, "feat"));
    assertTrue(ex.getMessage().contains("unknown"), ex.getMessage());
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
  }

  @Test
  public void recreateThrows404ForAnUnknownWorkspace() throws Exception {
    String repoId = clonedRepo();
    assertThrows(
        NotFoundException.class,
        () -> workspaceService.beginRecreateContainer(repoId, "no-such-workspace"));
  }
}
