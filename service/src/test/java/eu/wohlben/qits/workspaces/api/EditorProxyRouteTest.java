package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonRegistry;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor proxy against a real loopback origin: a Vert.x server plays openvscode-server —
 * echoing the path it was called on and the platform headers it did or did not see — and {@code
 * FakeContainerRuntime} resolves the container's target to {@code 127.0.0.1} + the configured
 * editor port, so that port <em>is</em> the port the proxy dials. No docker and no daemon; the
 * tunnel transport is {@link eu.wohlben.qits.workspaces.daemonhost.EditorTunnelRouteTest}'s subject
 * and this class is about everything else the route decides.
 *
 * <p>The sibling of {@link ContainerProxyRouteTest}, including the latched port: the editor's port
 * comes from configuration, so it has to be fixed before the application boots, and a profile that
 * picked a fresh one per classloader would configure the application with one number and the test
 * with another. See {@link #PORT_PROPERTY}.
 */
@QuarkusTest
@TestProfile(EditorProxyRouteTest.TestProfile.class)
public class EditorProxyRouteTest {

  private static final String PORT_PROPERTY = "qits.test.editor-proxy.editor-port";

  /** First caller wins; {@code ContainerProxyRouteTest}'s own note says at length why it must. */
  private static synchronized int latchedPort() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (Exception e) {
      throw new IllegalStateException("no free port for the fake editor", e);
    }
  }

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-editor-proxy-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            "qits.editor.port", String.valueOf(latchedPort()));
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx editorVertx;
  private HttpServer editorServer;
  private final AtomicInteger editorHits = new AtomicInteger();
  private final AtomicReference<String> lastUserHeader = new AtomicReference<>();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastForwardedHost = new AtomicReference<>();

  /** The text frame that asks the fake editor to write N 64 KiB binary frames as fast as it can. */
  private static final String FLOOD = "flood:";

  private static final int FLOOD_FRAME_BYTES = 64 * 1024;

  private final AtomicReference<CompletableFuture<Void>> floodWritten =
      new AtomicReference<>(new CompletableFuture<>());

  @BeforeEach
  void startFakeEditor() throws Exception {
    floodWritten.set(new CompletableFuture<>());
    editorVertx = Vertx.vertx();
    editorServer =
        editorVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  editorHits.incrementAndGet();
                  lastUserHeader.set(req.getHeader("X-Qits-User"));
                  lastAuthorization.set(req.getHeader("Authorization"));
                  lastForwardedHost.set(req.getHeader("X-Forwarded-Host"));
                  req.response().end("editor:" + req.uri());
                })
            .webSocketHandler(
                ws ->
                    ws.textMessageHandler(
                        message -> {
                          if (message.startsWith(FLOOD)) {
                            flood(ws, Integer.parseInt(message.substring(FLOOD.length())));
                          } else {
                            ws.writeTextMessage("ws-editor:" + ws.uri() + ":" + message);
                          }
                        }))
            .listen(latchedPort(), "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    editorHits.set(0);
    lastUserHeader.set(null);
    lastAuthorization.set(null);
  }

  @AfterEach
  void stopFakeEditor() {
    if (editorVertx != null) {
      editorVertx.close();
    }
  }

  /** A well-behaved producer: stop on a full write queue, resume on drain. */
  private void flood(ServerWebSocket ws, int remaining) {
    Buffer frame = Buffer.buffer(new byte[FLOOD_FRAME_BYTES]);
    int left = remaining;
    while (left > 0 && !ws.writeQueueFull()) {
      ws.writeBinaryMessage(frame);
      left--;
    }
    if (left == 0) {
      floodWritten.get().complete(null);
      return;
    }
    int rest = left;
    ws.drainHandler(
        drained -> {
          ws.drainHandler(null);
          flood(ws, rest);
        });
  }

  // --- fixtures -----------------------------------------------------------------------------------

  /** A project whose wrapper has a main workspace with a (fake) container running. */
  private Workspace editorWorkspace(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    Workspace main = workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.ensureContainer(main.id);
    return main;
  }

  private static String host(String slug) {
    return "editor." + slug + ".dev.example.eu";
  }

  /** What the daemon would have said, without a daemon: the registry caches the frame either way. */
  private void reportEditor(Long rowId, String state) {
    registry.onMessage(rowId, null, new EditorState(state));
  }

  // --- cases --------------------------------------------------------------------------------------

  @Test
  public void aRunningEditorGetsThePathVerbatimAndNoneOfThePlatformsIdentity() throws Exception {
    Workspace main = editorWorkspace("verbatim");
    reportEditor(main.id, EditorState.State.RUNNING);

    // openvscode-server serves from `/` and this route rewrites nothing — which is the whole reason
    // the editor is a host rather than a path prefix. A deep asset path arrives exactly as sent.
    given()
        .header("X-Forwarded-Host", host("verbatim"))
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .header("Authorization", "Bearer smuggled-in-by-the-caller")
        .get("/stable-abc123/static/out/vs/workbench/workbench.js?v=2")
        .then()
        .statusCode(200)
        .body(containsString("editor:/stable-abc123/static/out/vs/workbench/workbench.js?v=2"));

    // The editor runs an untrusted checkout with a shell: a platform identity header it can read is
    // one it can echo at something that trusts the namespace, and a bearer would be a credential
    // moved inside the sandbox for nobody's benefit.
    assertNull(lastUserHeader.get(), "the X-Qits-* namespace must not reach the editor");
    assertNull(lastAuthorization.get(), "no credential travels toward the editor");
    // What DOES travel is the public name, which is the only thing the editor could want.
    assertEquals(host("verbatim"), lastForwardedHost.get());
  }

  @Test
  public void aRequestWithoutThePlatformsIdentityIsRefusedBeforeAnythingIsDialled()
      throws Exception {
    Workspace main = editorWorkspace("refusal");
    reportEditor(main.id, EditorState.State.RUNNING);
    int before = editorHits.get();

    // The edge strips the X-Qits-* namespace from every inbound request unconditionally, so the
    // header cannot be forged and its ABSENCE is evidence too: this request did not come through the
    // session gate that is this platform's auth boundary. 403 rather than 401 because this hop has
    // no challenge to issue — the login is at the edge.
    given()
        .header("X-Forwarded-Host", host("refusal"))
        .get("/")
        .then()
        .statusCode(403)
        .body(containsString("did not come that way"));

    assertEquals(before, editorHits.get(), "a refused request must reach no container");
  }

  @Test
  public void aLabelNobodyRegisteredIs404WithNothingDialled() throws Exception {
    editorWorkspace("known");
    int before = editorHits.get();

    // Not a redirect, not a default project, not the only project this platform happens to have.
    given()
        .header("X-Forwarded-Host", host("nosuchproject"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(404)
        .body(containsString("no editor for this address"));

    assertEquals(before, editorHits.get());
  }

  @Test
  public void aStoppedContainerIsASplashAndNotAnError() throws Exception {
    Workspace main = editorWorkspace("stopped");
    reportEditor(main.id, EditorState.State.RUNNING);
    workspaceService.stopContainer(main.id);
    int before = editorHits.get();

    // A container that is not up is not a broken editor. The page says so and refreshes itself, so
    // opening the editor while it starts is the same act as opening it once it has.
    given()
        .header("X-Forwarded-Host", host("stopped"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("not running"))
        .body(containsString("http-equiv=\"refresh\""));

    assertEquals(before, editorHits.get(), "nothing is forwarded to a stopped container");
  }

  @Test
  public void aStartingEditorAndAnUnreportedOneAreTheSameSplash() throws Exception {
    Workspace main = editorWorkspace("starting");
    int before = editorHits.get();

    // Nothing reported: the container is up, and no frame has arrived. A reader cannot act on the
    // difference between that and STARTING, so they are one answer.
    given()
        .header("X-Forwarded-Host", host("starting"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"))
        .body(containsString("http-equiv=\"refresh\""));

    reportEditor(main.id, EditorState.State.STARTING);
    given()
        .header("X-Forwarded-Host", host("starting"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("starting"));

    assertEquals(before, editorHits.get(), "a starting editor is not asked to serve");
  }

  @Test
  public void anEndedEditorStopsTheWaitingWithAStatusOfItsOwn() throws Exception {
    Workspace main = editorWorkspace("ended");
    reportEditor(main.id, EditorState.State.ENDED);

    // Terminal, so it must NOT be the refreshing splash: the editor is not coming back in this
    // container, and a page that kept waiting would spin for the container's lifetime.
    given()
        .header("X-Forwarded-Host", host("ended"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(502)
        .body(containsString("recreate the container"));
  }

  @Test
  public void aWebSocketRidesThroughToTheEditor() throws Exception {
    Workspace main = editorWorkspace("sockets");
    reportEditor(main.id, EditorState.State.RUNNING);
    String path = "/stable-abc123/vscode-remote-resource?socket=1";

    WebSocketClient client = editorVertx.createWebSocketClient();
    try {
      CompletableFuture<String> reply = new CompletableFuture<>();
      WebSocket browser =
          client
              .connect(
                  new WebSocketConnectOptions()
                      .setHost("127.0.0.1")
                      .setPort(RestAssured.port)
                      .setURI(path)
                      .addHeader("X-Forwarded-Host", host("sockets"))
                      .addHeader("X-Qits-User", "alice"))
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);
      browser.textMessageHandler(reply::complete);
      browser.writeTextMessage("hello");

      // openvscode-server is mostly websocket — the extension host, every terminal, every file
      // watcher — so this is the path that carries the editor rather than an exception to it.
      // The URI and not the path: openvscode-server carries its reconnection token and its frame
      // options in the query of exactly this socket, so a hop that dropped one would lose a session
      // rather than a decoration.
      assertEquals("ws-editor:" + path + ":hello", reply.get(10, TimeUnit.SECONDS));
      browser.close();
    } finally {
      client.close();
    }
  }

  /**
   * The case that is about the pipe rather than about the feature.
   *
   * <p>{@code vertx-http-proxy} pipes an upgrade with bare {@code a.handler(b::write)} installs — no
   * {@code writeQueueFull}, no {@code pause}, no {@code drainHandler} — so an editor streaming a
   * build log into a terminal writes as fast as it likes and everything the browser has not read
   * accumulates on <em>this</em> process's heap. Park the browser, ask for 32 MiB, and a bounded
   * pipe simply cannot get rid of it; resume, and every byte arrives in order.
   */
  @Test
  public void aParkedBrowserStopsTheEditorRatherThanFillingThisProcessesHeap() throws Exception {
    Workspace main = editorWorkspace("backpressure");
    reportEditor(main.id, EditorState.State.RUNNING);
    int frames = 512;
    long expectedBytes = (long) frames * FLOOD_FRAME_BYTES;

    WebSocketClient client = editorVertx.createWebSocketClient();
    try {
      WebSocket browser =
          client
              .connect(
                  new WebSocketConnectOptions()
                      .setHost("127.0.0.1")
                      .setPort(RestAssured.port)
                      .setURI("/flooded")
                      .addHeader("X-Forwarded-Host", host("backpressure"))
                      .addHeader("X-Qits-User", "alice"))
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);

      CompletableFuture<Void> allReceived = new CompletableFuture<>();
      AtomicLong received = new AtomicLong();
      browser.binaryMessageHandler(
          buffer -> {
            if (received.addAndGet(buffer.length()) >= expectedBytes) {
              allReceived.complete(null);
            }
          });

      // Stop reading before asking for anything, so the very first frames have nowhere to go.
      browser.pause();
      browser.writeTextMessage(FLOOD + frames);

      try {
        floodWritten.get().get(1500, TimeUnit.MILLISECONDS);
        throw new AssertionError(
            "the editor handed over all "
                + expectedBytes
                + " bytes while the browser was not reading — the proxy is buffering them");
      } catch (TimeoutException expected) {
        // Good: the editor is parked on its drain handler because this hop stopped reading.
      }

      browser.resume();
      floodWritten.get().get(30, TimeUnit.SECONDS);
      allReceived.get(30, TimeUnit.SECONDS);
      assertEquals(expectedBytes, received.get(), "every byte, in order, once the reader comes back");
      browser.close();
    } finally {
      client.close();
    }
  }

  @Test
  public void theMachineSurfaceIsUntouchedByThisRoute() throws Exception {
    Workspace main = editorWorkspace("ordering");
    reportEditor(main.id, EditorState.State.RUNNING);
    int before = editorHits.get();

    // A request that names no editor origin falls straight through — this route claims nothing it
    // was not addressed by name. ContainerProxyRoute's own JSON 404 is the proof that it, and not
    // this catch-all, answered.
    given()
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    // And the machine surface keeps its paths even UNDER an editor host, where a running editor
    // would otherwise have served them: those routes take Vert.x's auto-sequence from 0 and this one
    // is ordered at 1000, deliberately behind them. Nothing on an editor origin ever asks for
    // /workspaces/*, so the ordering costs nothing and keeps this route out of the way of a surface
    // it has nothing to do with.
    given()
        .header("X-Forwarded-Host", host("ordering"))
        .header("X-Qits-User", "alice")
        .get("/workspaces/container/999999/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    assertEquals(before, editorHits.get());
  }
}
