package eu.wohlben.qits.domain.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceServiceDriver;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.service.dto.ServiceInstanceDto;
import eu.wohlben.qits.domain.service.entity.RestartPolicy;
import eu.wohlben.qits.domain.service.entity.ServiceStatus;
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
 * The {@code qits.services.autostop-enabled=false} kill switch suppresses the settle coupling: a
 * container-stopping event settles nothing, leaving the service to the daemon's own machinery.
 */
@QuarkusTest
@TestProfile(ServiceSettleKillSwitchTest.TestProfile.class)
public class ServiceSettleKillSwitchTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-settle-killswitch-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.services.autostop-enabled", "false",
            "qits.services.autostart-enabled", "false");
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
  public void killSwitchSuppressesSettle() throws Exception {
    driver.reset();
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Settle KillSwitch Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    String serviceId = "dev";
    configReader.setConfig(
        "work",
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    serviceId,
                    "dev",
                    null,
                    "sleep 300",
                    null,
                    null,
                    false,
                    RestartPolicy.ON_FAILURE,
                    3,
                    "TERM",
                    null,
                    null,
                    null)),
            null));
    supervisor.start(repo.id, "work", serviceId);
    driver.sink().onState(repo.id, "work", "dev", "READY", null);

    // The settle event fires, but the kill switch means the coupler ignores it: the service (still
    // owned by the live daemon) stays READY rather than being settled STOPPED.
    containerEvents.fireStopping(repo.id, "work", true);

    Thread.sleep(300);
    assertEquals(
        ServiceStatus.READY,
        instanceOf(repo.id, serviceId).status(),
        "kill switch off ⇒ the stopping event settles nothing");
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(repoId, "work").stream()
        .filter(i -> i.definition().id().equals(serviceId))
        .findFirst()
        .orElseThrow();
  }
}
