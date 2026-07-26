package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.control.ProjectService;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.RepositoryService;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.control.WorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.control.RestartPolicy;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The host {@code ServiceSupervisor} is a pure <b>projection</b> of the in-container daemon
 * (docs/epics/qits-workspace-daemon/ Part 4, and the collapse in docs/issues/resolved/
 * 2026-07-25_host-side-service-supervision-should-move-to-daemon.md): it issues only manual
 * start/stop over the {@link WorkspaceServiceDriver} and otherwise mirrors the lifecycle events the
 * daemon streams onto its display state machine, running no process itself. A {@link
 * FakeWorkspaceServiceDriver} plays the daemon — it records the host's start/stop calls and exposes
 * the sink the host subscribed, so a test feeds transitions and asserts the projection (status,
 * proxy target, singleton rule). Definitions are config-declared, staged into the {@link
 * FakeWorkspaceConfigReader} keyed by their {@code id:}.
 */
@QuarkusTest
public class ServiceSupervisorProjectionTest {

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject ServiceSupervisor supervisor;
  @Inject FakeWorkspaceServiceDriver driver;

  @BeforeEach
  void resetFakes() {
    // Both fakes are shared singletons across this class's test methods.
    driver.reset();
    configReader.clear();
  }

  /** Clone the fixture, add a {@code work} workspace, and provision its (fake) container. */
  private String repoWithWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Service Projection", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    // The proxy origin resolves against a real (fake) container; provision it up front.
    workspaceService.ensureContainer(repo.id, "work");
    return repo.id;
  }

  private String createService(String name, String script) {
    return createService(name, script, null);
  }

  private String createService(String name, String script, QitsConfig.WebViewDecl webView) {
    configReader.setConfig(
        "work",
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    name,
                    name,
                    null,
                    script,
                    null,
                    null,
                    null,
                    RestartPolicy.ON_FAILURE,
                    3,
                    "TERM",
                    null,
                    webView,
                    null)),
            null));
    return name;
  }

  private ServiceInstanceDto instanceOf(String repoId, String serviceId) {
    return supervisor.effectiveServices(repoId, "work").stream()
        .filter(d -> d.definition().id().equals(serviceId))
        .findFirst()
        .orElseThrow();
  }

  private ServiceStatus statusOf(String repoId, String serviceId) {
    return instanceOf(repoId, serviceId).status();
  }

  @Test
  void manualStartAsksDaemonThenProjectsLifecycle() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService("dev", "sleep 30");

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
  void restartingEventBumpsTheProjectedRestartCount() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService("flaky", "false");
    supervisor.start(repoId, "work", id);

    var sink = driver.sink();
    sink.onState(repoId, "work", "flaky", "CRASHED", 1);
    sink.onState(repoId, "work", "flaky", "RESTARTING", 1);
    assertEquals(ServiceStatus.RESTARTING, statusOf(repoId, id));
    assertEquals(1, instanceOf(repoId, id).restartCount(), "a RESTARTING event is one restart");
  }

  @Test
  void stopAsksDaemonToSignalThenSettlesOnTheStoppedEvent() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService("dev", "sleep 30");
    supervisor.start(repoId, "work", id);
    driver.sink().onState(repoId, "work", "dev", "READY", null);

    supervisor.stop(repoId, "work", id);
    assertTrue(driver.signalled().contains("dev"), "stop asks the daemon to signal the service");
    // The daemon owns the process — the host stays READY until the daemon reports it gone.
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));

    driver.sink().onState(repoId, "work", "dev", "STOPPED", 0);
    assertEquals(ServiceStatus.STOPPED, statusOf(repoId, id));
  }

  @Test
  void adoptsRunningServiceFromEventWithoutAStart() throws Exception {
    // No start() — the daemon re-reports a running service on reconnect (post qits-restart); the
    // host adopts it from the event, event-driven, with no /proc or tmux probe.
    String repoId = repoWithWorkspace();
    String id = createService("dev", "sleep 30");

    driver.sink().onState(repoId, "work", "dev", "READY", null);
    assertEquals(ServiceStatus.READY, statusOf(repoId, id));
    assertTrue(driver.started().isEmpty(), "adoption issues no start instruction");
  }

  @Test
  void oneRunningInstancePerWorkspaceAndService() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService("single", "sleep 30");

    supervisor.start(repoId, "work", id); // now STARTING (live) — a second start is rejected
    assertThrows(
        BadRequestException.class,
        () -> supervisor.start(repoId, "work", id),
        "second start of the same (workspace, service) must be rejected");
  }

  @Test
  void webViewableServiceExposesProxyTargetAndPath() throws Exception {
    String repoId = repoWithWorkspace();
    String id =
        createService("web", "sleep 30", new QitsConfig.WebViewDecl(8123, "greeting", "app"));

    supervisor.start(repoId, "work", id);
    driver.sink().onState(repoId, "work", "web", "READY", null);

    ServiceInstanceDto ready = instanceOf(repoId, id);
    assertEquals(ServiceStatus.READY, ready.status());
    assertEquals(
        "/service/work/" + id + "/app/",
        ready.proxyPath(),
        "the served base is the proxy prefix plus the basePath (entryPath is not part of it)");
    assertEquals("greeting", ready.definition().webView().entryPath());

    var target = supervisor.proxyTarget("work", id);
    assertTrue(target.isPresent(), "a live web-viewable service has a proxy target");
    assertEquals(ServiceStatus.READY, target.get().status());
    // FakeContainerRuntime resolves the target to 127.0.0.1 + the container port; the real runtime
    // returns the container's DNS name on the shared network.
    assertEquals(new ProxyOrigin("127.0.0.1", 8123), target.get().origin());

    assertTrue(
        supervisor.proxyTarget("work", "no-such-service").isEmpty(),
        "unknown service id resolves to nothing");

    supervisor.stop(repoId, "work", id);
    driver.sink().onState(repoId, "work", "web", "STOPPED", 0);
    var stopped = supervisor.proxyTarget("work", id);
    assertTrue(stopped.isPresent(), "a stopped instance still resolves (the proxy 502s on it)");
    assertEquals(ServiceStatus.STOPPED, stopped.get().status());
  }

  @Test
  void serviceWithoutWebViewHasNoProxyTargetOrPath() throws Exception {
    String repoId = repoWithWorkspace();
    String id = createService("plain", "sleep 30");

    supervisor.start(repoId, "work", id);
    driver.sink().onState(repoId, "work", "plain", "READY", null);

    assertEquals(null, instanceOf(repoId, id).proxyPath(), "no web-view config, no proxy path");
    assertTrue(supervisor.proxyTarget("work", id).isEmpty());
  }
}
