package eu.wohlben.qits.domain.bootstrap.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.domain.bootstrap.dto.BootstrapRunDto;
import eu.wohlben.qits.domain.bootstrap.entity.BootstrapOutcome;
import eu.wohlben.qits.domain.command.entity.Command;
import eu.wohlben.qits.domain.command.entity.CommandStatus;
import eu.wohlben.qits.domain.daemon.control.DaemonSupervisor;
import eu.wohlben.qits.domain.daemon.control.RepositoryDaemonService;
import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceReadyForDaemonsRecorder;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bootstrap chain end-to-end against the {@code FakeContainerRuntime} (real host processes, no
 * docker): a fresh provision runs the commands strictly in order before daemon auto-start; the
 * check script skips without a command row; a failure aborts the rest AND withholds {@code
 * WorkspaceReadyForDaemons} (auto-start daemons stay down); a restart-shaped event passes straight
 * through without re-running; and the manual chain re-run is the recovery path that releases
 * auto-start on success. Kill-switch coverage is {@link WorkspaceBootstrapKillSwitchTest}.
 */
@QuarkusTest
@TestProfile(WorkspaceBootstrapRunnerTest.TestProfile.class)
public class WorkspaceBootstrapRunnerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-bootstrap-runner-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.daemons.autostart-enabled", "true",
            "qits.daemons.ready-grace-ms", "300",
            "qits.daemons.liveness-poll-ms", "150",
            // Generous enough for a PTY spawn, tight enough that the sleep-forever timeout test
            // finishes fast.
            "qits.bootstrap.await-timeout-ms", "8000");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 20_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
  @Inject BootstrapCommandService bootstrapCommandService;
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceBootstrapRunner runner;
  @Inject RepositoryDaemonService repositoryDaemonService;
  @Inject DaemonSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;
  @Inject WorkspaceReadyForDaemonsRecorder readyRecorder;

  private Path scratch;

  @BeforeEach
  void setUp() throws Exception {
    readyRecorder.clear();
    scratch = Files.createTempDirectory("qits-bootstrap-runner-scratch");
  }

  /** Clones the fixture and adds a lazy {@code work} workspace (no container yet). */
  private String repoWithWorkspace(String name) throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create(name, null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  private String command(String repoId, String name, String execute, String check) {
    return bootstrapCommandService.create(repoId, name, null, execute, check, null, null).id;
  }

  private String autoStartDaemon(String repoId) {
    return repositoryDaemonService.create(
            repoId,
            "dev-server",
            null,
            "sleep 300",
            null,
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
            null)
        .id;
  }

  private BootstrapRunDto lastRun(String repoId, String commandId) {
    return bootstrapRunService.listForWorkspace(repoId, "work").stream()
        .filter(r -> r.bootstrapCommandId().equals(commandId))
        .findFirst()
        .orElse(null);
  }

  private <T> T await(Supplier<T> probe, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    T last = null;
    while (System.currentTimeMillis() < deadline) {
      last = probe.get();
      if (last != null) {
        return last;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + what + "; last: " + last);
  }

  private BootstrapRunDto awaitOutcome(String repoId, String commandId, BootstrapOutcome expected)
      throws InterruptedException {
    return await(
        () -> {
          BootstrapRunDto run = lastRun(repoId, commandId);
          return run != null && run.outcome() == expected ? run : null;
        },
        expected + " for " + commandId);
  }

  private DaemonInstanceDto daemonInstance(String repoId, String daemonId) {
    return supervisor.effectiveDaemons(repoId, "work").stream()
        .filter(i -> i.daemon().id().equals(daemonId))
        .findFirst()
        .orElse(null);
  }

  private DaemonInstanceDto awaitDaemonStatus(String repoId, String daemonId, DaemonStatus expected)
      throws InterruptedException {
    return await(
        () -> {
          DaemonInstanceDto i = daemonInstance(repoId, daemonId);
          return i != null && i.status() == expected ? i : null;
        },
        expected + " for daemon " + daemonId);
  }

  private Command commandRow(String commandId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> Command.<Command>findByIdOptional(commandId).orElse(null));
  }

  @Test
  public void freshProvisionRunsChainInOrderBeforeDaemonAutoStart() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Fresh");
    Path orderLog = scratch.resolve("order.log");
    String firstId = command(repoId, "first", "echo first >> " + orderLog, null);
    String secondId = command(repoId, "second", "echo second >> " + orderLog, null);
    String daemonId = autoStartDaemon(repoId);

    // First access provisions the container (fresh) and triggers the chain, then auto-start.
    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto first = awaitOutcome(repoId, firstId, BootstrapOutcome.SUCCEEDED);
    BootstrapRunDto second = awaitOutcome(repoId, secondId, BootstrapOutcome.SUCCEEDED);
    awaitDaemonStatus(repoId, daemonId, DaemonStatus.READY);

    assertEquals(
        List.of("first", "second"),
        Files.readAllLines(orderLog),
        "commands ran strictly in orderIndex order");
    assertEquals(0, first.exitCode());
    assertNotNull(first.commandId(), "each execute leaves a Command audit row");
    Command audit = commandRow(first.commandId());
    assertNotNull(audit);
    assertEquals(CommandStatus.EXITED, audit.status);
    assertNull(audit.actionId, "bootstrap runs are not backed by an action");
    assertEquals("first", audit.actionName);
    assertNotNull(second.commandId());
    assertEquals(1, readyRecorder.countFor(repoId, "work"), "chain success released auto-start");
  }

  @Test
  public void failingCheckSkipsWithoutCommandRowAndChainContinues() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Skip");
    Path marker = scratch.resolve("ran.log");
    // check exits non-zero → "not needed" → SKIPPED, execute never runs.
    String skippedId = command(repoId, "skipped", "echo skipped >> " + marker, "exit 1");
    String ranId = command(repoId, "ran", "echo ran >> " + marker, "exit 0");

    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto skipped = awaitOutcome(repoId, skippedId, BootstrapOutcome.SKIPPED);
    awaitOutcome(repoId, ranId, BootstrapOutcome.SUCCEEDED);
    assertNull(skipped.commandId(), "a skip leaves no Command row");
    assertNull(skipped.exitCode());
    assertEquals(List.of("ran"), Files.readAllLines(marker), "only the checked-in command ran");
    assertEquals(1, readyRecorder.countFor(repoId, "work"), "skips count as chain success");
  }

  @Test
  public void failureAbortsChainAndWithholdsDaemonAutoStart() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Fail");
    Path marker = scratch.resolve("never.log");
    String failingId = command(repoId, "failing", "exit 7", null);
    String neverId = command(repoId, "never", "echo never >> " + marker, null);
    String daemonId = autoStartDaemon(repoId);

    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto failed = awaitOutcome(repoId, failingId, BootstrapOutcome.FAILED);
    assertEquals(7, failed.exitCode());
    assertNotNull(failed.commandId(), "the failed run's log is linked for debugging");

    // The rest of the chain was aborted and auto-start withheld. Give the async pipeline a
    // moment to prove the negative.
    Thread.sleep(1_000);
    assertNull(lastRun(repoId, neverId), "commands after the failure never ran");
    assertFalse(Files.exists(marker));
    assertEquals(0, readyRecorder.countFor(repoId, "work"), "a failed chain never fires ready");
    DaemonInstanceDto daemon = daemonInstance(repoId, daemonId);
    assertEquals(DaemonStatus.STOPPED, daemon.status(), "auto-start daemon stays down");
    assertNull(daemon.commandId(), "the daemon was never launched");
  }

  @Test
  public void restartShapedEventPassesStraightThroughWithoutRunning() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Restart");
    String commandId = command(repoId, "install", "echo hi", null);

    // A restart of an Exited container (freshProvision=false): no chain, straight to auto-start.
    containerEvents.fireStarted(repoId, "work");

    await(
        () -> readyRecorder.countFor(repoId, "work") >= 1 ? Boolean.TRUE : null,
        "the pass-through ready event");
    assertNull(lastRun(repoId, commandId), "a plain restart does not re-run the chain");
  }

  @Test
  public void manualChainRerunAfterFailureIsTheRecoveryPath() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Recover");
    Path flag = scratch.resolve("fixed.flag");
    // Fails until the flag file exists — the "broken then fixed" bootstrap step.
    String flakyId = command(repoId, "flaky", "test -f " + flag, null);
    String daemonId = autoStartDaemon(repoId);

    workspaceService.ensureContainer(repoId, "work");
    awaitOutcome(repoId, flakyId, BootstrapOutcome.FAILED);
    assertEquals(0, readyRecorder.countFor(repoId, "work"));

    // Fix the world, then re-run the whole chain from the workspace surface.
    Files.writeString(flag, "fixed");
    runner.runChainAsync(repoId, "work");

    awaitOutcome(repoId, flakyId, BootstrapOutcome.SUCCEEDED);
    await(
        () -> readyRecorder.countFor(repoId, "work") >= 1 ? Boolean.TRUE : null,
        "recovery releases auto-start");
    awaitDaemonStatus(repoId, daemonId, DaemonStatus.READY);
  }

  @Test
  public void singleCommandRerunRecordsItsOutcomeOnly() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Single");
    Path marker = scratch.resolve("single.log");
    String targetId = command(repoId, "target", "echo target >> " + marker, null);
    String otherId = command(repoId, "other", "echo other >> " + marker, null);

    // A single-command re-run touches only its own command — deliberately, even when its
    // ensureContainer fresh-provisions the container here: the run holds the in-flight guard, so
    // the
    // provision's container-started event yields to it and the rest of the chain does not run.
    // (Full
    // bootstrap + daemon auto-start is the fresh-provision/"Run all" job, not this trigger's.)
    runner.runSingleAsync(repoId, "work", targetId);

    awaitOutcome(repoId, targetId, BootstrapOutcome.SUCCEEDED);
    Thread.sleep(500);
    assertNull(lastRun(repoId, otherId), "a single re-run touches only its command");
    assertEquals(List.of("target"), Files.readAllLines(marker));
  }

  @Test
  public void concurrentManualRunsAreRejected() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Conflict");
    command(repoId, "slow", "sleep 3", null);

    runner.runChainAsync(repoId, "work");
    assertThrows(
        BadRequestException.class,
        () -> runner.runChainAsync(repoId, "work"),
        "a second run while one is in flight is rejected");
  }

  @Test
  public void timedOutExecuteIsTerminatedAndFailsTheChain() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Timeout");
    // Sleeps far past qits.bootstrap.await-timeout-ms (8s in this profile).
    String hungId = command(repoId, "hung", "sleep 300", null);

    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto run = awaitOutcome(repoId, hungId, BootstrapOutcome.FAILED);
    assertNull(run.exitCode(), "a timeout has no real exit code");
    assertNotNull(run.commandId());
    // The straggler is terminated (unlike launchAndAwait's leave-running policy).
    await(
        () -> {
          Command audit = commandRow(run.commandId());
          return audit != null && audit.status == CommandStatus.TERMINATED ? audit : null;
        },
        "the timed-out command to be terminated");
    assertEquals(0, readyRecorder.countFor(repoId, "work"), "a timed-out chain withholds ready");
  }

  @Test
  public void repositoryWithRecordedBootstrapRunDeletesCleanly() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Delete");
    String cmdId = command(repoId, "install", "echo hi", null);

    workspaceService.ensureContainer(repoId, "work");
    awaitOutcome(repoId, cmdId, BootstrapOutcome.SUCCEEDED); // a workspace_bootstrap_run row exists

    // Deleting the repository cascade-deletes its workspace rows; without `on delete cascade` on
    // the
    // bootstrap-run FK this fails with a referential-integrity violation the moment a run is
    // recorded (the V32 command_agent_session bug class).
    repositoryService.delete(repoId);

    assertThrows(
        eu.wohlben.qits.domain.error.NotFoundException.class,
        () -> repositoryService.get(repoId),
        "the repository (and its cascaded bootstrap-run rows) is gone");
  }
}
