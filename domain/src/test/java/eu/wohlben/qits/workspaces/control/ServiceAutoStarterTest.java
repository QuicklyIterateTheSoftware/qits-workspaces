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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The container&#8594;service coupling under the pure-projection host (docs/issues/resolved/
 * 2026-07-25_host-side-service-supervision-should-move-to-daemon.md): a container-started event
 * (via the bootstrap pass-through to {@code WorkspaceReadyForServices}) registers a projection for
 * the workspace config's auto-start services and asks the daemon to start them, leaves the opt-out
 * ones untouched, tolerates an already-running instance without blocking the rest, and is gated by
 * the kill switch (off by default in tests — this class re-enables it via its profile; the
 * kill-switch case is {@link ServiceAutoStartKillSwitchTest}). The daemon then owns the lifecycle;
 * a {@link FakeWorkspaceServiceDriver} records the host's start requests and plays the lifecycle
 * events back. Auto-start flags are config-declared, staged into the {@link
 * FakeWorkspaceConfigReader}.
 */
@QuarkusTest
@TestProfile(ServiceAutoStarterTest.TestProfile.class)
public class ServiceAutoStarterTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-service-autostart-test-repos");
        return Map.of(
            "qits.repositories.data-dir",
            tempDir.toString(),
            "qits.services.autostart-enabled",
            "true");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject FakeWorkspaceServiceDriver driver;
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;

  /** The staged config is per-test state: replace it wholesale (never drop the other services). */
  private final List<QitsConfig.ServiceDecl> staged = new ArrayList<>();

  @BeforeEach
  void resetFakes() {
    staged.clear();
    configReader.clear(); // the fake is a shared singleton across this class's test methods
    driver.reset();
  }

  /** Clones the fixture and adds a lazy {@code work} workspace (no container yet). */
  private String repoWithWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("AutoStart Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  /** Add one auto-start/opt-out service to the workspace's staged config; returns its id. */
  private String createService(String repoId, String name, boolean autoStart) {
    staged.add(
        new QitsConfig.ServiceDecl(
            name,
            name,
            null,
            "sleep 300",
            null,
            null,
            autoStart,
            RestartPolicy.NEVER,
            0,
            "TERM",
            null,
            null,
            null));
    configReader.setConfig("work", new QitsConfig(null, null, null, staged, null));
    return name;
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(repoId, "work").stream()
        .filter(i -> i.definition().id().equals(serviceId))
        .findFirst()
        .orElse(null);
  }

  private ServiceInstanceDto awaitStatus(String repoId, String serviceId, ServiceStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    ServiceInstanceDto last = null;
    while (System.currentTimeMillis() < deadline) {
      last = instanceOf(repoId, serviceId);
      if (last != null && last.status() == expected) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last state: " + last);
  }

  @Test
  public void startedEventAsksTheDaemonToStartAutoStartsAndSkipsOptOuts() throws Exception {
    String repoId = repoWithWorkspace();
    String autoId = createService(repoId, "auto", true);
    String optOutId = createService(repoId, "manual-only", false);

    containerEvents.fireStarted(repoId, "work");

    // The auto-start service is registered as a projection (STARTING) and the daemon was asked to
    // start it...
    awaitStatus(repoId, autoId, ServiceStatus.STARTING);
    assertTrue(driver.started().contains("auto"), "the daemon was asked to start the auto-start");
    // ...while the opt-out service was never touched — effectiveServices still lists it, but as an
    // unstarted STOPPED placeholder.
    assertEquals(
        ServiceStatus.STOPPED,
        instanceOf(repoId, optOutId).status(),
        "opt-out must not auto-start");
    assertTrue(!driver.started().contains("manual-only"), "the opt-out is never asked to start");

    // The daemon streams it up; the host projects READY.
    driver.sink().onState(repoId, "work", "auto", "READY", null);
    awaitStatus(repoId, autoId, ServiceStatus.READY);
  }

  @Test
  public void alreadyLiveInstanceIsToleratedAndDoesNotBlockOthers() throws Exception {
    String repoId = repoWithWorkspace();
    String firstId = createService(repoId, "first", true);
    String secondId = createService(repoId, "second", true);

    // Start the first service manually and bring it READY, so the auto-start pass hits an
    // already-running instance.
    supervisor.start(repoId, "work", firstId);
    driver.sink().onState(repoId, "work", "first", "READY", null);
    ServiceInstanceDto firstReady = awaitStatus(repoId, firstId, ServiceStatus.READY);

    containerEvents.fireStarted(repoId, "work");

    // The second service still registers — the first's tolerated "already running" must not abort
    // the loop — and the first stays the single live instance (not relaunched).
    awaitStatus(repoId, secondId, ServiceStatus.STARTING);
    ServiceInstanceDto firstAfter = instanceOf(repoId, firstId);
    assertEquals(ServiceStatus.READY, firstAfter.status(), "the already-live service is untouched");
    assertEquals(
        firstReady.restartCount(),
        firstAfter.restartCount(),
        "the tolerated skip must not restart the running instance");
  }

  @Test
  public void aSingleStartedEventRegistersEachAutoStartExactlyOnce() throws Exception {
    // The projection host never re-enters container provisioning on start (unlike the retired tmux
    // supervisor), so a single container-started event yields exactly one start request, no storm.
    String repoId = repoWithWorkspace();
    String autoId = createService(repoId, "auto", true);

    containerEvents.fireStarted(repoId, "work");
    awaitStatus(repoId, autoId, ServiceStatus.STARTING);

    Thread.sleep(300); // let any (wrongful) re-fire happen
    assertEquals(
        1,
        driver.started().stream().filter("auto"::equals).count(),
        "a single started event asks the daemon to start the service exactly once");
  }

  @Test
  public void manualStartStillWorksWithAutoStartOff() throws Exception {
    // Auto-start is opt-out per service, but manual start/stop stays available for any service.
    String repoId = repoWithWorkspace();
    String optOutId = createService(repoId, "manual-only", false);

    supervisor.start(repoId, "work", optOutId);
    assertTrue(driver.started().contains("manual-only"), "a manual start asks the daemon");
    driver.sink().onState(repoId, "work", "manual-only", "READY", null);
    awaitStatus(repoId, optOutId, ServiceStatus.READY);
  }
}
