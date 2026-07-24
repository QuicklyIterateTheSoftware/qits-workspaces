package eu.wohlben.qits.domain.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.control.RepositoryDaemonService;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceDaemonLiveness;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceServiceDriver;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.control.WorkspaceServiceDriver;
import eu.wohlben.qits.domain.service.dto.ServiceInstanceDto;
import eu.wohlben.qits.domain.service.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The <b>daemon-backed projection</b> path of the host {@code ServiceSupervisor} (Part 4): when a
 * workspace's in-container daemon is live, the supervisor issues only manual start/stop over the
 * {@link WorkspaceServiceDriver} and otherwise <em>projects</em> the lifecycle events the daemon
 * streams onto its state machine — it runs no tmux session itself. A {@link
 * FakeWorkspaceServiceDriver} plays the daemon (records the host's calls, exposes the sink the host
 * subscribed), and {@link FakeWorkspaceDaemonLiveness} makes the workspace daemon-live. The tmux
 * fallback (no live daemon) stays covered by {@link ServiceSupervisorTest}.
 */
@QuarkusTest
@TestProfile(ServiceSupervisorProjectionTest.TestProfile.class)
public class ServiceSupervisorProjectionTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-service-projection-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject RepositoryDaemonService repositoryDaemonService;
  @Inject ServiceSupervisor supervisor;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeWorkspaceServiceDriver driver;

  @BeforeEach
  void resetDriver() {
    driver.reset(); // the fake is a shared singleton across this class's test methods
  }

  private String repoWithLiveWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Service Projection", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    liveness.markLive("work"); // a live daemon ⇒ this workspace's services are daemon-backed
    return repo.id;
  }

  private String createDaemon(String repoId, String name, String script) {
    return repositoryDaemonService.create(
            repoId,
            name,
            null,
            script,
            null,
            "TERM",
            RestartPolicy.ON_FAILURE,
            null,
            3,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null)
        .id;
  }

  private ServiceStatus statusOf(String repoId, String daemonId) {
    return supervisor.effectiveDaemons(repoId, "work").stream()
        .filter(d -> d.daemon().id().equals(daemonId))
        .map(ServiceInstanceDto::status)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void manualStartAsksDaemonThenProjectsLifecycle() throws Exception {
    String repoId = repoWithLiveWorkspace();
    String id = createDaemon(repoId, "dev", "sleep 30");

    assertTrue(
        supervisor.isDaemonBacked("work"), "a live daemon makes the workspace daemon-backed");
    supervisor.start(repoId, "work", id); // manual, daemon-backed
    assertTrue(driver.started().contains("dev"), "a manual start asks the daemon to start it");
    assertTrue(driver.signalled().isEmpty(), "start does not signal");

    WorkspaceServiceDriver.ServiceEventSink sink = driver.sink();
    assertNotNull(sink, "the supervisor subscribed a projection sink at startup");

    // Play the daemon: it owns the lifecycle and streams transitions; the host projects them.
    sink.onState(repoId, "work", "dev", "STARTING", null);
    sink.onState(repoId, "work", "dev", "READY", null);
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));

    sink.onState(repoId, "work", "dev", "CRASHED", 1);
    assertEquals(ServiceStatus.CRASHED, statusOf(repoId, id));
  }

  @Test
  void stopAsksDaemonToSignal() throws Exception {
    String repoId = repoWithLiveWorkspace();
    String id = createDaemon(repoId, "dev", "sleep 30");
    supervisor.start(repoId, "work", id);
    driver.sink().onState(repoId, "work", "dev", "READY", null);

    supervisor.stop(repoId, "work", id);
    assertTrue(driver.signalled().contains("dev"), "stop asks the daemon to signal the service");
  }

  @Test
  void adoptsRunningServiceFromEventWithoutAStart() throws Exception {
    // No start() — the daemon re-reports a running service on reconnect (post qits-restart); the
    // host adopts it from the event, event-driven, with no /proc or tmux probe.
    String repoId = repoWithLiveWorkspace();
    String id = createDaemon(repoId, "dev", "sleep 30");

    driver.sink().onState(repoId, "work", "dev", "READY", null);
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));
    assertTrue(driver.started().isEmpty(), "adoption issues no start instruction");
  }
}
