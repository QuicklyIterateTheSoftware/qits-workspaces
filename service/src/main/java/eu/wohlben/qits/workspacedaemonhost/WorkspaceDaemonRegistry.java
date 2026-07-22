package eu.wohlben.qits.workspacedaemonhost;

import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jboss.logging.Logger;

/**
 * The backend's live-{@code workspace-daemon} directory (docs/epics/qits-workspace-daemon/): tracks
 * which workspaces have an open control socket, keyed by {@code workspaceId}, and routes correlated
 * request/reply traffic over it. It is the in-JVM half of the control plane — {@link
 * DaemonControlSocket} owns the WebSocket lifecycle and forwards frames here.
 *
 * <p>It implements the framework-free {@link WorkspaceDaemonLiveness} SPI so {@code domain}'s
 * {@code WorkspaceService} can observe liveness across the module boundary without depending on
 * websockets.
 *
 * <p>Part 1 wires no production caller: {@link #runCommand} exists to prove the transport
 * end-to-end (backend → {@code workspace-daemon} → backend) from the demonstration/extended tests.
 * No existing {@code docker exec} path routes through here.
 */
@ApplicationScoped
public class WorkspaceDaemonRegistry implements WorkspaceDaemonLiveness {

  private static final Logger LOG = Logger.getLogger(WorkspaceDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  private final ConcurrentHashMap<String, DaemonConnection> clients = new ConcurrentHashMap<>();

  /** The terminal outcome of a {@link #runCommand} round-trip. */
  public record CommandResult(int exitCode, String stdout, String stderr) {}

  /** Register a freshly-connected client, replacing any stale entry for the same workspace. */
  public void register(String workspaceId, WebSocketConnection connection) {
    clients.put(workspaceId, new DaemonConnection(connection));
    LOG.debugf(
        "workspace-daemon connected for workspace %s (connection %s)",
        workspaceId, connection.id());
  }

  /**
   * Drop the client for {@code workspaceId}, but only if it is still the given connection — a
   * reconnect that registered a newer socket must not be evicted by the old one's late close.
   */
  public void unregister(String workspaceId, WebSocketConnection connection) {
    clients.computeIfPresent(
        workspaceId,
        (id, existing) -> existing.connection.id().equals(connection.id()) ? null : existing);
    LOG.debugf(
        "workspace-daemon disconnected for workspace %s (connection %s)",
        workspaceId, connection.id());
  }

  @Override
  public boolean isDaemonLive(String workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    return client != null && client.connection.isOpen();
  }

  /** Handle a decoded frame from {@code workspace-daemon} for {@code workspaceId}. */
  public void onMessage(String workspaceId, WebSocketConnection connection, DaemonMessage message) {
    DaemonConnection client = clients.get(workspaceId);
    switch (message) {
      case Hello hello -> {
        LOG.infof(
            "workspace-daemon HELLO for workspace %s (repo %s, branch %s, capability %d)",
            hello.workspaceId(), hello.repoId(), hello.branch(), hello.capabilityVersion());
        connection.sendTextAndAwait(codec.encode(new Ack()));
      }
      case Heartbeat ignored -> {
        /* liveness only — the open socket is the signal */
      }
      case DaemonLog log ->
          LOG.infof("[workspace-daemon %s] %s: %s", workspaceId, log.level(), log.message());
      case CommandChunk chunk -> {
        if (client != null) {
          client.appendChunk(chunk);
        }
      }
      case CommandExit exit -> {
        if (client != null) {
          client.completeCommand(exit);
        }
      }
      case WorkspaceInfo info -> {
        if (client != null) {
          client.completeDescribe(info);
        }
      }
      // qits -> workspace-daemon requests are never received here; ignore defensively.
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
    }
  }

  /**
   * Send a {@link RunCommand} to the workspace's client and complete when its {@link CommandExit}
   * arrives, with the accumulated output. Fails fast if no client is connected. Part-1
   * demonstration seam only.
   */
  public CompletableFuture<CommandResult> runCommand(
      String workspaceId,
      java.util.List<String> argv,
      String cwd,
      java.util.Map<String, String> env) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<CommandResult> future = client.expectCommand(correlationId);
    client.connection.sendTextAndAwait(codec.encode(new RunCommand(correlationId, argv, cwd, env)));
    return future;
  }

  /**
   * Send a {@link Describe} to the workspace's client and complete with its {@link WorkspaceInfo}.
   * Part-1 demonstration seam only.
   */
  public CompletableFuture<WorkspaceInfo> describe(String workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<WorkspaceInfo> future = client.expectDescribe();
    client.connection.sendTextAndAwait(codec.encode(new Describe(correlationId)));
    return future;
  }

  /** One live client: its connection plus the in-flight correlated round-trips. */
  private static final class DaemonConnection {
    private final WebSocketConnection connection;
    private final ConcurrentHashMap<String, PendingCommand> pendingCommands =
        new ConcurrentHashMap<>();
    // WorkspaceInfo carries no correlation id (Part-1 stub), so describes are matched FIFO: a queue
    // rather than a single slot, otherwise a second concurrent describe() would overwrite the first
    // and orphan its future. Best-effort ordering is enough until a real consumer needs
    // correlation.
    private final Queue<CompletableFuture<WorkspaceInfo>> pendingDescribes =
        new ConcurrentLinkedQueue<>();

    DaemonConnection(WebSocketConnection connection) {
      this.connection = connection;
    }

    CompletableFuture<CommandResult> expectCommand(String correlationId) {
      PendingCommand pending = new PendingCommand();
      pendingCommands.put(correlationId, pending);
      return pending.future;
    }

    void appendChunk(CommandChunk chunk) {
      PendingCommand pending = pendingCommands.get(chunk.correlationId());
      if (pending != null) {
        (chunk.stream() == Stream.STDERR ? pending.stderr : pending.stdout).append(chunk.text());
      }
    }

    void completeCommand(CommandExit exit) {
      PendingCommand pending = pendingCommands.remove(exit.correlationId());
      if (pending != null) {
        pending.future.complete(
            new CommandResult(
                exit.exitCode(), pending.stdout.toString(), pending.stderr.toString()));
      }
    }

    CompletableFuture<WorkspaceInfo> expectDescribe() {
      CompletableFuture<WorkspaceInfo> future = new CompletableFuture<>();
      pendingDescribes.add(future);
      return future;
    }

    void completeDescribe(WorkspaceInfo info) {
      CompletableFuture<WorkspaceInfo> future = pendingDescribes.poll();
      if (future != null) {
        future.complete(info);
      }
    }
  }

  /** Accumulates a command's streamed output until its exit resolves the future. */
  private static final class PendingCommand {
    private final CompletableFuture<CommandResult> future = new CompletableFuture<>();
    private final StringBuilder stdout = new StringBuilder();
    private final StringBuilder stderr = new StringBuilder();
  }
}
