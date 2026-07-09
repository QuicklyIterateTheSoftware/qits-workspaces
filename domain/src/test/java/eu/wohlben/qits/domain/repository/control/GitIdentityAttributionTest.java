package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.project.control.ProjectService;
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
 * End-to-end attribution: a configured {@code qits.git.*} identity is what both synthetic-commit
 * paths — the host-side integration merge and the container-side parent merge — author and commit
 * as. Runs against a real cloned-fixture repo through {@link FakeContainerRuntime} (which applies
 * the container-level identity env exactly like a real container), so {@code git log} verifies the
 * attribution for real.
 */
@QuarkusTest
@TestProfile(GitIdentityAttributionTest.TestProfile.class)
public class GitIdentityAttributionTest {

  private static final String IDENTITY = "qits-bot <qits-bot@example.com>";

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-git-identity-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.git.author-name", "qits-bot",
            "qits.git.author-email", "qits-bot@example.com");
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

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Identity Project", null);
    return repositoryService.cloneRepository(fixtureUrl, null, project).id;
  }

  /** Author and committer of the tip commit of {@code ref} in the repo's bare origin. */
  private String tipAttribution(String repoId, String ref) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return git.exec(originPath.toFile(), "git", "log", "-1", "--format=%an <%ae>|%cn <%ce>", ref)
        .trim();
  }

  @Test
  public void hostSideMergeCommitsAsTheConfiguredIdentity() throws Exception {
    String repoId = clonedRepo();
    // The seed shape: fork off the fixture's 'feature' branch and integrate it into master via
    // the host-side merge (no container involved) — the path that used to depend on ambient
    // ~/.gitconfig and failed with "Committer identity unknown" in identity-less environments.
    workspaceService.createWorkspace(repoId, "feeder", "feature", "feeder", null);

    workspaceService.mergeWorkspace(repoId, "feeder", "master");

    assertEquals(
        IDENTITY + "|" + IDENTITY,
        tipAttribution(repoId, "refs/heads/master"),
        "the host-side synthetic merge is authored and committed by the configured identity");
  }

  @Test
  public void containerMergeCommitsAsTheConfiguredIdentityEvenOverStaleCloneConfig()
      throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "up-parent", "master", "up-parent-branch", null);
    workspaceService.createWorkspace(
        repoId, "up-child", "up-parent-branch", "up-child-branch", null);
    workspaceService.ensureContainer(repoId, "up-parent");
    workspaceService.ensureContainer(repoId, "up-child");
    String parentContainer = containers.containerName("up-parent", repoId);
    String childContainer = containers.containerName("up-child", repoId);

    // Diverge cleanly: each branch adds its own distinct file (the child's own commit is what
    // forces a true merge commit instead of a fast-forward). Pushed for the parent so the child's
    // fetch sees it.
    commitFile(parentContainer, "parent-only.txt");
    containers.exec(
        parentContainer, "/workspace", Map.of(), "git", "push", "origin", "up-parent-branch");
    commitFile(childContainer, "child-only.txt");
    // The upgrade path: a container provisioned before identity became env-delivered still has
    // `user.*` in its clone's .git/config. Identity env must beat it for author AND committer.
    containers.exec(
        childContainer, "/workspace", Map.of(), "git", "config", "user.email", "stale@local");
    containers.exec(childContainer, "/workspace", Map.of(), "git", "config", "user.name", "stale");

    workspaceService.updateWorkspaceFromParent(repoId, "up-child");

    assertEquals(
        IDENTITY + "|" + IDENTITY,
        tipAttribution(repoId, "refs/heads/up-child-branch"),
        "the container-side merge commit carries the configured identity, not the stale config");
  }

  /** Commits {@code file} in the container's /workspace (identity comes from the container env). */
  private void commitFile(String container, String file) {
    containers.exec(
        container,
        "/workspace",
        Map.of(),
        "bash",
        "-lc",
        "echo hi > " + file + " && git add " + file + " && git commit -m 'add " + file + "'");
  }
}
