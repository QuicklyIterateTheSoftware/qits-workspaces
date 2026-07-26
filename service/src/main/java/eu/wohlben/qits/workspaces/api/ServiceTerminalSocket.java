package eu.wohlben.qits.workspaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.CommandOutputSink;
import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.WorkspaceTerminalSessions;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Opens an <em>interactive</em> browser terminal onto a running service by attaching a fresh PTY to
 * its detached tmux session ({@link ContainerRuntime#attachServiceCommand}) and streaming it to
 * xterm.js. This is the terminal half of Increment 2 of tmux-backed services: the background {@code
 * tail -F} follower keeps feeding the durable pipeline (observers, ready-pattern, per-line
 * persistence) as a <em>read-only</em> live log, while this socket gives real input/resize so the
 * user can drive full-screen apps (e.g. Quarkus dev's {@code [r]}/{@code [e]} keys).
 *
 * <p>Unlike {@link eu.wohlben.qits.workspaces.api.TerminalSocket} — which only attaches to
 * an already-running command and never kills it — the attach client here is <em>ephemeral
 * and owned by this connection</em>: {@code onOpen} spawns it, {@code onClose} terminates it.
 * Killing it (a {@code docker exec} client running {@code tmux attach}) only detaches the tmux
 * client; the detached service session on the {@code -L} socket keeps running.
 *
 * <p>Wire protocol matches {@code TerminalSocket}: the client sends {@code
 * {"type":"data","data":…}} for keystrokes and {@code {"type":"resize","cols":N,"rows":M}} for
 * size; the server sends raw PTY output as text frames. Cross-origin handshakes are rejected
 * globally by {@code SameOriginUpgradeCheck}.
 */
@WebSocket(path = "/api/terminal/services/{repoId}/{workspaceId}/{serviceId}")
public class ServiceTerminalSocket {

  private static final Logger LOG = Logger.getLogger(ServiceTerminalSocket.class);

  /** The ephemeral PTY session id spawned per connection, so onClose can terminate it. */
  private final Map<String, String> sessionIds = new ConcurrentHashMap<>();

  /**
   * The command context's PTY sessions, when one is assembled with this jar. Absent — the supported
   * standalone configuration — this socket refuses the upgrade instead of opening a dead terminal;
   * a service's live log still streams, because the follower does not go through here.
   */
  @Inject Instance<WorkspaceTerminalSessions> terminals;

  @Inject ContainerRuntime containers;

  @Inject ObjectMapper objectMapper;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("serviceId") String serviceId,
      WebSocketConnection connection) {
    if (!terminals.isResolvable()) {
      connection.sendTextAndAwait(
          "\r\n\u001b[33mInteractive service terminals are not configured.\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    String container = containers.containerName(workspaceId, repoId);
    if (!containers.exists(container) || !containers.serviceAlive(container, serviceId)) {
      connection.sendTextAndAwait("\r\n\u001b[33mThis service is not running.\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    String sessionId = "service-attach-" + connection.id();
    CommandOutputSink sink = new ConnectionSink(connection);
    // A per-connection PTY that runs `tmux attach` — no persistence (the follower owns the durable
    // log; a tmux redraw stream isn't line-framable), which is why the port has no exit or log
    // callback at all: the monorepo's call passed no-ops for both.
    terminals
        .get()
        .open(
            sessionId,
            container,
            containers.attachServiceCommand(serviceId),
            Map.of("TERM", "xterm-256color"),
            sink);
    sessionIds.put(connection.id(), sessionId);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    String sessionId = sessionIds.get(connection.id());
    if (sessionId == null) {
      return;
    }
    try {
      JsonNode node = objectMapper.readTree(message);
      String type = node.path("type").asText();
      if ("data".equals(type)) {
        terminals.get().input(sessionId, node.path("data").asText().getBytes(StandardCharsets.UTF_8));
      } else if ("resize".equals(type)) {
        terminals.get().resize(sessionId, node.path("cols").asInt(80), node.path("rows").asInt(24));
      }
    } catch (IOException e) {
      LOG.debugf(e, "Service terminal message parse failed for connection %s", connection.id());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    String sessionId = sessionIds.remove(connection.id());
    if (sessionId != null) {
      // Terminate the attach client (kill its process group) — that detaches the tmux client and
      // leaves the detached service session running.
      terminals.get().close(sessionId);
    }
  }

  /** Bridges a websocket connection to the framework-free output sink. */
  private static final class ConnectionSink implements CommandOutputSink {
    private final WebSocketConnection connection;

    ConnectionSink(WebSocketConnection connection) {
      this.connection = connection;
    }

    @Override
    public void write(String data) {
      if (connection.isOpen()) {
        connection.sendTextAndAwait(data);
      }
    }

    @Override
    public boolean isOpen() {
      return connection.isOpen();
    }
  }
}
