package eu.wohlben.qits.workspacedaemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.ConfigView;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.DescribeConfig;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
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
            default -> {
              /* Ack and others: just recorded in `inbound` */
            }
          }
        });
    // Announce ourselves, exactly as workspace-daemon does on connect.
    ws.writeTextMessage(codec.encode(new Hello(WORKSPACE_ID, "repo-1", "feature", "main", 1)));
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
      assertEquals(1, config.daemons().size());
      assertEquals(8080, config.daemons().get(0).webView().port());
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
  void closingTheSocketPrunesTheRegistry() throws Exception {
    FakePeer peer = connect();
    await(() -> registry.isDaemonLive(WORKSPACE_ID));
    peer.close();
    await(() -> !registry.isDaemonLive(WORKSPACE_ID));
    assertFalse(registry.isDaemonLive(WORKSPACE_ID));
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
