package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.featureflow.control.ActionConfigurationService;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.domain.repository.dto.WorkspaceHistoryDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceEventType;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies workspace soft-delete against a real cloned-fixture repo: cleanup/discard keeps the row
 * as history (with status, events and result), workspace ids can be reused after resolution, and a
 * workspace that ran a command can still be discarded — both the workspace and its command persist.
 */
@QuarkusTest
@TestProfile(WorkspaceHistoryServiceTest.TestProfile.class)
public class WorkspaceHistoryServiceTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-history-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceHistoryService workspaceHistoryService;

  @Inject ActionConfigurationService actionConfigurationService;

  @Inject CommandService commandService;

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("History Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  private WorkspaceHistoryDto historyFor(String repoId, String workspaceId) {
    return workspaceHistoryService.list(repoId).stream()
        .filter(h -> workspaceId.equals(h.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  private boolean activeContains(String repoId, String workspaceId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .anyMatch(w -> workspaceId.equals(w.workspaceId()));
  }

  @Test
  public void discardKeepsTheRowAsAbandonedHistory() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", "build the feature");
    assertTrue(activeContains(repoId, "feat"));

    workspaceService.discardWorkspace(repoId, "feat", "did not work out");

    assertFalse(activeContains(repoId, "feat"), "discarded workspace leaves the active list");
    WorkspaceHistoryDetailDto detail =
        workspaceHistoryService.get(repoId, historyFor(repoId, "feat").id());
    assertEquals(WorkspaceStatus.ABANDONED, detail.status());
    assertEquals("build the feature", detail.preamble());
    assertEquals("did not work out", detail.result());
    assertTrue(detail.events().stream().anyMatch(e -> e.type() == WorkspaceEventType.CREATED));
    assertTrue(detail.events().stream().anyMatch(e -> e.type() == WorkspaceEventType.ABANDONED));
  }

  @Test
  public void cleanupResolvesAsIntegrated() throws Exception {
    String repoId = clonedRepo();
    // A freshly forked workspace has no commits ahead of master and a clean tree → cleanable.
    workspaceService.createWorkspace(repoId, "ff", "master", "ff", null);

    workspaceService.cleanupBranch(repoId, "ff", "merged upstream");

    assertFalse(activeContains(repoId, "ff"));
    assertEquals(WorkspaceStatus.INTEGRATED, historyFor(repoId, "ff").status());
  }

  @Test
  public void workspaceIdCanBeReusedAfterResolution() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.discardWorkspace(repoId, "feat", null);

    // Reuse the id — only an ACTIVE duplicate is rejected, so this succeeds.
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    assertTrue(activeContains(repoId, "feat"));
    long featRows =
        workspaceHistoryService.list(repoId).stream()
            .filter(h -> "feat".equals(h.workspaceId()))
            .count();
    assertEquals(2, featRows, "both the resolved and the new workspace share the id in history");
  }

  @Test
  public void aWorkspaceThatRanACommandCanBeDiscardedAndBothPersist() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String actionId =
        actionConfigurationService.createForRepository(
                repoId, "echo", null, "echo hi", null, false, null)
            .id;
    commandService.launchAndAwait(repoId, "feat", actionId);

    // Previously the command's FK pinned the workspace row and this threw a constraint violation.
    workspaceService.discardWorkspace(repoId, "feat", null);

    WorkspaceHistoryDetailDto detail =
        workspaceHistoryService.get(repoId, historyFor(repoId, "feat").id());
    assertEquals(WorkspaceStatus.ABANDONED, detail.status());
    assertEquals(1, detail.commands().size(), "the command stays associated with the workspace");
  }
}
