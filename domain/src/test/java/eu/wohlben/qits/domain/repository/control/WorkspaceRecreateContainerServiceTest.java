package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Recreate-container: the operation that rolls a workspace onto a newer workspace-daemon build by
 * tearing its container down and re-provisioning it from the durable branch
 * (docs/epics/qits-workspace-registry/). Verifies the registry-only clean gate (only a
 * daemon-reported clean tree passes; dirty <em>and</em> unknown are both rejected 400) and the
 * teardown+reprovision mechanic (a fresh container onto the current image; the persistent
 * /workspace volume — and committed work pushed before teardown — carry the checkout across).
 */
@QuarkusTest
public class WorkspaceRecreateContainerServiceTest {

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

    // An untracked marker in the working tree. Recreate re-provisions a FRESH container but keeps
    // the persistent /workspace volume (docs/epics/qits-workspaces/features/
    // 2026-07-25_persistent-workspace-volume.md — "recreation now preserves the working tree"), so
    // the checkout is reattached to the new container rather than re-cloned: the marker survives.
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
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "recreate keeps the persistent /workspace volume, so the untracked file survives the"
            + " teardown+reprovision — the checkout is reattached, not re-cloned");
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
