package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.EditorLifecycle;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The registry's half of {@code WorkspaceEditorState}: what a daemon reports about its web editor,
 * and — just as much — what happens when it stops reporting.
 *
 * <p>The caching cases go through {@code onMessage} directly, which needs no socket at all (the
 * dispatch test's idiom). The eviction case cannot: {@code unregister} is keyed on the connection
 * that registered, so it takes a real control socket to close, and closing one is the whole point
 * of the case — <b>a disconnect means nothing is known, not that the editor ended</b>. A retained
 * {@code RUNNING} would send the editor proxy at a listener whose container may have gone.
 */
@QuarkusTest
public class WorkspaceDaemonRegistryEditorStateTest {

  @Inject WorkspaceDaemonRegistry registry;

  private Vertx vertx;
  private WebSocketClient wsClient;
  private WebSocket controlSocket;

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (controlSocket != null) {
      controlSocket.close();
    }
    if (wsClient != null) {
      wsClient.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  @Test
  void aWorkspaceNoDaemonEverReportedForIsEmpty() {
    // Empty is "nothing reported", and it covers a plain workspace, a container that is not up and
    // a first frame that has not arrived — one answer, because each of them deserves the same one.
    assertTrue(registry.editorStateFor(772000L).isEmpty());
    assertTrue(registry.editorStateFor(null).isEmpty(), "no row id is no report either");
  }

  @Test
  void theLastReportedStateIsWhatTheHostAnswersWith() {
    Long id = 772001L;

    registry.onMessage(id, null, new EditorState(EditorState.State.STARTING));
    assertEquals(EditorLifecycle.STARTING, registry.editorStateFor(id).orElseThrow());

    // A live report always wins — the same rule the agent-activity rollup carries.
    registry.onMessage(id, null, new EditorState(EditorState.State.RUNNING));
    assertEquals(EditorLifecycle.RUNNING, registry.editorStateFor(id).orElseThrow());

    // ENDED is KEPT rather than evicted: it is what lets a splash stop waiting and say the editor
    // is not coming back, instead of spinning for the container's lifetime.
    registry.onMessage(id, null, new EditorState(EditorState.State.ENDED));
    assertEquals(EditorLifecycle.ENDED, registry.editorStateFor(id).orElseThrow());
  }

  @Test
  void aStateThisHostCannotNameReadsAsNothingReported() {
    Long id = 772002L;
    registry.onMessage(id, null, new EditorState(EditorState.State.RUNNING));

    // The wire carries a String precisely so a newer daemon can say something new without failing a
    // frame. "Nothing reported" is the honest reading of a value this backend has never heard of —
    // and dropping the entry is what keeps a stale RUNNING from outliving the transition that said
    // otherwise.
    registry.onMessage(id, null, new EditorState("REBUILDING"));
    assertTrue(registry.editorStateFor(id).isEmpty());
  }

  @Test
  void aDisconnectDropsTheReportRatherThanEndingTheEditor() throws Exception {
    Long id = 772003L;
    connectFakeDaemon(id);
    controlSocket.writeTextMessage(
        new JsonObject(DaemonCodec.encode(new EditorState(EditorState.State.RUNNING))).encode());
    awaitState(id, EditorLifecycle.RUNNING);

    controlSocket.close();
    controlSocket = null;

    // Not ENDED: the daemon re-reports on every connect, so the correct state of the host's
    // knowledge after a disconnect is that it has none.
    awaitState(id, null);
  }

  // --- helpers ------------------------------------------------------------------------------------

  private void connectFakeDaemon(Long workspaceId) throws Exception {
    controlSocket =
        await(wsClient.connect(RestAssured.port, "127.0.0.1", "/workspaces/daemon/" + workspaceId));
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello(
                        "ws-" + workspaceId,
                        "repo",
                        "work",
                        "master",
                        DaemonProtocol.CAPABILITY_VERSION,
                        "test",
                        null)))
            .encode());
  }

  /** Poll until the registry answers {@code expected} ({@code null} meaning "nothing reported"). */
  private void awaitState(Long workspaceId, EditorLifecycle expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (registry.editorStateFor(workspaceId).orElse(null) == expected) {
        return;
      }
      Thread.sleep(25);
    }
    assertEquals(expected, registry.editorStateFor(workspaceId).orElse(null));
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
