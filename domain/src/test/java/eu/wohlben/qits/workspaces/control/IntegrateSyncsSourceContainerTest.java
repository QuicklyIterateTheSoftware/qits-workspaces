package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.BadRequestException;
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
 * Integration merges the source branch's <em>origin</em> ref, so before merging, the source's live
 * workspace container must be reconciled with origin — otherwise commits sitting only inside the
 * container are silently dropped and uncommitted work is left behind. This guards the shared
 * pre-flight ({@code WorkspaceService.requireSyncedSourceForIntegration}) used by both {@code
 * mergeBranch} (the UI Integrate button and the MCP {@code integrateBranch} tool) and {@code
 * mergeWorkspace}. Previously {@code mergeBranch} pushed nothing, so an MCP/UI integration of a
 * branch with unpushed container commits merged a stale ref with no error
 * (docs/issues/2026-07-25_integrate-branch-skips-behind-and-unpushed-checks.md). Runs against a
 * real cloned fixture through {@link FakeContainerRuntime}.
 */
@QuarkusTest
@TestProfile(IntegrateSyncsSourceContainerTest.TestProfile.class)
public class IntegrateSyncsSourceContainerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-integrate-sync-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  // MOVED: integrationIncludesCommitsThatOnlyLiveInTheSourceContainer.
  // It asserted that integrating a source workspace first pushes that workspace's container-only
  // commits, so the integration sees them. requireSyncedSourceForIntegration no longer runs that
  // `docker exec git push`; it requires the daemon to report CLEAN and relies on the daemon having
  // pushed. The "no commit is left behind" property is now the daemon's -- unowned here.


  @Test
  public void integrationRefusesADirtySourceWorkingTree() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "dirty-ws", "master", "dirty-b", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "dirty-ws"));
    String container = containers.containerName("dirty-ws", repoId);
    // An uncommitted change in the container: the origin-side merge would silently leave it behind.
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo scratch > dirty.txt");

    assertThrows(
        BadRequestException.class,
        () -> workspaceService.mergeBranch(repoId, "dirty-b", "master"),
        "a dirty source workspace must block integration with a 400");
  }
}
