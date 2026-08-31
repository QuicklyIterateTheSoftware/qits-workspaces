package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The editor door's behaviour, one level below the route.
 *
 * <p>Three claims, and each of them is something a two-second poll would otherwise get wrong: the
 * row is found rather than made twice, an editor that is up is not asked for again, and readiness is
 * the service's judgement rather than the caller's.
 */
@QuarkusTest
public class EditorServiceTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject EditorService editors;
  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private String wrapperRepository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", "someproject-someproject");
    return repoId;
  }

  @Test
  void theFirstCallStartsTheWrappersMainWorkspaceAndTheSecondFindsIt() throws Exception {
    String repoId = wrapperRepository();

    EditorService.EditorSession first = editors.ensure(repoId);
    assertTrue(first.fresh(), "nothing was there, so this call started it — a 201");

    // The row is the wrapper's main workspace and not a workspace of the editor's own: the editor
    // has no lifecycle, it rides one that already exists.
    Long mainId = workspaceIds.of(repoId, "master");
    assertEquals(Long.toString(mainId), first.workspaceId());

    // Wait for the provision to settle, then let the workspace look the way a live editor does: the
    // container running and its daemon on the socket.
    assertTrue(startedRecorder.awaitCount(repoId, "master", 1, 10_000));
    startedRecorder.clear();
    liveness.markLive(mainId);
    try {
      EditorService.EditorSession second = editors.ensure(repoId);
      assertFalse(second.fresh(), "it was already there — a 200");
      assertEquals(first.workspaceId(), second.workspaceId(), "and it is the same workspace");
      assertEquals(WorkspaceRuntimeStatus.RUNNING.name(), second.containerStatus());
    } finally {
      liveness.markDead(mainId);
    }
  }

  @Test
  void readinessNeedsTheEDITORAndNotOnlyTheContainer() throws Exception {
    // The container being up is half of it. Nothing implements WorkspaceEditorState yet — the
    // daemon's frame and the registry's handling of it land with the proxy route — so the state is
    // null and the readiness is false, which is the honest answer and the one a waiting page needs.
    // A door that read readiness off the container alone would send a reader to an origin serving
    // nothing.
    String repoId = wrapperRepository();

    EditorService.EditorSession session = editors.ensure(repoId);
    assertTrue(startedRecorder.awaitCount(repoId, "master", 1, 10_000));
    startedRecorder.clear();

    EditorService.EditorSession settled = editors.ensure(repoId);
    assertNull(settled.editorState());
    assertFalse(settled.editorReady());
    assertEquals(session.workspaceId(), settled.workspaceId());
  }

  @Test
  void aRepositoryThatIsNotAWrapperIsRefused() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, "master", "SERVICE");

    // Refused rather than started: an ordinary workspace runs the plain image, so no editor could
    // ever report and the caller would poll a workspace that can never become ready.
    assertThrows(BadRequestException.class, () -> editors.ensure(repoId));
  }

  @Test
  void anUnknownRepositoryIsNotFound() {
    assertThrows(NotFoundException.class, () -> editors.ensure("no-such-repository"));
  }
}
