package eu.wohlben.qits.workspacedaemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.domain.repository.control.WorkspaceConfigView;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint.Topic;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.GitStatus;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunBootstrap;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Proves the workspace-daemon control plane end-to-end in-JVM (docs/epics/qits-workspace-daemon/)
 * with a fake Vert.x WebSocket peer standing in for the native binary — no container, no docker. It
 * covers the handshake ({@code Hello}→{@code Ack} + registry liveness), the backend-initiated
 * {@code RunCommand}→{@code CommandChunk}*→{@code CommandExit} round-trip, the {@code
 * Describe}→{@code WorkspaceInfo} stub, and connection teardown pruning the registry. The same
 * {@link WorkspaceDaemonRegistry} path is exercised by a real container in the extended {@code
 * DaemonControlSocketIT}.
 */
@QuarkusTest
class DaemonControlSocketTest {

  private static final String WORKSPACE_ID = "ws-daemonhost-test";

  @Inject Vertx vertx;
  @Inject WorkspaceDaemonRegistry registry;
  @Inject DaemonMessageCodec codec;
  @Inject HintRecorder hints;

  @TestHTTPResource("/api/workspace-daemon/" + WORKSPACE_ID)
  URI endpoint;

  /** The {@code configJson}/{@code warning} the fake peer answers a {@link DescribeConfig} with. */
  private volatile String configJson = "{}";

  private volatile String configWarning = null;

  /**
   * A fake workspace-daemon: connects, echoes Hello, and answers RunCommand/Describe like the real
   * binary.
   */
  private FakePeer connect() throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    BlockingQueue<DaemonMessage> inbound = new LinkedBlockingQueue<>();
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(endpoint.getHost())
            .setPort(endpoint.getPort())
            .setURI(endpoint.getPath());
    WebSocket ws =
        client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    ws.textMessageHandler(
        text -> {
          DaemonMessage message = codec.decode(text);
          inbound.add(message);
          switch (message) {
            case RunCommand command -> {
              ws.writeTextMessage(
                  codec.encode(new CommandChunk(command.correlationId(), Stream.STDOUT, "hi\n")));
              ws.writeTextMessage(
                  codec.encode(new CommandChunk(command.correlationId(), Stream.STDERR, "warn\n")));
              ws.writeTextMessage(codec.encode(new CommandExit(command.correlationId(), 0)));
            }
            case Describe ignored ->
                ws.writeTextMessage(
                    codec.encode(
                        new WorkspaceInfo(
                            WORKSPACE_ID, "repo-1", "feature", "main", "deadbeef", true)));
            case DescribeConfig request ->
                ws.writeTextMessage(
                    codec.encode(
                        new ConfigView(
                            WORKSPACE_ID, request.correlationId(), configJson, configWarning)));
            case RunBootstrap ignored -> {
              // Manual re-run: reply with a one-step chain + terminal, as the daemon would.
              ws.writeTextMessage(
                  codec.encode(
                      new BootstrapStep(WORKSPACE_ID, "install", BootstrapStep.Phase.EXECUTE)));
              ws.writeTextMessage(
                  codec.encode(
                      new BootstrapOutcome(
                          WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
              ws.writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));
            }
            default -> {
              /* Ack and others: just recorded in `inbound` */
            }
          }
        });
    // Announce ourselves, exactly as workspace-daemon does on connect.
    ws.writeTextMessage(
        codec.encode(
            new Hello(
                WORKSPACE_ID,
                "repo-1",
                "feature",
                "main",
                1,
                "1.0.0-SNAPSHOT",
                "2026-07-25T09:14:03Z")));
    ws.writeTextMessage(codec.encode(new DaemonLog("INFO", "workspace-daemon online")));
    return new FakePeer(client, ws, inbound);
  }

