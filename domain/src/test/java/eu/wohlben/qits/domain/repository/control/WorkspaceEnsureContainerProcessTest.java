package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.domain.project.control.ProjectService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The streamed Start ({@code beginEnsureContainer}): registers a technical process, runs the
 * provision off-thread, and the process's replay tells the whole story — {@code docker-run} and
 * {@code clone} segments (with the fake runtime's real git output) settling {@code ok} on success,
 * a {@code done failed} on a dead branch, and a no-op completion for an already-running container.
 * Driven through {@link FakeContainerRuntime}, so no docker is needed.
 */
@QuarkusTest
@TestProfile(WorkspaceEnsureContainerProcessTest.TestProfile.class)
public class WorkspaceEnsureContainerProcessTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-ensure-process-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject TechnicalProcessRegistry registry;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  /** Records a terminal process's full replay (attach on a terminal process replays + done). */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {}

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private String repoWithWorkspace(String workspaceId) throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Process Project " + workspaceId, null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, workspaceId, "master", workspaceId);
    return repo.id;
  }

  private TechnicalProcess awaitTerminal(String processId) throws InterruptedException {
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "process did not reach done in time");
    return process;
  }

  private static Replay replayOf(TechnicalProcess process) {
    Replay replay = new Replay();
    process.attach(replay);
    return replay;
  }

  private static TechnicalProcessFrame settled(Replay replay, String segment) {
    return replay.frames.stream()
        .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("segment '" + segment + "' never settled"));
  }

  private static TechnicalProcessFrame doneFrame(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "done".equals(f.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no done frame"));
  }

  @Test
  public void aFreshProvisionStreamsDockerRunAndCloneSegmentsAndEndsDoneOk() throws Exception {
    String repoId = repoWithWorkspace("stream");

    String processId = workspaceService.beginEnsureContainer(repoId, "stream");
    assertNotNull(processId);
    assertEquals(processId, registry.activeFor(repoId, "stream").orElseThrow());

    Replay replay = replayOf(awaitTerminal(processId));
    assertEquals("ok", settled(replay, "docker-run").status());
    assertEquals("ok", settled(replay, "clone").status());
    assertEquals("ok", doneFrame(replay).status());
    // The fake runtime runs a real host `git clone`, whose output streams into the clone segment.
    assertTrue(
        replay.frames.stream()
            .anyMatch(
                f ->
                    "line".equals(f.kind())
                        && "clone".equals(f.segment())
                        && f.line().contains("Cloning")),
        "the clone segment carries git's own output lines");
    assertTrue(registry.activeFor(repoId, "stream").isEmpty(), "done clears the active mapping");
    assertEquals(
        eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus.RUNNING,
        workspaceService.getWorkspace(repoId, "stream").runtimeStatus());
  }

  @Test
  public void aSecondStartCompletesAsANoOpWithoutReprovisioning() throws Exception {
    String repoId = repoWithWorkspace("noop");
    awaitTerminal(workspaceService.beginEnsureContainer(repoId, "noop"));

    Replay replay = replayOf(awaitTerminal(workspaceService.beginEnsureContainer(repoId, "noop")));
    assertEquals("ok", settled(replay, "container-start").status());
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(
        replay.frames.stream().noneMatch(f -> "docker-run".equals(f.segment())),
        "an already-running container must not re-provision");
  }

  @Test
  public void aDeadBranchEndsTheProcessDoneFailedWithTheReasonInTheStream() throws Exception {
    String repoId = repoWithWorkspace("doomed");
    // Kill the workspace's branch in the bare origin, so the provision has nothing to recreate
    // from (the branch-gone abandonment path).
    git.exec(Path.of(dataDir, repoId, "origin").toFile(), "git", "branch", "-D", "--", "doomed");

    Replay replay =
        replayOf(awaitTerminal(workspaceService.beginEnsureContainer(repoId, "doomed")));
    assertEquals("failed", doneFrame(replay).status());
    assertTrue(
        replay.frames.stream()
            .anyMatch(f -> "line".equals(f.kind()) && f.line().contains("no branch")),
        "the abandonment reason lands in the stream");
  }

  @Test
  public void anUnknownWorkspaceFailsFastInRequest() {
    assertThrows(
        NotFoundException.class, () -> workspaceService.beginEnsureContainer("nope", "nope"));
  }
}
