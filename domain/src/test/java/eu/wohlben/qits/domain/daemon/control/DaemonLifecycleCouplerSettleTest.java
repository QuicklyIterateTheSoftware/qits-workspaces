package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.repository.dto.WorkspaceDto;
import eu.wohlben.qits.domain.repository.entity.WorkspaceRuntimeStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The container&#8594;daemon <em>stop</em> coupling: a {@code WorkspaceContainerStopping} event
 * settles a workspace's live daemons STOPPED (INFO, no crash) instead of leaving them to the crash
 * machinery — so a deliberate {@code stopContainer} is not misread as a crash and resurrected. The
 * kill-switch case is {@link DaemonSettleKillSwitchTest}.
 */
@QuarkusTest
@TestProfile(DaemonLifecycleCouplerSettleTest.TestProfile.class)
public class DaemonLifecycleCouplerSettleTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-settle-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.daemons.autostop-enabled", "true",
            // Keep auto-start OFF so these tests isolate the settle direction; start manually.
            "qits.daemons.autostart-enabled", "false",
            "qits.daemons.ready-grace-ms", "300",
            "qits.daemons.stop-grace-ms", "500",
            // Long backoff so a crashing daemon sits in RESTARTING long enough to observe.
            "qits.daemons.restart-backoff-initial-ms", "3000",
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
  @Inject DaemonSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;
  @Inject ContainerRuntime containers;

  private String repoWithWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Settle Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  private String createDaemon(String repoId, String name, String command, RestartPolicy policy) {
    return repositoryDaemonService.create(
            repoId, name, null, command, null, "TERM", policy, false, 3, null, null, null, null,
            null, null, null)
        .id;
  }

  private DaemonInstanceDto instanceOf(String repoId, String daemonId) {
    return supervisor.effectiveDaemons(repoId, "work").stream()
        .filter(i -> i.daemon().id().equals(daemonId))
        .findFirst()
        .orElse(null);
  }

  private DaemonInstanceDto awaitStatus(String repoId, String daemonId, DaemonStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    DaemonInstanceDto last = null;
    while (System.currentTimeMillis() < deadline) {
      last = instanceOf(repoId, daemonId);
      if (last != null && last.status() == expected) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last state: " + last);
  }

  @Test
  public void stoppingEventSettlesReadyDaemonWithoutCrashOrRelaunch() throws Exception {
    String repoId = repoWithWorkspace();
    String daemonId = createDaemon(repoId, "dev", "sleep 300", RestartPolicy.ON_FAILURE);
    supervisor.start(repoId, "work", daemonId);
    awaitStatus(repoId, daemonId, DaemonStatus.READY);

    // A deliberate container stop: settle, don't crash.
    containerEvents.fireStopping(repoId, "work", true);

    DaemonInstanceDto settled = awaitStatus(repoId, daemonId, DaemonStatus.STOPPED);
    assertEquals(0, settled.restartCount(), "a settled daemon is not restarted");

    // Past a couple of liveness intervals, it stays STOPPED — no crash path, no resurrection.
    Thread.sleep(600);
    assertEquals(
        DaemonStatus.STOPPED,
        instanceOf(repoId, daemonId).status(),
        "the settled daemon is not resurrected by the liveness poll");
  }

  @Test
  public void stoppingEventSettlesARestartingInstance() throws Exception {
    String repoId = repoWithWorkspace();
    // Exits non-zero immediately, so ON_FAILURE drops it into RESTARTING (long backoff).
    String daemonId = createDaemon(repoId, "flaky", "sh -c 'exit 1'", RestartPolicy.ON_FAILURE);
    supervisor.start(repoId, "work", daemonId);
    awaitStatus(repoId, daemonId, DaemonStatus.RESTARTING);

    containerEvents.fireStopping(repoId, "work", true);

    awaitStatus(repoId, daemonId, DaemonStatus.STOPPED);
    // The pending relaunch (3s backoff) must have been cancelled — it never comes back.
    Thread.sleep(3500);
    assertEquals(
        DaemonStatus.STOPPED,
        instanceOf(repoId, daemonId).status(),
        "settling a RESTARTING instance cancels its pending relaunch");
  }

  @Test
  public void stopContainerDoesNotResurrectItsSettledDaemon() throws Exception {
    String repoId = repoWithWorkspace();
    String daemonId = createDaemon(repoId, "dev", "sleep 300", RestartPolicy.ON_FAILURE);
    supervisor.start(repoId, "work", daemonId);
    awaitStatus(repoId, daemonId, DaemonStatus.READY);
    String container = containers.containerName("work", repoId);

    // The regression: a deliberate stop under a live ON_FAILURE daemon used to be misread as a
    // crash
    // and the just-stopped container re-provisioned. The synchronous settle prevents it.
    workspaceService.stopContainer(repoId, "work");

    Thread.sleep(600); // several liveness intervals
    assertFalse(
        containers.exists(container), "the deliberately stopped container is not resurrected");
    WorkspaceDto dto =
        workspaceService.listWorkspaces(repoId).stream()
            .filter(w -> "work".equals(w.workspaceId()))
            .findFirst()
            .orElseThrow();
    assertEquals(
        WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus(), "the workspace stays STOPPED");
    assertEquals(
        DaemonStatus.STOPPED,
        instanceOf(repoId, daemonId).status(),
        "and its daemon stays STOPPED");
  }
}