  @Test
  void handshakeRegistersLivenessAndAcks() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      assertTrue(registry.isDaemonLive(WORKSPACE_ID));
      assertInstanceOf(Ack.class, peer.take());
    }
  }

  @Test
  void handshakeRecordsDaemonRegistryInfo() throws Exception {
    try (FakePeer peer = connect()) {
      // The entry appears on socket registration (connectedAt), but version/buildTime are filled
      // when the Hello frame is processed — await that, not mere presence.
      await(() -> registry.lookup(WORKSPACE_ID).map(i -> i.version() != null).orElse(false));
      WorkspaceDaemonInfo.Info info = registry.lookup(WORKSPACE_ID).orElseThrow();
      // The build identity the fake peer announced in its Hello is retained for the registry, the
      // build timestamp parsed to an Instant; connectedAt is stamped server-side on registration.
      assertEquals("1.0.0-SNAPSHOT", info.version());
      assertEquals(java.time.Instant.parse("2026-07-25T09:14:03Z"), info.buildTime());
      assertTrue(info.connectedAt() != null);
    }
  }

  @Test
  void lookupIsEmptyForAnUnknownWorkspace() {
    assertTrue(registry.lookup("no-such-workspace").isEmpty());
  }

  @Test
  void runCommandRoundTripsOverTheSocket() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      WorkspaceDaemonRegistry.CommandResult result =
          registry
              .runCommand(WORKSPACE_ID, List.of("echo", "hi"), "/workspace", Map.of())
              .get(5, TimeUnit.SECONDS);

      assertEquals(0, result.exitCode());
      assertEquals("hi\n", result.stdout());
      assertEquals("warn\n", result.stderr());
    }
  }

  @Test
  void describeReturnsWorkspaceInfo() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      WorkspaceInfo info = registry.describe(WORKSPACE_ID).get(5, TimeUnit.SECONDS);

      assertEquals("deadbeef", info.head());
      assertTrue(info.dirty());
      assertEquals("feature", info.branch());
    }
  }

  @Test
  void readConfigDeserializesTheDaemonsConfigViewIntoQitsConfig() throws Exception {
    // A QitsConfig-shaped JSON as the daemon's ConfigJson emits it (camelCase keys, empty
    // collections present) — readConfig must map it straight into a QitsConfig over the socket.
    configJson =
        "{\"repository\":{\"mainBranch\":\"main\",\"archetype\":\"SERVICE\"},"
            + "\"frameworks\":[],"
            + "\"actions\":[{\"name\":\"build\",\"execute\":\"mvn -B verify\",\"interactive\":false,"
            + "\"environment\":{\"CI\":\"true\"}}],"
            + "\"daemons\":[{\"name\":\"dev\",\"start\":\"mvn quarkus:dev\",\"readyPattern\":\"Listening\","
            + "\"environment\":{},\"webView\":{\"port\":8080,\"entryPath\":\"/\"},"
            + "\"observers\":[],\"sources\":[],\"healthChecks\":[]}],"
            + "\"bootstrap\":[]}";
    configWarning = null;
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ID);

      assertTrue(read.isPresent());
      WorkspaceConfigView view = read.get();
      assertEquals(null, view.warning());
      QitsConfig config = view.config();
      assertEquals("main", config.repository().mainBranch());
      assertEquals(1, config.actions().size());
      assertEquals("build", config.actions().get(0).name());
      assertEquals(Map.of("CI", "true"), config.actions().get(0).environment());
      assertEquals(1, config.services().size());
      assertEquals(8080, config.services().get(0).webView().port());
    }
  }

  @Test
  void readConfigDeserializesExplicitIdsAndDefaultsMissingIdsToNames() throws Exception {
    // Part 5: every declared entry carries an id: — explicit when the file declares one, defaulting
    // to the entry's name when absent. The registry's Jackson deserialization into QitsConfig must
    // honor both.
    configJson =
        "{\"frameworks\":[],"
            + "\"actions\":["
            + "{\"id\":\"build-ci\",\"name\":\"build\",\"execute\":\"mvn -B verify\"},"
            + "{\"name\":\"test\",\"execute\":\"mvn test\"}],"
            + "\"services\":["
            + "{\"id\":\"dev-server\",\"name\":\"dev\",\"start\":\"mvn quarkus:dev\"},"
            + "{\"name\":\"logs\",\"start\":\"tail -f app.log\"}],"
            + "\"bootstrap\":["
            + "{\"id\":\"install-deps\",\"name\":\"install\",\"execute\":\"./install.sh\"},"
            + "{\"name\":\"seed\",\"execute\":\"./seed.sh\"}]}";
    configWarning = null;
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ID);

      assertTrue(read.isPresent());
      QitsConfig config = read.get().config();
      assertEquals("build-ci", config.actions().get(0).id());
      assertEquals("test", config.actions().get(1).id(), "id-less action defaults id to name");
      assertEquals("dev-server", config.services().get(0).id());
      assertEquals("logs", config.services().get(1).id(), "id-less service defaults id to name");
      assertEquals("install-deps", config.bootstrap().get(0).id());
      assertEquals("seed", config.bootstrap().get(1).id(), "id-less step defaults id to name");
    }
  }

  @Test
  void readConfigSurfacesTheDaemonsWarningWithEmptyConfig() throws Exception {
    configJson = "{\"frameworks\":[],\"actions\":[],\"daemons\":[],\"bootstrap\":[]}";
    configWarning = "Unsupported or missing 'version' (expected 1): null";
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      Optional<WorkspaceConfigView> read = registry.readConfig(WORKSPACE_ID);

      assertTrue(read.isPresent());
      assertEquals("Unsupported or missing 'version' (expected 1): null", read.get().warning());
      assertTrue(read.get().config().isEmpty());
    }
  }

  @Test
  void readConfigIsEmptyWhenNoDaemonIsLive() {
    assertTrue(registry.readConfig("ws-no-daemon-here").isEmpty());
  }

  @Test
  void awaitProvisionCompletesOnProvisionedAndStreamsOutput() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitProvision(
                      "repo-1",
                      WORKSPACE_ID,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(5),
                      lines::add));

      // Wait until the awaiter's line sink is registered, so the streamed chunk is routed rather
      // than
      // dropped (chunk routing is best-effort before an awaiter exists). The terminal Provisioned
      // itself is race-proof via complete-or-retain.
      await(() -> registry.isAwaitingProvision(WORKSPACE_ID));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new CommandChunk(
                      eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol
                          .PROVISION_CORRELATION_ID,
                      Stream.STDOUT,
                      "Cloning into /workspace...\n")));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new eu.wohlben.qits.workspacedaemon.protocol.Provisioned(
                      WORKSPACE_ID, "cafebabe")));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertEquals("cafebabe", result.get().head());
      await(() -> lines.contains("Cloning into /workspace..."));
      assertTrue(lines.contains("Cloning into /workspace..."), lines.toString());
    }
  }

  @Test
  void awaitProvisionFailsOnProvisionFailed() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitProvision(
                      "repo-1", WORKSPACE_ID, Duration.ofSeconds(5), Duration.ofSeconds(5), null));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed(
                      WORKSPACE_ID, "git clone exited 128")));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertFalse(result.get().ok());
      assertEquals("git clone exited 128", result.get().message());
    }
  }

  @Test
  void awaitProvisionReturnsEmptyWhenNoDaemonConnects() {
    // No peer: the connect window lapses and awaitProvision reports "no daemon" (empty). The daemon
    // is the sole provisioner now, so the caller (WorkspaceService) turns this empty into a
    // provision
    // FAILURE — there is no host-driven fallback.
    var result =
        registry.awaitProvision(
            "repo-none", "ws-never-connects", Duration.ofMillis(200), Duration.ofSeconds(1), null);
    assertTrue(result.isEmpty());
  }

  @Test
  void awaitBootstrapStreamsStepsAndCompletesOk() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      CapturingSink sink = new CapturingSink();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      "repo-1", WORKSPACE_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5)));

      // The daemon streams the chain autonomously, then the terminal.
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapStep(WORKSPACE_ID, "install", BootstrapStep.Phase.EXECUTE)));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new CommandChunk(
                      DaemonProtocol.bootstrapCorrelationId("install"),
                      Stream.STDOUT,
                      "building\n")));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.steps.contains("install:EXECUTE"), sink.steps.toString());
      assertTrue(sink.lines.contains("building"), sink.lines.toString());
      assertTrue(sink.outcomes.contains("install:SUCCEEDED:0"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapReportsFailedChain() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      CapturingSink sink = new CapturingSink();
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      "repo-1", WORKSPACE_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5)));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.FAILED, 7)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, false)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(result.isPresent());
      assertFalse(result.get().ok(), "a failed chain gates daemons off");
      assertTrue(sink.outcomes.contains("install:FAILED:7"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapPicksUpATerminalThatArrivedBeforeTheAwait() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      // The autonomous chain finishes before the host's observer registers its await: the terminal
      // (and the step events) must be buffered/retained and replayed when the sink registers.
      peer.ws()
          .writeTextMessage(
              codec.encode(new BootstrapStep(WORKSPACE_ID, "seed", BootstrapStep.Phase.EXECUTE)));
      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "seed", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      // Give the frames time to land on the registry ahead of the await.
      await(() -> registry.isBootstrapPending(WORKSPACE_ID));
      CapturingSink sink = new CapturingSink();
      var result =
          registry.awaitBootstrap(
              "repo-1", WORKSPACE_ID, sink, Duration.ofSeconds(5), Duration.ofSeconds(5));

      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.outcomes.contains("seed:SUCCEEDED:0"), "buffered step replayed on the sink");
    }
  }

  @Test
  void runBootstrapSendsRunBootstrapAndAwaitsTheReply() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      CapturingSink sink = new CapturingSink();

      var result = registry.runBootstrap("repo-1", WORKSPACE_ID, null, sink, Duration.ofSeconds(5));

      assertTrue(result.isPresent());
      assertTrue(result.get().ok());
      assertTrue(sink.outcomes.contains("install:SUCCEEDED:0"), sink.outcomes.toString());
    }
  }

  @Test
  void awaitBootstrapCompletesEvenWhenASinkCallbackThrows() throws Exception {
    // A sink whose onOutcome throws (the recordOutcome-on-deleted-workspace case) must not escape
    // onMessage and tear down the socket — the terminal Bootstrapped must still complete the await.
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      WorkspaceBootstrapDriver.StepSink throwingSink =
          new WorkspaceBootstrapDriver.StepSink() {
            @Override
            public void onStep(String name, String phase) {}

            @Override
            public void onLine(String name, String line) {}

            @Override
            public void onOutcome(String name, String outcome, Integer exitCode) {
              throw new RuntimeException("simulated recordOutcome failure");
            }
          };
      var awaiting =
          java.util.concurrent.CompletableFuture.supplyAsync(
              () ->
                  registry.awaitBootstrap(
                      "repo-1",
                      WORKSPACE_ID,
                      throwingSink,
                      Duration.ofSeconds(5),
                      Duration.ofSeconds(5)));

      peer.ws()
          .writeTextMessage(
              codec.encode(
                  new BootstrapOutcome(
                      WORKSPACE_ID, "install", BootstrapOutcome.Result.SUCCEEDED, 0)));
      peer.ws().writeTextMessage(codec.encode(new Bootstrapped(WORKSPACE_ID, true)));

      var result = awaiting.get(5, TimeUnit.SECONDS);
      assertTrue(
          result.isPresent(), "the throwing sink must not prevent the terminal from resolving");
      assertTrue(result.get().ok());
    }
  }

  @Test
  void runBootstrapIsEmptyWhenNoDaemonIsLive() {
    var result =
        registry.runBootstrap(
            "repo-none",
            "ws-no-daemon-bootstrap",
            null,
            new CapturingSink(),
            Duration.ofSeconds(1));
    assertTrue(result.isEmpty());
  }

  @Test
  void closingTheSocketPrunesTheRegistry() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ID));
    peer.close();
    await(() -> !registry.isDaemonLive(WORKSPACE_ID));
    assertFalse(registry.isDaemonLive(WORKSPACE_ID));
  }

  @Test
  void gitStatusReportCachesTheFlagAndFiresFilesAndGitStatusHints() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));
      hints.clear();

      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, false, "abc123")));

      // The flag is cached for the DTO read path.
      await(() -> registry.isClean(WORKSPACE_ID).isPresent());
      assertEquals(Optional.of(false), registry.isClean(WORKSPACE_ID));
      // Every report re-homes the old host watcher's FILES trigger (workspace channel)…
      await(() -> hints.has(Topic.FILES, "repo-1", WORKSPACE_ID));
      assertTrue(hints.has(Topic.FILES, "repo-1", WORKSPACE_ID), hints.toString());
      // …and a flag change nudges the branch-tree badge on the repository channel (repoId, null).
      await(() -> hints.has(Topic.GIT_STATUS, "repo-1", null));
      assertTrue(hints.has(Topic.GIT_STATUS, "repo-1", null), hints.toString());
    }
  }

  @Test
  void unchangedGitStatusFiresFilesButNotGitStatus() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      // First report establishes clean=true (fires FILES + GIT_STATUS).
      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, true, "abc123")));
      await(() -> registry.isClean(WORKSPACE_ID).equals(Optional.of(true)));
      hints.clear();

      // A second report with the SAME flag (a dirty→dirty content edit would look like this): the
      // marker moved on the daemon, so FILES must fire again, but the badge flag didn't change so
      // GIT_STATUS must NOT.
      peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, true, "abc123")));
      await(() -> hints.has(Topic.FILES, "repo-1", WORKSPACE_ID));
      assertTrue(hints.has(Topic.FILES, "repo-1", WORKSPACE_ID), hints.toString());
      assertFalse(hints.has(Topic.GIT_STATUS, "repo-1", null), hints.toString());
    }
  }

  @Test
  void disconnectEvictsTheCachedGitStatus() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ID));
    peer.ws().writeTextMessage(codec.encode(new GitStatus(WORKSPACE_ID, false, "abc123")));
    await(() -> registry.isClean(WORKSPACE_ID).isPresent());

    peer.close();

    await(() -> registry.isClean(WORKSPACE_ID).isEmpty());
    assertTrue(registry.isClean(WORKSPACE_ID).isEmpty(), "cache cleared on disconnect (unknown)");
  }

  @Test
  void pullFromOriginSendsAPullBranchToTheLiveDaemon() throws Exception {
    try (FakePeer peer = connect()) {
      await(() -> registry.isDaemonLive(WORKSPACE_ID));

      // A host-side merge advanced this workspace's branch; the registry asks the daemon to pull.
      registry.pullFromOrigin(WORKSPACE_ID, "feature");

      PullBranch pull = null;
      for (int i = 0; i < 10 && pull == null; i++) {
        if (peer.take() instanceof PullBranch p) {
          pull = p;
        }
      }
      assertTrue(pull != null, "expected a PullBranch frame at the daemon");
      assertEquals("feature", pull.branch());
    }
  }

  @Test
  void pullFromOriginIsANoOpWithoutALiveDaemon() {
    // No daemon connected for this id: fire-and-forget, must not throw (the checkout syncs on its
    // next host git op).
    registry.pullFromOrigin("nobody-home", "feature");
  }

  /** Spin until {@code condition} holds or a 5s deadline passes. */
  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return; // let the caller's assertion report the failure
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
  }

  /** Captures the {@link WorkspaceBootstrapDriver.StepSink} callbacks for assertion. */
  private static final class CapturingSink implements WorkspaceBootstrapDriver.StepSink {
    private final List<String> steps = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> outcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void onStep(String name, String phase) {
      steps.add(name + ":" + phase);
    }

    @Override
    public void onLine(String name, String line) {
      lines.add(line);
    }

    @Override
    public void onOutcome(String name, String outcome, Integer exitCode) {
      outcomes.add(name + ":" + outcome + ":" + exitCode);
    }
  }

  /**
   * Collects the async {@link WorkspaceChangeHint}s the registry fires, so the git-status tests can
   * assert the re-homed FILES trigger and the GIT_STATUS badge nudge without the SSE boundary. An
   * {@code @ApplicationScoped} bean discovered by the {@code @QuarkusTest}.
   */
  @jakarta.enterprise.context.ApplicationScoped
  public static class HintRecorder {
    private final List<WorkspaceChangeHint> received =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    void onHint(@jakarta.enterprise.event.ObservesAsync WorkspaceChangeHint hint) {
      received.add(hint);
    }

    void clear() {
      received.clear();
    }

    boolean has(Topic topic, String repoId, String workspaceId) {
      return received.stream()
          .anyMatch(
              h ->
                  h.topic() == topic
                      && java.util.Objects.equals(h.repoId(), repoId)
                      && java.util.Objects.equals(h.workspaceId(), workspaceId));
    }

    @Override
    public String toString() {
      return received.toString();
    }
  }

  /** The fake workspace-daemon side of the socket. */
  private record FakePeer(
      WebSocketClient client, WebSocket ws, BlockingQueue<DaemonMessage> inbound)
      implements AutoCloseable {

    DaemonMessage take() throws InterruptedException {
      DaemonMessage message = inbound.poll(5, TimeUnit.SECONDS);
      if (message == null) {
        throw new AssertionError("expected a frame from the backend but none arrived");
      }
      return message;
    }

    @Override
    public void close() {
      ws.close();
      client.close();
    }
  }
}
