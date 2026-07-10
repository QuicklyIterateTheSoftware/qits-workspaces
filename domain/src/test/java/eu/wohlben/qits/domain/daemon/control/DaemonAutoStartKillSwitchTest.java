package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * The {@code qits.daemons.autostart-enabled=false} kill switch suppresses the whole coupling: a
 * container-started event brings up nothing, even for a default (auto-start) daemon.
 */
@QuarkusTest
@TestProfile(DaemonAutoStartKillSwitchTest.TestProfile.class)
public class DaemonAutoStartKillSwitchTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-autostart-killswitch-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
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
  public void killSwitchSuppressesAutoStart() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("KillSwitch Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    String daemonId =
        repositoryDaemonService.create(
                repo.id,
                "auto",
                null,
                "sleep 300",
                null,
                "TERM",
                RestartPolicy.NEVER,
                true, // autoStart, but the kill switch overrides it
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null)
            .id;

    containerEvents.fireStarted(repo.id, "work");

    // Give the async observer ample time to (not) act, then confirm nothing launched — the daemon
    // is still listed, but as an unstarted STOPPED placeholder (no command).
    Thread.sleep(1500);
    DaemonInstanceDto instance =
        supervisor.effectiveDaemons(repo.id, "work").stream()
            .filter(i -> i.daemon().id().equals(daemonId))
            .findFirst()
            .orElseThrow();
    assertEquals(
        DaemonStatus.STOPPED,
        instance.status(),
        "kill switch off ⇒ no auto-start, daemon stays STOPPED");
    assertNull(instance.commandId(), "kill switch off ⇒ the daemon was never launched");
  }
}
