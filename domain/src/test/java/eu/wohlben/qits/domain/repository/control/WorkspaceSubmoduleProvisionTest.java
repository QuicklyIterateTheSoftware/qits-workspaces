package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.project.control.ProjectService;
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
 * Offline proof that a provisioned workspace materializes its <b>full submodule closure
 * natively</b> — the depth-2 {@code submodule-super.git} diamond ({@code super → child-a → {shared,
 * grandchild}} plus {@code super → shared}) — with <b>no {@code submodule.<name>.url} override</b>.
 * Provisioning is the daemon's job now (docs/epics/qits-workspace-daemon/ Part 2): {@link
 * FakeWorkspaceDaemonProvisioner} plays the daemon's clone + bounded {@code .gitmodules} walk, and
 * {@link FakeContainerRuntime}'s name farm serves the project's repositories as siblings so git's
 * committed relative urls ({@code ../<name>.git}) resolve at every level — the offline analogue of
 * the real name-addressed HTTP host. No docker.
 *
 * <p>Because the daemon walks the checkout's own {@code .gitmodules} (it has no DB), there is <b>no
 * import-scoping</b>: see {@link #collidingUnimportedSubmoduleMaterializesWithoutImportScoping} for
 * the accepted-limitation counterpart the old host-side, DB-scoped walk used to prevent.
 */
@QuarkusTest
@TestProfile(WorkspaceSubmoduleProvisionTest.TestProfile.class)
public class WorkspaceSubmoduleProvisionTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-submodule-provision");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject TechnicalProcessRegistry registry;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private String fixture(String name) throws Exception {
    return getClass().getResource("/fixtures/" + name).toURI().getPath();
  }

  private void awaitTerminal(String processId) throws InterruptedException {
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + 20_000;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "provision did not finish in time");
  }

  @Test
  public void diamondSubmoduleClosureMaterializesNativelyOffline() throws Exception {
    var project = projectService.create("Diamond Provision", null);
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, true);

    // A workspace on a fresh branch off main — its tree carries main's submodule gitlinks.
    workspaceService.createWorkspace(superRepo.id, "diamond", superRepo.mainBranch, "diamond");
    awaitTerminal(workspaceService.beginEnsureContainer(superRepo.id, "diamond"));
    assertEquals(
        WorkspaceRuntimeStatus.RUNNING,
        workspaceService.getWorkspace(superRepo.id, "diamond").runtimeStatus(),
        "the workspace container provisioned successfully");

    Path ws = Path.of(dataDir, superRepo.id, "workspaces", "diamond");
    // Depth-1: the super's direct submodules checked out with content.
    assertTrue(
        Files.exists(ws.resolve("shared-direct/shared.txt")),
        "the super's direct 'shared' submodule materialized");
    assertTrue(
        Files.exists(ws.resolve("child-a/.git")), "the super's 'child-a' submodule checked out");
    // Depth-2: child-a's own submodules, resolved natively relative to child-a's origin.
    assertTrue(
        Files.exists(ws.resolve("child-a/shared/shared.txt")),
        "child-a's nested 'shared' submodule materialized (depth 2, no url override)");
    assertTrue(
        Files.exists(ws.resolve("child-a/grandchild/gc.txt")),
        "child-a's nested 'grandchild' submodule materialized (depth 2)");
  }

  /**
   * The accepted trade-off of the autonomous model: the daemon materializes what the branch's
   * {@code .gitmodules} names (it has no DB import list), so an un-imported submodule whose
   * basename collides with a <em>different</em> served sibling now resolves to that sibling and
   * materializes — the content leak the old host-side, DB-scoped walk prevented. Bounded by the
   * project model (a naming mistake inside the maintainer's own curated repo set, not an outside
   * threat) and documented on {@code Provisioner.materializeSubmodules}. This test pins that
   * behaviour so a future re-introduction of import-scoping (a closure hand-off) is a conscious,
   * test-updating change.
   */
  @Test
  public void collidingUnimportedSubmoduleMaterializesWithoutImportScoping() throws Exception {
    var project = projectService.create("Scope Guard", null);

    // An unrelated standalone repo in the project whose self-name ('submodule-child-a') collides
    // with the superproject's committed submodule url basename (../submodule-child-a.git).
    repositoryService.cloneRepository(fixture("submodule-child-a.git"), null, project);

    // The superproject imported WITHOUT its submodules — but the daemon ignores that (no DB): it
    // walks the checkout's own .gitmodules, so the colliding sibling is still resolved.
    var superRepo =
        repositoryService.cloneRepository(fixture("submodule-super.git"), null, project, false);
    workspaceService.createWorkspace(superRepo.id, "scoped", superRepo.mainBranch, "scoped");
    awaitTerminal(workspaceService.beginEnsureContainer(superRepo.id, "scoped"));
    assertEquals(
        WorkspaceRuntimeStatus.RUNNING,
        workspaceService.getWorkspace(superRepo.id, "scoped").runtimeStatus(),
        "the workspace still provisions");

    // No import-scoping: child-a's committed relative url resolves to the colliding served sibling
    // and its content IS materialized (the accepted limitation).
    Path ws = Path.of(dataDir, superRepo.id, "workspaces", "scoped");
    assertTrue(
        Files.exists(ws.resolve("child-a/.git")),
        "without import-scoping the colliding submodule materializes from its name sibling");
  }
}
