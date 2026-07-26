package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.ProjectService;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.RepositoryService;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.control.RestartPolicy;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code qits.services.autostart-enabled=false} kill switch suppresses the whole coupling: a
 * container-started event brings up nothing, even for a default (auto-start) daemon.
 */
@QuarkusTest
@TestProfile(ServiceAutoStartKillSwitchTest.TestProfile.class)
public class ServiceAutoStartKillSwitchTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-autostart-killswitch-repos");
        return Map.of(
            "qits.repositories.data-dir",
            tempDir.toString(),
            "qits.services.autostart-enabled",
            "false");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;

  @Test
  public void killSwitchSuppressesAutoStart() throws Exception {
    driver.reset();
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("KillSwitch Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    String serviceId = "auto";
    configReader.setConfig(
        "work",
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    serviceId,
                    "auto",
                    null,
                    "sleep 300",
                    null,
                    null,
                    true, // autoStart, but the
                    // kill switch overrides it
                    RestartPolicy.NEVER,
                    0,
                    "TERM",
                    null,
                    null,
                    null)),
            null));

    containerEvents.fireStarted(repo.id, "work");

    // Give the async observer ample time to (not) act, then confirm nothing launched — the service
    // is still listed, but as an unstarted STOPPED placeholder.
    Thread.sleep(1500);
    ServiceInstanceDto instance =
        supervisor.effectiveServices(repo.id, "work").stream()
            .filter(i -> i.definition().id().equals(serviceId))
            .findFirst()
            .orElseThrow();
    assertEquals(
        ServiceStatus.STOPPED,
        instance.status(),
        "kill switch off ⇒ no auto-start, service stays STOPPED");
    assertTrue(driver.started().isEmpty(), "kill switch off ⇒ the daemon was never asked to start");
  }
}
