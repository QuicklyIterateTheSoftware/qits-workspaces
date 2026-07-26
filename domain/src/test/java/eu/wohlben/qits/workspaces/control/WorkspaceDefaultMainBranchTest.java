package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Regression: a blank workspace parent / merge target defaults to the repository's
 * <em>configured</em> main branch ({@code Repository.mainBranch}), not a hardcoded "master". A
 * repository whose default branch is "main" (or anything non-master) failed to create a workspace
 * with {@code fatal: not a valid object name: 'master'}.
 */
@QuarkusTest
public class WorkspaceDefaultMainBranchTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceHistoryService workspaceHistoryService;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Default Branch Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  private String revParse(String repoId, String ref) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return git.exec(originPath.toFile(), "git", "rev-parse", ref).trim();
  }

  @Test
  public void blankParentForksFromTheConfiguredMainBranch() throws Exception {
    String repoId = clonedRepo();
    // A repository whose configured main branch is NOT "master" (the fixture's 'feature' branch
    // diverges from 'master', so the fork point is distinguishable).
    repositoryService.setMainBranch(repoId, "feature");

    Workspace ws = workspaceService.createWorkspace(repoId, "ws", null, null, null);

    assertEquals("feature", ws.parent);
    assertEquals("ws", ws.branch);
    assertEquals(
        revParse(repoId, "refs/heads/feature"),
        revParse(repoId, "refs/heads/ws"),
        "the new branch forks from the configured main branch's tip");
    assertNotEquals(
        revParse(repoId, "refs/heads/master"),
        revParse(repoId, "refs/heads/ws"),
        "the fork point is not master");
  }

  @Test
  public void blankMergeTargetMergesIntoTheConfiguredMainBranch() throws Exception {
    String repoId = clonedRepo();
    repositoryService.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feeder", null, "feeder", null);
    String masterBefore = revParse(repoId, "refs/heads/master");

    workspaceService.mergeWorkspace(repoId, "feeder", null);

    assertEquals(
        masterBefore,
        revParse(repoId, "refs/heads/master"),
        "master is untouched — the merge targeted the configured main branch");
    Long historyId =
        workspaceHistoryService.list(repoId).stream()
            .filter(h -> "feeder".equals(h.workspaceId()))
            .findFirst()
            .orElseThrow()
            .id();
    assertTrue(
        workspaceHistoryService.get(repoId, historyId).events().stream()
            .anyMatch(e -> e.type() == WorkspaceEventType.MERGED && "feature".equals(e.target())),
        "the MERGED event names the configured main branch as target");
  }
}
