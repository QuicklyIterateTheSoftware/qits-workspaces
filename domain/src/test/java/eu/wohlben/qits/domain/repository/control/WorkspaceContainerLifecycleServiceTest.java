package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
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
 * Verifies the disposable-container lifecycle against a real cloned-fixture repo (Fake runtime): a
 * lost container is re-provisioned from the durable branch, its runtime status is surfaced, and a
 * workspace is abandoned only when the branch itself is gone.
 */
@QuarkusTest
@TestProfile(WorkspaceContainerLifecycleServiceTest.TestProfile.class)
public class WorkspaceContainerLifecycleServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-lifecycle-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject RepositoryDiscoveryService discoveryService;
  @Inject ContainerRuntime containers;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Lifecycle Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  private WorkspaceDto workspaceDto(String repoId, String workspaceId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> workspaceId.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  public void ensureContainerRecreatesALostContainerFromTheBranch() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);
    assertTrue(containers.exists(container));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());

    // Container vanishes out-of-band; the branch — the real work — is untouched in origin.
    containers.rm(container);
    assertFalse(containers.exists(container));
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());

    // ensureContainer re-provisions from the durable branch and the workspace stays ACTIVE.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.exists(container), "container is re-provisioned");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, dto.runtimeStatus());
  }

  @Test
  public void ensureContainerIsANoOpWhenAlreadyRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    // Should not throw and should leave the (same) container running.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.exists(containers.containerName("feat", repoId)));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
  }

  @Test
  public void ensureContainerRestartsAnExitedContainerInPlaceKeepingUnpushedWork()
      throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "unpushed.txt");

    // A host/docker restart leaves the container present but Exited (its /workspace clone intact) —
    // the exact state DockerExecutor.exists() couldn't distinguish from "running".
    ((FakeContainerRuntime) containers).markExited(container);
    assertFalse(containers.isRunning(container));
    assertTrue(containers.exists(container), "the exited container is still present");

    // ensureContainer must start it back up in place — NOT no-op on mere presence, NOT re-clone —
    // so the unpushed commit survives and the runtime status reflects the live container.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.isRunning(container), "the exited container is started back up");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(head, containerHead(container), "restart-in-place keeps the unpushed commit");
  }

  @Test
  public void ensureContainerAbandonsWhenTheBranchIsGone() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);

    // Both the container AND the durable branch disappear: the work no longer exists anywhere.
    containers.rm(container);
    Path originPath = Path.of(dataDir, repoId, "origin");
    git.exec(originPath.toFile(), "git", "branch", "-D", "--", "feat");

    assertThrows(NotFoundException.class, () -> workspaceService.ensureContainer(repoId, "feat"));

    // The workspace is abandoned (the only path to abandonment) and drops off the active list.
    assertFalse(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "a workspace with no branch to recreate from is abandoned");
  }

  @Test
  public void reconcileMarksAContainerlessButLiveBranchStoppedNotAbandoned() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    containers.rm(containers.containerName("feat", repoId));

    // Reconciliation keys off the branch, not the container: the branch survives, so the workspace
    // stays ACTIVE (STOPPED runtime), not ABANDONED.
    discoveryService.discover();

    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status());
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());
  }

  @Test
  public void stopContainerRemovesTheContainerButKeepsTheWorkspaceActive() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);

    workspaceService.stopContainer(repoId, "feat");

    assertFalse(containers.exists(container), "graceful stop removes the container");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status(), "the workspace stays active");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());

    // ...and it can be brought straight back.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.exists(container));
  }

  @Test
  public void gracefulStopPushesUnpushedWorkSoRecreationIsLossless() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "graceful.txt");

    // A graceful stop pushes before removing the container...
    workspaceService.stopContainer(repoId, "feat");
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        head,
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        "graceful stop pushed the commit to origin");

    // ...so the recreated container still has it.
    workspaceService.ensureContainer(repoId, "feat");
    assertEquals(head, containerHead(container), "recreated container has the pushed commit");
  }

  @Test
  public void unpushedWorkDiesWithAnUnexpectedlyRemovedContainer() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "doomed.txt");

    // Unexpected death (no push) — recreation restores origin state only, so the commit is gone.
    containers.rm(container);
    workspaceService.ensureContainer(repoId, "feat");
    assertNotEquals(
        head, containerHead(container), "an unpushed commit is lost on unexpected container death");
  }

  /** Makes a commit in the container's /workspace without pushing it, returning the new HEAD. */
  private String commitInContainer(String container, String file) {
    containers.exec(
        container,
        "/workspace",
        Map.of(),
        "bash",
        "-lc",
        "echo hi > " + file + " && git add " + file + " && git commit -m local");
    return containerHead(container);
  }

  private String containerHead(String container) {
    return containers
        .exec(container, "/workspace", Map.of(), "git", "rev-parse", "HEAD")
        .output()
        .trim();
  }
}
