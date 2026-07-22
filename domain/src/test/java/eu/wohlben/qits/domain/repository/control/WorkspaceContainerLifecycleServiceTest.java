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
 * Verifies the disposable-container lifecycle against a real cloned-fixture repo (Fake runtime):
 * creation is lazy (durable state only — the container materializes on first use), a lost container
 * is re-provisioned from the durable branch, its runtime status is surfaced, and a workspace is
 * abandoned only when the branch itself is gone.
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
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject WorkspaceContainerStoppingRecorder stoppingRecorder;

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
  public void createWorkspaceDoesNotProvisionAContainerUntilFirstUse() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);

    // Creation writes only durable state: the branch ref in origin and the STOPPED row.
    assertFalse(containers.exists(container), "creation does not run a container");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        "the branch ref exists in origin at the parent's commit");
    // The runtime mirrors docker: touching the not-yet-provisioned container fails, it doesn't
    // silently run elsewhere — a use-site that forgot ensureContainer becomes a test failure.
    assertNotEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "git", "status").exitCode(),
        "exec against the never-provisioned container fails cleanly");

    // First use provisions: container up, branch checked out, status live.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.exists(container), "first use provisions the container");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        containerHead(container),
        "the provisioned container has the branch checked out");
  }

  @Test
  public void mainWorkspaceIsCreatedLazilyToo() throws Exception {
    String repoId = clonedRepo();
    // cloneRepository created the main workspace ("master") — a row only, no container.
    String container = containers.containerName("master", repoId);
    assertFalse(containers.exists(container), "the main workspace starts without a container");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "master").runtimeStatus());

    // First use checks out the existing main branch (no branch ref to create).
    workspaceService.ensureContainer(repoId, "master");
    assertTrue(containers.exists(container));
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        containerHead(container));
  }

  @Test
  public void mergeWorkspaceSucceedsWithoutASourceContainer() throws Exception {
    String repoId = clonedRepo();
    // The seed path: fork off the fixture's 'feature' branch (which carries a commit master
    // lacks) and merge it into master — all host-side, the workspace never provisioned.
    workspaceService.createWorkspace(repoId, "feeder", "feature", "feeder", null);
    Path originPath = Path.of(dataDir, repoId, "origin");
    String masterBefore =
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim();

    var result = workspaceService.mergeWorkspace(repoId, "feeder", "master");

    assertFalse(result.hasConflicts(), "the host-side merge succeeds without a container");
    assertNotEquals(
        masterBefore,
        git.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        "origin's target ref advanced");
    assertFalse(
        containers.exists(containers.containerName("feeder", repoId)),
        "merging never provisioned a container");
  }

  @Test
  public void aNeverProvisionedWorkspaceIsCleanable() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    // A fresh fork has nothing to lose: no container means no dirty tree and no unpushed commits,
    // so cleanup must be offered exactly as for a provisioned-but-level workspace.
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertTrue(
        workspaceService.canCleanupBranch(repoId, originPath, "feat", "master"),
        "a never-provisioned fresh fork is cleanable");
  }

  @Test
  public void ensureContainerRecreatesALostContainerFromTheBranch() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
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
    workspaceService.ensureContainer(repoId, "feat");

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
    workspaceService.ensureContainer(repoId, "feat");
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
    workspaceService.ensureContainer(repoId, "feat");
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
    workspaceService.ensureContainer(repoId, "feat");
    containers.rm(containers.containerName("feat", repoId));

    // Reconciliation keys off the branch, not the container: the branch survives, so the workspace
    // stays ACTIVE (STOPPED runtime), not ABANDONED.
    discoveryService.discover();

    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status());
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());
  }

  @Test
  public void stopContainerPausesInPlaceKeepingTheWorkspaceActive() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    String container = containers.containerName("feat", repoId);

    workspaceService.stopContainer(repoId, "feat");

    // A graceful stop PAUSES in place (docker stop), it does not remove the container: the
    // container
    // survives (present but not running) so its /workspace clone is kept for a lossless resume.
    assertTrue(containers.exists(container), "graceful stop keeps the container present");
    assertFalse(containers.isRunning(container), "graceful stop leaves it not running");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status(), "the workspace stays active");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());

    // ...and it can be brought straight back — resumed in place (start), not re-provisioned.
    workspaceService.ensureContainer(repoId, "feat");
    assertTrue(containers.isRunning(container));
  }

  @Test
  public void stopContainerPreservesUncommittedWorkingTreeChanges() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    String container = containers.containerName("feat", repoId);

    // Working-tree state that is NOT a pushed commit — the exact thing the old stop (docker rm -f +
    // re-clone) silently destroyed: an untracked file. A real pause must keep it.
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo draft > scratch.txt");

    workspaceService.stopContainer(repoId, "feat");
    // Resumed in place (same container, not a fresh clone).
    workspaceService.ensureContainer(repoId, "feat");

    assertEquals(
        "draft",
        containers.exec(container, "/workspace", Map.of(), "cat", "scratch.txt").output().trim(),
        "an untracked working-tree file survives stop -> resume");
  }

  @Test
  public void gracefulStopPushesUnpushedWorkSoRecreationIsLossless() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
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
    workspaceService.ensureContainer(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "doomed.txt");

    // Unexpected death (no push) — recreation restores origin state only, so the commit is gone.
    containers.rm(container);
    workspaceService.ensureContainer(repoId, "feat");
    assertNotEquals(
        head, containerHead(container), "an unpushed commit is lost on unexpected container death");
  }

  @Test
  public void ensureContainerFiresStartedOnFreshProvision() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    startedRecorder.clear();

    workspaceService.ensureContainer(repoId, "feat");

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "a fresh cold->RUNNING provision fires WorkspaceContainerStarted");
  }

  @Test
  public void ensureContainerFiresStartedOnExitedRestart() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    ((FakeContainerRuntime) containers).markExited(container);
    startedRecorder.clear();

    // Restart-in-place of an Exited container is the second cold->RUNNING transition.
    workspaceService.ensureContainer(repoId, "feat");

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "restarting an Exited container fires WorkspaceContainerStarted");
  }

  @Test
  public void ensureContainerDoesNotFireStartedWhenAlreadyRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    // Wait for the fresh provision's (async) event to land before clearing, so a late delivery of
    // it
    // can't masquerade as a second fire below.
    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000));
    startedRecorder.clear();

    // The already-running short-circuit must NOT fire — this is what terminates the auto-start
    // reentrancy loop.
    workspaceService.ensureContainer(repoId, "feat");

    Thread.sleep(500); // give any (erroneous) async fire time to land
    assertEquals(
        0,
        startedRecorder.countFor(repoId, "feat"),
        "a no-op ensureContainer on a live container fires nothing");
  }

  @Test
  public void stopContainerFiresStoppingWhileTheContainerIsStillRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    stoppingRecorder.clear();

    workspaceService.stopContainer(repoId, "feat");

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), "stopContainer fires exactly one stopping event");
    assertTrue(seen.get(0).event().graceful(), "a graceful stop asks for a graceful settle");
    assertTrue(
        seen.get(0).containerExistedWhenObserved(),
        "the stopping event fires before containers.stop — daemons settle while the container is"
            + " still present");
  }

  @Test
  public void discardFiresStoppingImmediatelyBeforeRm() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(repoId, "feat");
    stoppingRecorder.clear();

    workspaceService.discardWorkspace(repoId, "feat");

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), "discard fires exactly one stopping event");
    assertFalse(seen.get(0).event().graceful(), "discard settles bookkeeping-only (immediate)");
    assertTrue(
        seen.get(0).containerExistedWhenObserved(),
        "the stopping event fires before containers.rm");
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
