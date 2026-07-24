package eu.wohlben.qits.domain.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.service.dto.ServiceInstanceDto;
import eu.wohlben.qits.domain.service.entity.ServiceStatus;
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
 * The container&#8594;daemon coupling: a container-started event (via the bootstrap pass-through to
 * {@code WorkspaceReadyForDaemons}) brings a workspace config's auto-start services up (and leaves
 * the opt-out ones alone), tolerates an already-running instance without blocking the rest, and is
 * gated by the kill switch (which is off by default in tests — this class re-enables it via its
 * profile; the kill-switch case is {@link ServiceAutoStartKillSwitchTest}). Auto-start flags are
 * config-declared, staged into the {@link FakeWorkspaceConfigReader}.
 */
@QuarkusTest
@TestProfile(ServiceAutoStarterTest.TestProfile.class)
public class ServiceAutoStarterTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-autostart-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.services.autostart-enabled", "true",
            // Fast grace-READY + crash detection so the test doesn't wait on prod timings.
            "qits.services.ready-grace-ms", "300",
            "qits.services.liveness-poll-ms", "150");
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
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;

  /** The staged config is per-test state: replace it wholesale (never drop the other daemons). */
  private final List<QitsConfig.ServiceDecl> staged = new ArrayList<>();

  @BeforeEach
  void resetConfig() {
    staged.clear();
    configReader.clear(); // the fake is a shared singleton across this class's test methods
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
  private String createDaemon(String repoId, String name, boolean autoStart) {
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

  private ServiceInstanceDto instanceOf(String repoId, String daemonId) {
    return supervisor.effectiveDaemons(repoId, "work").stream()
        .filter(i -> i.daemon().id().equals(daemonId))
        .findFirst()
        .orElse(null);
  }

  private ServiceInstanceDto awaitStatus(String repoId, String daemonId, ServiceStatus expected)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    ServiceInstanceDto last = null;
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
  public void startedEventLaunchesAutoStartDaemonsAndSkipsOptOuts() throws Exception {
    String repoId = repoWithWorkspace();
    String autoId = createDaemon(repoId, "auto", true);
    String optOutId = createDaemon(repoId, "manual-only", false);

    containerEvents.fireStarted(repoId, "work");

    // The auto-start daemon comes up unattended (grace-period readiness, no pattern)...
    awaitStatus(repoId, autoId, ServiceStatus.READY);
    // ...while the opt-out daemon was never launched. The observer processes both in one
    // synchronous
    // pass, so by the time `auto` is READY, `manual-only` was already (deliberately) skipped —
    // effectiveDaemons still lists it, but as an unstarted STOPPED placeholder (no command).
    ServiceInstanceDto optOut = instanceOf(repoId, optOutId);
    assertEquals(ServiceStatus.STOPPED, optOut.status(), "opt-out daemon must not auto-start");
    assertNull(optOut.commandId(), "opt-out daemon was never launched");
  }

  @Test
  public void alreadyLiveInstanceIsToleratedAndDoesNotBlockOthers() throws Exception {
    String repoId = repoWithWorkspace();
    String firstId = createDaemon(repoId, "first", true);
    String secondId = createDaemon(repoId, "second", true);

    // Start the first daemon manually, so the auto-start pass hits an already-running instance.
    supervisor.start(repoId, "work", firstId);
    ServiceInstanceDto firstReady = awaitStatus(repoId, firstId, ServiceStatus.READY);

    containerEvents.fireStarted(repoId, "work");

    // The second daemon still launches — the first's tolerated "already running" must not abort the
    // loop — and the first stays the single live instance (not relaunched).
    awaitStatus(repoId, secondId, ServiceStatus.READY);
    ServiceInstanceDto firstAfter = instanceOf(repoId, firstId);
    assertEquals(ServiceStatus.READY, firstAfter.status(), "the already-live daemon is untouched");
    assertEquals(
        firstReady.restartCount(),
        firstAfter.restartCount(),
        "the tolerated skip must not restart the running instance");
  }

  @Test
  public void reentrancyTerminates() throws Exception {
    // Auto-start -> supervisor.start -> beginDaemonRun -> ensureContainer hits the already-RUNNING
    // short-circuit, which does not fire the event. If it did, this would recurse; a plain READY
    // (with a single launch) is the observable proof the cycle terminated.
    String repoId = repoWithWorkspace();
    String autoId = createDaemon(repoId, "auto", true);

    containerEvents.fireStarted(repoId, "work");

    ServiceInstanceDto ready = awaitStatus(repoId, autoId, ServiceStatus.READY);
    assertEquals(0, ready.restartCount(), "a single clean launch, no reentrant relaunch storm");
  }

  @Test
  public void manualStartStillWorksWithAutoStartOff() throws Exception {
    // Auto-start is opt-out per daemon, but manual start/stop stays available for any daemon.
    String repoId = repoWithWorkspace();
    String optOutId = createDaemon(repoId, "manual-only", false);

    ServiceInstanceDto started = supervisor.start(repoId, "work", optOutId);
    assertTrue(started != null);
    awaitStatus(repoId, optOutId, ServiceStatus.READY);
  }
}
