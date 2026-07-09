package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code qits.daemons.autostop-enabled=false} kill switch suppresses the settle coupling: a
 * container-stopping event settles nothing, leaving the daemon to the ordinary machinery.
 */
@QuarkusTest
@TestProfile(DaemonSettleKillSwitchTest.TestProfile.class)
public class DaemonSettleKillSwitchTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-settle-killswitch-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.daemons.autostop-enabled", "false",
            "qits.daemons.autostart-enabled", "false",
            "qits.daemons.ready-grace-ms", "300",
            "qits.daemons.liveness-poll-ms", "150");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject RepositoryDaemonService repositoryDaemonService;
  @Inject DaemonSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;

  @Test
  public void killSwitchSuppressesSettle() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Settle KillSwitch Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    String daemonId =
        repositoryDaemonService.create(
                repo.id,
                "dev",
                null,
                "sleep 300",
                null,
                "TERM",
                RestartPolicy.ON_FAILURE,
                false,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                null)
            .id;
    supervisor.start(repo.id, "work", daemonId);
    // Wait for READY.
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline
        && instanceOf(repo.id, daemonId).status() != DaemonStatus.READY) {
      Thread.sleep(50);
    }

    // The settle event fires, but the kill switch means the coupler ignores it: the daemon (still
    // alive in its container) stays READY rather than being settled STOPPED.
    containerEvents.fireStopping(repo.id, "work", true);

    Thread.sleep(600);
    assertEquals(
        DaemonStatus.READY,
        instanceOf(repo.id, daemonId).status(),
        "kill switch off ⇒ the stopping event settles nothing");
  }

  private DaemonInstanceDto instanceOf(String repoId, String daemonId) {
    return supervisor.effectiveDaemons(repoId, "work").stream()
        .filter(i -> i.daemon().id().equals(daemonId))
        .findFirst()
        .orElseThrow();
  }
}
