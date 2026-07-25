package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The registry-only "latest agent version" badge computation ({@code WorkspaceDto.daemonOutdated}):
 * a RUNNING workspace whose daemon build is strictly older than the newest one connected anywhere
 * is flagged, ordered by build time (docs/epics/qits-workspace-registry/). The newest, a single
 * daemon, and daemons that report no build time are never flagged.
 */
@QuarkusTest
@TestProfile(WorkspaceDaemonOutdatedTest.TestProfile.class)
public class WorkspaceDaemonOutdatedTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-outdated-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceDaemonInfo daemonInfo;

  private static final Instant OLD = Instant.ofEpochSecond(1_000);
  private static final Instant NEW = Instant.ofEpochSecond(2_000);

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Outdated Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  private WorkspaceDto runningWorkspace(String repoId, String id, String parent) {
    workspaceService.createWorkspace(repoId, id, parent, id, null);
    workspaceService.ensureContainer(repoId, id);
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> id.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  private Boolean outdated(String repoId, String id) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> id.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow()
        .daemonOutdated();
  }

  @Test
  public void olderDaemonIsFlaggedOutdatedAgainstTheNewest() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "old-ws", "master");
    runningWorkspace(repoId, "new-ws", "master");

    daemonInfo.report("old-ws", "1.0.0", OLD);
    daemonInfo.report("new-ws", "1.0.0", NEW);

    assertEquals(Boolean.TRUE, outdated(repoId, "old-ws"), "the older build is outdated");
    assertNull(outdated(repoId, "new-ws"), "the newest build is not outdated");
  }

  @Test
  public void aSingleDaemonIsNeverOutdated() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "solo", "master");
    daemonInfo.report("solo", "1.0.0", OLD);

    assertNull(outdated(repoId, "solo"), "the only daemon connected is by definition the latest");
  }

  @Test
  public void aDaemonWithNoBuildTimeIsNeverOutdatedNorTheLatest() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "timed", "master");
    runningWorkspace(repoId, "untimed", "master");

    // 'untimed' reports a version but no build time (an older image). It can't be ordered, so it
    // neither becomes "the latest" (which would wrongly flag 'timed') nor is itself flagged.
    daemonInfo.report("timed", "1.0.0", OLD);
    daemonInfo.report("untimed", "1.0.0", null);

    assertNull(
        outdated(repoId, "timed"), "the only build-timed daemon is the latest, not outdated");
    assertNull(outdated(repoId, "untimed"), "a daemon with no build time is never flagged");
  }

  @Test
  public void aStoppedWorkspaceCarriesNoOutdatedFlag() throws Exception {
    String repoId = clonedRepo();
    // Created but never provisioned → STOPPED, so no daemon is connected and no badge is computed,
    // even if a stale registry entry existed.
    workspaceService.createWorkspace(repoId, "stopped", "master", "stopped", null);
    daemonInfo.report("stopped", "0.9.0", OLD);
    runningWorkspace(repoId, "live", "master");
    daemonInfo.report("live", "1.0.0", NEW);

    assertNull(outdated(repoId, "stopped"), "no outdated badge while not RUNNING");
  }
}
