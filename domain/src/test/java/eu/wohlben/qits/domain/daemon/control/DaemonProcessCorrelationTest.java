package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cross-thread correlation of the technical process with the daemon phase: the id rides {@code
 * WorkspaceContainerStarted} onto the async observer thread, so an auto-started daemon's startup
 * lines land in the {@code daemon:<name>} segment of the <em>same</em> process that streamed the
 * provision, STARTING→READY settles that segment, and the process reaches {@code done} only once
 * every auto-start daemon settled.
 */
@QuarkusTest
@TestProfile(DaemonProcessCorrelationTest.TestProfile.class)
public class DaemonProcessCorrelationTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-process-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.daemons.autostart-enabled", "true",
            "qits.daemons.ready-grace-ms", "300",
            "qits.daemons.liveness-poll-ms", "150");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject RepositoryDaemonService repositoryDaemonService;
  @Inject TechnicalProcessRegistry registry;

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

  @Test
  public void autoStartedDaemonLinesLandInTheStartProcessAndReadySettlesTheSegment()
      throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Daemon Process Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    // The echo doubles as the ready pattern, so READY (and the segment settle it triggers) can
    // only happen after the follower delivered the line into the segment — deterministic ordering
    // instead of racing the grace timer against tail startup.
    repositoryDaemonService.create(
        repo.id,
        "web",
        null,
        "echo hello-from-daemon; sleep 300",
        "hello-from-daemon",
        "TERM",
        RestartPolicy.NEVER,
        true,
        0,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);

    String processId = workspaceService.beginEnsureContainer(repo.id, "work");
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "done must fire once provision + the daemon settled");

    Replay replay = new Replay();
    process.attach(replay);
    String segment = TechnicalProcess.daemonSegment("web");
    assertTrue(
        replay.frames.stream()
            .anyMatch(
                f ->
                    "line".equals(f.kind())
                        && segment.equals(f.segment())
                        && f.line().contains("hello-from-daemon")),
        "the daemon's startup output lands in its segment of the same process");
    TechnicalProcessFrame settle =
        replay.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("daemon segment never settled"));
    assertEquals("ok", settle.status(), "STARTING→READY settles the segment ok");
    assertEquals(
        "ok",
        replay.frames.stream()
            .filter(f -> "done".equals(f.kind()))
            .findFirst()
            .orElseThrow()
            .status());
  }
}
