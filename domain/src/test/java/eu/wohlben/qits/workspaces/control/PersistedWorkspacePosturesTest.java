package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The wrapper-main posture, derived and not stored: a workspace is the editor's workspace when its
 * repository's archetype is {@code PROJECT} and its branch is that repository's main branch.
 *
 * <p>What is worth a test here is not the predicate — it is one {@code &&} — but the three things
 * around it that are easy to get wrong and expensive to have wrong, because this answer picks a
 * container's image and its environment and a spec that differs from what is running is a
 * replacement: that a non-wrapper repository's main workspace is <b>not</b> it, that a wrapper's
 * <em>other</em> branches are not it, and that an unreachable registry does not turn a wrapper-main
 * workspace into an ordinary one on the next ensure.
 */
@QuarkusTest
public class PersistedWorkspacePosturesTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspacePostures postures;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** A repository with a real bare origin, registered as its project's wrapper. */
  private String wrapperRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master");
    return repoId;
  }

  @Test
  void theWrappersMainWorkspaceIsTheEditorsWorkspace() throws Exception {
    String repoId = wrapperRepo();
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    assertTrue(postures.isWrapperMain(main.id));
    // …and it is not the admin kind by that fact. The two postures are independent: the socket is
    // asked for at creation, the editor is derived, and neither implies the other.
    assertFalse(postures.isAdmin(main.id));
  }

  @Test
  void anotherBranchOfTheWrapperIsNot() throws Exception {
    String repoId = wrapperRepo();
    workspaceService.createMainWorkspace(repoId, "master");
    var branched =
        workspaceService.createWorkspace(repoId, "editor-check", "master", "task/editor-check", null);

    assertFalse(postures.isWrapperMain(branched.id));
  }

  @Test
  void aRepositoryThatIsNotTheWrapperIsNotItEither() throws Exception {
    // Same shape, same branch, one field different — which is the whole of the predicate that is
    // not already true of every main workspace on the platform.
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, "master", "SERVICE");
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    assertFalse(postures.isWrapperMain(main.id));
  }

  @Test
  void aRegistryThatCannotBeAskedDoesNotUNDECIDEAnAlreadyDecidedWorkspace() throws Exception {
    // THE REPRODUCIBILITY CLAIM, and the reason the shipped posture memoizes at all. The
    // orchestrator has no start verb — a stopped container is resumed by presenting its spec AGAIN
    // under Recreate.ifChanged — so an answer that flipped while qits-projects was down would
    // describe a plain-image container and REPLACE the editor's one. The first read decides; every
    // read after it says the same thing whether or not the registry can be reached.
    String repoId = wrapperRepo();
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    assertTrue(postures.isWrapperMain(main.id));

    repositories.findOutage(true);
    try {
      assertTrue(postures.isWrapperMain(main.id), "the decided answer survives an outage");
    } finally {
      repositories.findOutage(false);
    }
  }

  @Test
  void aViewWithNoArchetypeIsNotWRITTENDOWNAsAnOrdinaryWorkspace() throws Exception {
    // The 200 that answers nothing. `archetype` is nullable on the wire, so a registry that does not
    // carry one is a live, successful read whose `isWrapper()` is false — and remembering THAT is
    // the unreachable case's exposure with a status code in front of it, except permanent: one
    // half-answered read would describe the plain image at every ensure for the life of the process.
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId, "master"); // registered, and with no archetype
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    assertFalse(postures.isWrapperMain(main.id), "not a wrapper for this call");

    // The same workspace, once the registry answers in full. It must be able to flip.
    repositories.registerWrapper(repoId, "master");
    assertTrue(postures.isWrapperMain(main.id), "the unanswered read must not have been memoized");
  }

  @Test
  void aViewWithNoMainBranchIsNotWrittenDownEither() throws Exception {
    // The other nullable half of the predicate, and the same rule: a repository whose main branch
    // the registry did not state cannot say anything about a workspace claiming it.
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, " ", RepositoryLookup.RepositoryView.WRAPPER_ARCHETYPE);
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");

    assertFalse(postures.isWrapperMain(main.id));

    repositories.setMainBranch(repoId, "master");
    assertTrue(postures.isWrapperMain(main.id), "the unanswered read must not have been memoized");
  }

  @Test
  void anUnknownWorkspaceIsNotTheEditorsWorkspace() {
    assertFalse(postures.isWrapperMain(-1L));
    assertFalse(postures.isWrapperMain(null));
  }
}
