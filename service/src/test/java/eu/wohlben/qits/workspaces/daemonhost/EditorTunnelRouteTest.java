package eu.wohlben.qits.workspaces.daemonhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor over the reverse tunnel, end to end in one JVM: a fake daemon holds a real control
 * socket, answers an {@code OpenStream} by dialling the real stream route back, and pipes that to a
 * real HTTP server standing in for openvscode-server on its container's loopback. A browser request
 * on {@code editor.<project>.…} then has to traverse all of it.
 *
 * <p>{@link DaemonStreamRouteTest}'s shape, one target over — and the two things this class adds are
 * the two the target exists for.
 *
 * <p><b>The stream is asked for by NAME.</b> The {@code OpenStream} carries {@link
 * StreamTarget#EDITOR}, so the daemon resolves it against its own allow-list and the host never
 * states a port inside the container. That the frame says {@code EDITOR} is asserted directly,
 * because it is the only thing that keeps an editor request off the daemon's own API.
 *
 * <p><b>A daemon that predates the editor is never asked.</b> An older image decodes an absent
 * target as {@code API}, so a name it has never heard of would be served by the wrong listener
 * rather than refused — which is why the gate is a capability version and not a hope.
 */
@QuarkusTest
@TestProfile(EditorTunnelRouteTest.TestProfile.class)
public class EditorTunnelRouteTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-editor-tunnel-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            // Short enough not to dominate the run, long enough that a loopback dial-back never
            // loses the race.
            "qits.workspace.daemon-tunnel.nonce-ttl-ms", "8000");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceTunnels tunnels;
  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx vertx;
  private HttpServer editor;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** Every OpenStream the fake daemon was asked for. */
  private final CopyOnWriteArrayList<OpenStream> asked = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    editor = vertx.createHttpServer();
    editor.requestHandler(
        req -> req.response().end("editor:" + req.uri() + ":" + req.getHeader("X-Qits-User")));
    editor.webSocketHandler(
        (ServerWebSocket socket) ->
            socket.textMessageHandler(text -> socket.writeTextMessage("ws-editor:" + text)));
    await(editor.listen(0, "127.0.0.1"));
    asked.clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    // The application's tunnels outlive a test otherwise: each holds a NetServer and an HttpClient
    // wired to a fake daemon whose Vert.x is about to close. In production a tunnel's lifetime is
    // its daemon's; here it has to be the test's.
    tunnels.closeAll();
    if (controlSocket != null) {
      controlSocket.close();
    }
    if (wsClient != null) {
      wsClient.close();
    }
    if (netClient != null) {
      netClient.close();
    }
    if (editor != null) {
      editor.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  /**
   * A fake daemon for {@code workspaceId} announcing {@code capabilityVersion}, serving every {@code
   * OpenStream} by dialling the real stream route and piping it to {@link #editor} — which stands in
   * for the ONE listener this test is about, so a frame that named the API would fail visibly rather
   * than quietly succeed against a second stub.
   */
  private void connectFakeDaemon(Long workspaceId, int capabilityVersion) throws Exception {
    controlSocket =
        await(wsClient.connect(RestAssured.port, "127.0.0.1", "/workspaces/daemon/" + workspaceId));
    controlSocket.textMessageHandler(
        text -> {
          DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
          if (message instanceof OpenStream open) {
            asked.add(open);
            serveStream(open);
          }
        });
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello(
                        "master",
                        "repo",
                        "work",
                        "master",
                        capabilityVersion,
                        "test",
                        null)))
            .encode());
    awaitCapability(workspaceId, capabilityVersion);
  }

  /** What a daemon that supervises an editor says on connect and on every transition. */
  private void reportEditor(Long workspaceId, String state) throws Exception {
    controlSocket.writeTextMessage(
        new JsonObject(DaemonCodec.encode(new EditorState(state))).encode());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (registry.editorStateFor(workspaceId).map(Enum::name).filter(state::equals).isPresent()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's EditorState never registered");
  }

  private void serveStream(OpenStream open) {
    netClient
        .connect(editor.actualPort(), "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(RestAssured.port, "127.0.0.1", open.path())
                    .onSuccess(remote -> pipe(remote, local))
                    .onFailure(t -> local.close()));
  }

  private static void pipe(WebSocket remote, NetSocket local) {
    remote.pause();
    local.pause();
    remote.handler(local::write);
    local.handler(remote::writeBinaryMessage);
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
    remote.resume();
    local.resume();
  }

  /** A project whose wrapper has a main workspace — and deliberately NO container. */
  private Workspace editorWorkspace(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    return workspaceService.createMainWorkspace(repoId, "master");
  }

  private static String host(String slug) {
    return "editor." + slug + ".dev.example.eu";
  }

  @Test
  public void aRequestReachesTheEditorThroughAStreamAskedForByName() throws Exception {
    Workspace main = editorWorkspace("tunnelled");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host("tunnelled"))
        .header("X-Qits-User", "alice")
        .get("/stable-abc/static/main.js")
        .then()
        .statusCode(200)
        // The path arrives unrewritten — the editor serves from `/`, which is the whole reason it
        // is a host — and the platform's identity does not arrive at all.
        .body(containsString("editor:/stable-abc/static/main.js:null"));

    assertEquals(1, asked.size(), "exactly one stream was asked for");
    assertEquals(
        StreamTarget.EDITOR,
        asked.getFirst().target(),
        "an editor request must name the editor; API here would serve the daemon's own 404s");
    assertTrue(
        asked.getFirst().path().startsWith(WorkspaceTunnels.STREAM_PATH_PREFIX),
        asked.getFirst().path());
  }

  @Test
  public void theTunnelBranchNeverAsksTheOrchestratorWhetherTheContainerIsUp() throws Exception {
    // No ensureContainer anywhere in this class, so FakeContainerRuntime knows of no container at
    // all — and the request is still served. A live control socket at the editor capability is
    // stronger evidence that the container is up than a status call is, and it is one round trip
    // less per request on a surface where a keystroke is a request.
    Workspace main = editorWorkspace("nocontainerread");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host("nocontainerread"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("editor:/"));
  }

  @Test
  public void aWebSocketTraversesTheTunnelToTheEditor() throws Exception {
    Workspace main = editorWorkspace("tunnelsockets");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    // The case the byte pipe exists for: the extension host's socket crosses a proxy and a tunnel,
    // and neither end knows about either.
    CompletableFuture<String> reply = new CompletableFuture<>();
    WebSocket browser =
        await(
            wsClient.connect(
                new WebSocketConnectOptions()
                    .setHost("127.0.0.1")
                    .setPort(RestAssured.port)
                    .setURI("/stable-abc/vscode-remote-resource")
                    .addHeader("X-Forwarded-Host", host("tunnelsockets"))
                    .addHeader("X-Qits-User", "alice")));
    browser.textMessageHandler(reply::complete);
    browser.writeTextMessage("hello");

    assertEquals("ws-editor:hello", reply.get(20, TimeUnit.SECONDS));
    browser.close();
  }

  @Test
  public void aDaemonBelowTheEditorCapabilityIsNeverAskedForAnEditorStream() throws Exception {
    Workspace main = editorWorkspace("olddaemon");
    connectFakeDaemon(main.id, WorkspaceTunnels.EDITOR_CAPABILITY_VERSION - 1);

    // That image carries no editor, and an absent target decodes there as API — so asking would be
    // served by the wrong listener, and the browser would read the daemon's 404s as an editor that
    // is broken rather than one that is absent. It gets the splash instead: no container was ever
    // ensured for this workspace, so there is nothing running to wait for either.
    given()
        .header("X-Forwarded-Host", host("olddaemon"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("not running"));

    assertTrue(asked.isEmpty(), "a daemon that cannot serve an editor must not be asked for one");
  }

  // --- helpers ------------------------------------------------------------------------------------

  private void awaitCapability(Long workspaceId, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Integer seen = registry.lookup(workspaceId).map(info -> info.capabilityVersion()).orElse(null);
      if (seen != null && seen == expected) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's Hello never registered");
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
