package eu.wohlben.qits.domain.daemon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Opens an <em>interactive</em> browser terminal onto a running daemon by attaching a fresh PTY to
 * its detached tmux session ({@link ContainerRuntime#attachDaemonCommand}) and streaming it to
 * xterm.js. This is the terminal half of Increment 2 of tmux-backed daemons: the background {@code
 * tail -F} follower keeps feeding the durable pipeline (observers, ready-pattern, per-line
 * persistence) as a <em>read-only</em> live log, while this socket gives real input/resize so the
 * user can drive full-screen apps (e.g. Quarkus dev's {@code [r]}/{@code [e]} keys).
 *
 * <p>Unlike {@link eu.wohlben.qits.domain.repository.api.TerminalSocket} — which only attaches to
 * an already-running registry command and never kills it — the attach client here is <em>ephemeral
 * and owned by this connection</em>: {@code onOpen} spawns it, {@code onClose} terminates it.
 * Killing it (a {@code docker exec} client running {@code tmux attach}) only detaches the tmux
 * client; the detached daemon session on the {@code -L} socket keeps running.
 *
 * <p>Wire protocol matches {@code TerminalSocket}: the client sends {@code
 * {"type":"data","data":…}} for keystrokes and {@code {"type":"resize","cols":N,"rows":M}} for
 * size; the server sends raw PTY output as text frames. Cross-origin handshakes are rejected
 * globally by {@code SameOriginUpgradeCheck}.
 */
@WebSocket(path = "/api/terminal/daemons/{repoId}/{worktreeId}/{daemonId}")
public class DaemonTerminalSocket {

  private static final Logger LOG = Logger.getLogger(DaemonTerminalSocket.class);

  /** The ephemeral registry session id spawned per connection, so onClose can terminate it. */
  private final Map<String, String> sessionIds = new ConcurrentHashMap<>();

  @Inject CommandRegistry registry;

  @Inject ContainerRuntime containers;

  @Inject ObjectMapper objectMapper;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @PathParam("daemonId") String daemonId,
      WebSocketConnection connection) {
    String container = containers.containerName(worktreeId, repoId);
    if (!containers.exists(container) || !containers.daemonAlive(container, daemonId)) {
      connection.sendTextAndAwait("\r\n\u001b[33mThis daemon is not running.\u001b[0m\r\n");
      connection.closeAndAwait();
      return;
    }
    String sessionId = "daemon-attach-" + connection.id();
    CommandOutputSink sink = new ConnectionSink(connection);
    // A per-connection PTY that runs `tmux attach` — no persistence (the follower owns the durable
    // log; a tmux redraw stream isn't line-framable), so exit/log are no-ops.
    registry.spawn(
        sessionId,
        container,
        containers.attachDaemonCommand(daemonId),
        Map.of("TERM", "xterm-256color"),
        (id, exitCode, terminatedManually) -> {},
        (id, sequence, channel, content, timestamp) -> {},
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
        registry.input(sessionId, node.path("data").asText().getBytes(StandardCharsets.UTF_8));
      } else if ("resize".equals(type)) {
        registry.resize(sessionId, node.path("cols").asInt(80), node.path("rows").asInt(24));
      }
    } catch (IOException e) {
      LOG.debugf(e, "Daemon terminal message parse failed for connection %s", connection.id());
    }
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    String sessionId = sessionIds.remove(connection.id());
    if (sessionId != null) {
      // Terminate the attach client (kill its process group) — that detaches the tmux client and
      // leaves the detached daemon session running.
      registry.terminate(sessionId);
    }
  }

  /** Bridges a websocket connection to the registry's framework-free output sink. */
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
