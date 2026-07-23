package eu.wohlben.qits.workspacedaemonhost;

import eu.wohlben.qits.domain.repository.control.ProvisionResult;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonProvisioner;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonLog;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Describe;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The backend's live-{@code workspace-daemon} directory (docs/epics/qits-workspace-daemon/): tracks
 * which workspaces have an open control socket, keyed by {@code workspaceId}, and routes correlated
 * request/reply traffic over it. It is the in-JVM half of the control plane — {@link
 * DaemonControlSocket} owns the WebSocket lifecycle and forwards frames here.
 *
 * <p>It implements two framework-free {@code domain} SPIs so {@code WorkspaceService} can reach
 * across the module boundary without depending on websockets: {@link WorkspaceDaemonLiveness}
 * (observational) and {@link WorkspaceDaemonProvisioner} — the latter the first production caller,
 * awaiting the daemon's <b>autonomous self-provision</b> (clone + submodules on boot) and streaming
 * its output to the {@code clone} process segment (docs/epics/qits-workspace-daemon/ Part 1).
 *
 * <p>{@link #runCommand}/{@link #describe} remain Part-1 demonstration seams (backend → {@code
 * workspace-daemon} → backend); no existing {@code docker exec} path routes through them yet.
 */
@ApplicationScoped
public class WorkspaceDaemonRegistry
    implements WorkspaceDaemonLiveness, WorkspaceDaemonProvisioner {

  private static final Logger LOG = Logger.getLogger(WorkspaceDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  private final ConcurrentHashMap<String, DaemonConnection> clients = new ConcurrentHashMap<>();

  /**
   * In-flight autonomous self-provisions, keyed by {@code workspaceId} — <b>on the registry, not on
   * a {@link DaemonConnection}</b>, because a provision (a real clone) outlives a socket bounce:
   * the daemon keeps cloning across a reconnect and reports {@code Provisioned} on whichever
   * connection is up when it finishes. A connection-scoped slot would be orphaned by {@link
   * #unregister}.
   */
  private final ConcurrentHashMap<String, PendingProvision> provisions = new ConcurrentHashMap<>();

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
        if (DaemonProtocol.PROVISION_CORRELATION_ID.equals(chunk.correlationId())) {
          streamProvisionOutput(workspaceId, chunk);
        } else if (client != null) {
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
      case Provisioned provisioned ->
          completeProvision(workspaceId, ProvisionResult.ok(provisioned.head()));
      case ProvisionFailed failed ->
          completeProvision(workspaceId, ProvisionResult.failed(failed.message()));
      // qits -> workspace-daemon requests are never received here; ignore defensively.
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
    }
  }

  /**
   * Feed a provision's streamed clone/submodule output to the awaiting host's {@code clone}
   * segment.
   */
  private void streamProvisionOutput(String workspaceId, CommandChunk chunk) {
    PendingProvision pending = provisions.get(workspaceId);
    if (pending == null || pending.onLine == null) {
      return; // no awaiter (yet) — provision output is best-effort UI, the exit is what matters
    }
    for (String line : chunk.text().split("\n", -1)) {
      if (!line.isEmpty()) {
        pending.onLine.accept(line);
      }
    }
  }

  /**
   * Complete the workspace's provision with {@code result}, if an awaiter is registered. The
   * awaiter always registers first — {@code provisionContainer} calls {@link #awaitProvision}
   * synchronously right after {@code docker run}, long before the daemon can finish cloning — so a
   * terminal with no pending slot is a late straggler (a connect that beat the timeout, or a
   * duplicate on restart) and is dropped rather than retained: {@code computeIfAbsent} here would
   * create an entry no awaiter ever removes, leaking the map. A duplicate on an already-completed
   * future is a harmless no-op.
   */
  private void completeProvision(String workspaceId, ProvisionResult result) {
    PendingProvision pending = provisions.get(workspaceId);
    if (pending != null) {
      pending.future.complete(result);
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

  @Override
  public Optional<ProvisionResult> awaitProvision(
      String workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine) {
    // Register the pending slot BEFORE waiting, so streamed chunks and the terminal event that
    // arrive during the wait land on it (and survive a socket reconnect — the slot lives on the
    // registry, not the connection).
    PendingProvision pending =
        provisions.computeIfAbsent(workspaceId, id -> new PendingProvision());
    pending.onLine = onLine;
    try {
      // If the terminal event somehow already arrived, take it without waiting on liveness. Else
      // wait
      // for a daemon to dial home; if none does within the window, this is a stale image /
      // no-backend
      // case — return empty so the caller falls back to the host-driven clone (degradation
      // contract).
      if (!pending.future.isDone() && !awaitLive(workspaceId, connectTimeout)) {
        return Optional.empty();
      }
      return Optional.of(pending.future.get(provisionTimeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      // Live but silent past the deadline: fail (the caller removes the container + marks FAILED —
      // no fallback, the daemon owns a possibly half-populated /workspace).
      return Optional.of(
          ProvisionResult.failed(
              "workspace-daemon did not finish provisioning within "
                  + provisionTimeout.toSeconds()
                  + "s"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of(ProvisionResult.failed("interrupted while awaiting provisioning"));
    } catch (ExecutionException e) {
      return Optional.of(ProvisionResult.failed(String.valueOf(e.getCause())));
    } finally {
      provisions.remove(workspaceId, pending);
    }
  }

  /**
   * Test hook: whether an {@link #awaitProvision} awaiter (with its line sink) is registered for
   * {@code workspaceId}. Lets a test send provision output only once routing is in place, since
   * streamed chunks are otherwise best-effort (dropped before an awaiter registers).
   */
  boolean isAwaitingProvision(String workspaceId) {
    PendingProvision pending = provisions.get(workspaceId);
    return pending != null && pending.onLine != null;
  }

  /** Poll for a live daemon up to {@code timeout}; true once one is connected. */
  private boolean awaitLive(String workspaceId, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (isDaemonLive(workspaceId)) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return isDaemonLive(workspaceId);
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

  /**
   * One in-flight autonomous self-provision: the future the awaiting host blocks on, plus the
   * {@code clone}-segment line sink its streamed output is routed to. Keyed by {@code workspaceId}
   * so it survives a socket reconnect mid-clone.
   */
  private static final class PendingProvision {
    private final CompletableFuture<ProvisionResult> future = new CompletableFuture<>();
    private volatile Consumer<String> onLine;
  }
}
