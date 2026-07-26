package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import eu.wohlben.qits.workspaces.entity.BootstrapOutcome;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.dto.ServiceInstanceDto;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The host bootstrap <b>wiring</b> against the {@code FakeWorkspaceBootstrapDriver} (which plays
 * the daemon: it parses the fake checkout's committed {@code .qits-config.yml} and runs each step
 * through {@code FakeContainerRuntime} — real host processes, no docker): a fresh provision awaits
 * the daemon's chain and records outcomes strictly in order before service auto-start; the check
 * script skips; a failure aborts the rest AND withholds {@code WorkspaceReadyForServices}
 * (auto-start services stay down); a restart-shaped event passes straight through without
 * re-running; and the manual chain re-run is the recovery path that releases auto-start on success.
 * The chain <b>semantics</b> (order, check-skip, fail-fast, timeout-terminate) are the daemon
 * module's {@code BootstrapRunnerTest}; bootstrap steps run in the container now, so they no longer
 * leave host {@code Command} audit rows (their live output is the {@code bootstrap:<name>} process
 * segment). Kill-switch coverage is {@link WorkspaceBootstrapKillSwitchTest}.
 *
 * <p>Staging: the chain is committed as {@code .qits-config.yml} on {@code master} before the
 * workspace is forked (so the provision-triggered — asynchronous — chain sees it deterministically;
 * a file written into the checkout afterwards would race the async observer). Auto-start services
 * are config-declared too, staged into the {@link FakeWorkspaceConfigReader}. {@code BootstrapRun}
 * rows are keyed by the step NAME, so {@code bootstrapCommandId} equals the step name throughout.
 *
 * <p>Two cross-test hygiene rules this class follows because the app (and the fakes) are shared
 * across its methods: (1) every staged service id is unique per test — {@code FakeContainerRuntime}
 * keys service sessions by id host-wide, so a leaked {@code sleep 300} session from one test would
 * be <em>adopted</em> by the next test's {@code effectiveServices} probe under a reused id; (2)
 * every test whose pipeline fires {@code WorkspaceReadyForServices} drains the async coupler pass
 * (by awaiting its own auto-start service's STARTING — the projection host's observable that the
 * coupler ran) before returning — otherwise the late pass reads the <em>next</em> test's staged
 * config (the reader is keyed by workspace slug, not repo) and starts a service for the wrong repo.
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
            "qits.services.autostart-enabled", "true",
            // The host's chain-await timeout (the fake driver runs the chain synchronously, so this
            // only bounds a hung await).
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
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceBootstrapRunner runner;
  @Inject FakeWorkspaceConfigReader configReader;
  @Inject ServiceSupervisor supervisor;
  @Inject WorkspaceContainerEventPublisher containerEvents;
  @Inject WorkspaceReadyForServicesRecorder readyRecorder;
  @Inject GitExecutor git;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private Path scratch;

  @BeforeEach
  void setUp() throws Exception {
    readyRecorder.clear();
    configReader.clear(); // the fake is a shared singleton across this class's test methods
    scratch = Files.createTempDirectory("qits-bootstrap-runner-scratch");
  }

  /**
   * Clones the fixture, commits {@code configYaml} as {@code .qits-config.yml} on master (when
   * given), and adds a lazy {@code work} workspace forked off that master (no container yet).
   */
  private String repoWithWorkspace(String name, String configYaml) throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create(name, null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    if (configYaml != null) {
      commitConfig(repo.id, configYaml);
    }
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  /** Commit {@code .qits-config.yml} onto the origin's master, so workspace forks carry it. */
  private void commitConfig(String repoId, String yaml) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin");
    Path worktree = Files.createTempDirectory("qits-config-commit");
    git.exec(null, "git", "clone", origin.toString(), worktree.toString());
    git.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    git.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(worktree.resolve(".qits-config.yml"), yaml);
    git.exec(worktree.toFile(), "git", "add", ".qits-config.yml");
    git.exec(worktree.toFile(), "git", "commit", "-m", "stage qits config");
    git.exec(worktree.toFile(), "git", "push", "origin", "HEAD:master");
  }

  /** A bootstrap chain YAML (version 1) from {@code name=execute[=check]} tuples. */
  private static String chainYaml(String... steps) {
    StringBuilder yaml = new StringBuilder("version: 1\nbootstrap:\n");
    for (String step : steps) {
      String[] parts = step.split("=", 3);
      yaml.append("  - name: '").append(parts[0]).append("'\n");
      yaml.append("    execute: '").append(parts[1]).append("'\n");
      if (parts.length > 2) {
        yaml.append("    check: '").append(parts[2]).append("'\n");
      }
    }
    return yaml.toString();
  }

  /** Stage the workspace's auto-start dev server in the fake config reader; returns its id. */
  private String autoStartService(String id) {
    configReader.setConfig(
        "work",
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    id,
                    id,
                    null,
                    "sleep 300",
                    null,
                    null,
                    true,
                    RestartPolicy.NEVER,
                    0,
                    "TERM",
                    null,
                    null,
                    null)),
            null));
    return id;
  }

  private BootstrapRunDto lastRun(String repoId, String stepName) {
    return bootstrapRunService.listForWorkspace(repoId, "work").stream()
        .filter(r -> r.bootstrapCommandId().equals(stepName))
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

  private BootstrapRunDto awaitOutcome(String repoId, String stepName, BootstrapOutcome expected)
      throws InterruptedException {
    return await(
        () -> {
          BootstrapRunDto run = lastRun(repoId, stepName);
          return run != null && run.outcome() == expected ? run : null;
        },
        expected + " for " + stepName);
  }

  private ServiceInstanceDto serviceInstance(String repoId, String serviceId) {
    return supervisor.effectiveServices(repoId, "work").stream()
        .filter(i -> i.definition().id().equals(serviceId))
        .findFirst()
        .orElse(null);
  }

  private ServiceInstanceDto awaitServiceStatus(
      String repoId, String serviceId, ServiceStatus expected) throws InterruptedException {
    return await(
        () -> {
          ServiceInstanceDto i = serviceInstance(repoId, serviceId);
          return i != null && i.status() == expected ? i : null;
        },
        expected + " for service " + serviceId);
  }

  @Test
  public void freshProvisionRunsChainInOrderBeforeServiceAutoStart() throws Exception {
    Path orderLog = scratch.resolve("order.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Fresh",
            chainYaml("first=echo first >> " + orderLog, "second=echo second >> " + orderLog));
    String serviceId = autoStartService("dev-fresh");

    // First access provisions the container (fresh) and triggers the chain, then auto-start.
    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto first = awaitOutcome(repoId, "first", BootstrapOutcome.SUCCEEDED);
    BootstrapRunDto second = awaitOutcome(repoId, "second", BootstrapOutcome.SUCCEEDED);
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);

    assertEquals(
        List.of("first", "second"),
        Files.readAllLines(orderLog),
        "commands ran strictly in declaration order");
    assertEquals(0, first.exitCode());
    assertNull(first.commandId(), "bootstrap steps run in-container — no host Command audit row");
    assertEquals(0, second.exitCode());
    assertEquals(1, readyRecorder.countFor(repoId, "work"), "chain success released auto-start");
  }

  @Test
  public void failingCheckSkipsWithoutCommandRowAndChainContinues() throws Exception {
    Path marker = scratch.resolve("ran.log");
    // check exits non-zero → "not needed" → SKIPPED, execute never runs.
    String repoId =
        repoWithWorkspace(
            "Bootstrap Skip",
            chainYaml(
                "skipped=echo skipped >> " + marker + "=exit 1",
                "ran=echo ran >> " + marker + "=exit 0"));
    String serviceId = autoStartService("dev-skip");

    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto skipped = awaitOutcome(repoId, "skipped", BootstrapOutcome.SKIPPED);
    awaitOutcome(repoId, "ran", BootstrapOutcome.SUCCEEDED);
    assertNull(skipped.commandId(), "a skip leaves no Command row");
    assertNull(skipped.exitCode());
    assertEquals(List.of("ran"), Files.readAllLines(marker), "only the checked-in command ran");
    assertEquals(1, readyRecorder.countFor(repoId, "work"), "skips count as chain success");
    // Drain the coupler's auto-start pass so no late event leaks into the next test.
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);
  }

  @Test
  public void failureAbortsChainAndWithholdsServiceAutoStart() throws Exception {
    Path marker = scratch.resolve("never.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Fail", chainYaml("failing=exit 7", "never=echo never >> " + marker));
    String serviceId = autoStartService("dev-fail");

    workspaceService.ensureContainer(repoId, "work");

    BootstrapRunDto failed = awaitOutcome(repoId, "failing", BootstrapOutcome.FAILED);
    assertEquals(7, failed.exitCode());

    // The rest of the chain was aborted and auto-start withheld. Give the async pipeline a
    // moment to prove the negative.
    Thread.sleep(1_000);
    assertNull(lastRun(repoId, "never"), "commands after the failure never ran");
    assertFalse(Files.exists(marker));
    assertEquals(0, readyRecorder.countFor(repoId, "work"), "a failed chain never fires ready");
    ServiceInstanceDto service = serviceInstance(repoId, serviceId);
    assertEquals(
        ServiceStatus.STOPPED,
        service.status(),
        "auto-start service stays down — a withheld ready never registered a projection");
  }

  @Test
  public void restartShapedEventPassesStraightThroughWithoutRunning() throws Exception {
    Path marker = scratch.resolve("installs.log");
    String repoId =
        repoWithWorkspace("Bootstrap Restart", chainYaml("install=echo installed >> " + marker));
    String serviceId = autoStartService("dev-restart");

    // A fresh provision runs the chain once (and auto-starts the daemon).
    workspaceService.ensureContainer(repoId, "work");
    awaitOutcome(repoId, "install", BootstrapOutcome.SUCCEEDED);
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);

    // A restart of the Exited container (freshProvision=false): no chain re-run, straight to
    // service auto-start.
    workspaceService.stopContainer(repoId, "work");
    workspaceService.ensureContainer(repoId, "work");
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);

    assertEquals(
        List.of("installed"),
        Files.readAllLines(marker),
        "a plain restart does not re-run the chain");
  }

  @Test
  public void manualChainRerunAfterFailureIsTheRecoveryPath() throws Exception {
    Path flag = scratch.resolve("fixed.flag");
    // Fails until the flag file exists — the "broken then fixed" bootstrap step.
    String repoId = repoWithWorkspace("Bootstrap Recover", chainYaml("flaky=test -f " + flag));
    String serviceId = autoStartService("dev-recover");

    workspaceService.ensureContainer(repoId, "work");
    awaitOutcome(repoId, "flaky", BootstrapOutcome.FAILED);
    assertEquals(0, readyRecorder.countFor(repoId, "work"));

    // Fix the world, then re-run the whole chain from the workspace surface.
    Files.writeString(flag, "fixed");
    runner.runChainAsync(repoId, "work");

    awaitOutcome(repoId, "flaky", BootstrapOutcome.SUCCEEDED);
    await(
        () -> readyRecorder.countFor(repoId, "work") >= 1 ? Boolean.TRUE : null,
        "recovery releases auto-start");
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);
  }

  @Test
  public void singleCommandRerunRecordsItsOutcomeOnly() throws Exception {
    Path marker = scratch.resolve("single.log");
    String repoId =
        repoWithWorkspace(
            "Bootstrap Single",
            chainYaml("target=echo target >> " + marker, "other=echo other >> " + marker));

    // A single-step re-run touches only its own step — deliberately, even when its
    // ensureContainer fresh-provisions the container here: the run holds the in-flight guard, so
    // the
    // provision's container-started event yields to it and the rest of the chain does not run.
    // (Full
    // bootstrap + service auto-start is the fresh-provision/"Run all" job, not this trigger's.) The
    // step id passes through as the name (no config is readable for the workspace yet — ids
    // default to names).
    runner.runSingleAsync(repoId, "work", "target");

    awaitOutcome(repoId, "target", BootstrapOutcome.SUCCEEDED);
    Thread.sleep(500);
    assertNull(lastRun(repoId, "other"), "a single re-run touches only its command");
    assertEquals(List.of("target"), Files.readAllLines(marker));
  }

  @Test
  public void concurrentManualRunsAreRejected() throws Exception {
    String repoId = repoWithWorkspace("Bootstrap Conflict", chainYaml("slow=sleep 3"));
    String serviceId = autoStartService("dev-conflict");

    runner.runChainAsync(repoId, "work");
    assertThrows(
        BadRequestException.class,
        () -> runner.runChainAsync(repoId, "work"),
        "a second run while one is in flight is rejected");

    // Drain: the first run's success fires ready and the coupler auto-starts the daemon — await
    // it so the async pipeline is quiescent before the next test stages its own config.
    awaitServiceStatus(repoId, serviceId, ServiceStatus.STARTING);
  }

  @Test
  public void repositoryWithRecordedBootstrapRunDeletesCleanly() throws Exception {
    // A FAILED chain still records the run row — and conveniently never fires ready, so this test
    // leaves no async coupler pass behind.
    String repoId = repoWithWorkspace("Bootstrap Delete", chainYaml("install=exit 3"));

    workspaceService.ensureContainer(repoId, "work");
    awaitOutcome(repoId, "install", BootstrapOutcome.FAILED); // a workspace_bootstrap_run row
    // exists

    // Deleting the repository cascade-deletes its workspace rows; without `on delete cascade` on
    // the
    // bootstrap-run FK this fails with a referential-integrity violation the moment a run is
    // recorded (the V32 command_agent_session bug class).
    repositoryService.delete(repoId);

    assertThrows(
        eu.wohlben.qits.workspaces.error.NotFoundException.class,
        () -> repositoryService.get(repoId),
        "the repository (and its cascaded bootstrap-run rows) is gone");
  }
}
