package eu.wohlben.qits.workspacedaemonhost;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The backend endpoint each workspace's in-container {@code workspace-daemon} dials on boot
 * (docs/epics/qits-workspace-daemon/). It owns only the WebSocket lifecycle and JSON framing —
 * {@link WorkspaceDaemonRegistry} owns the state and correlated traffic.
 *
 * <p>Reachability mirrors the container's other channels ({@code /git}, {@code /api/otel}, {@code
 * /mcp}): a plain {@code ws://} connection over qits-net on the main HTTP port, made token-free in
 * {@code PublicPaths}. {@code SameOriginUpgradeCheck} permits it because {@code workspace-daemon}
 * is a non-browser client and sends no {@code Origin}.
 *
 * <p>Part 1: the socket carries the {@code Hello}/{@code Ack} handshake, heartbeats, {@code
 * workspace-daemon}'s own logs, and — for the demonstration/extended tests only — a {@code
 * RunCommand} round-trip. It drives no existing behaviour; the {@code docker exec} paths are
 * untouched.
 */
@WebSocket(path = "/api/workspace-daemon/{workspaceId}")
public class DaemonControlSocket {

  private static final Logger LOG = Logger.getLogger(DaemonControlSocket.class);

  @Inject WorkspaceDaemonRegistry registry;

  @Inject DaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("workspaceId") String workspaceId, WebSocketConnection connection) {
    registry.register(workspaceId, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message,
      @PathParam("workspaceId") String workspaceId,
      WebSocketConnection connection) {
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable workspace-daemon frame for workspace %s: %s",
          workspaceId, e.getMessage());
      return;
    }
    registry.onMessage(workspaceId, connection, decoded);
  }

  @OnClose
  public void onClose(
      @PathParam("workspaceId") String workspaceId, WebSocketConnection connection) {
    registry.unregister(workspaceId, connection);
  }
}
