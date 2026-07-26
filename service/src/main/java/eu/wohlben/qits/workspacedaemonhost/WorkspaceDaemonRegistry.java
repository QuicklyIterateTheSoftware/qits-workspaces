package eu.wohlben.qits.workspacedaemonhost;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.agent.control.AgentActivityState;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.repository.control.ProvisionResult;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceAgentActivity;
import eu.wohlben.qits.domain.repository.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.domain.repository.control.WorkspaceConfigReader;
import eu.wohlben.qits.domain.repository.control.WorkspaceConfigView;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonProvisioner;
import eu.wohlben.qits.domain.repository.control.WorkspaceGitStatus;
import eu.wohlben.qits.domain.repository.control.WorkspaceGitSync;
import eu.wohlben.qits.domain.repository.control.WorkspaceServiceDriver;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
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
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.PullBranch;
import eu.wohlben.qits.workspacedaemon.protocol.RunBootstrap;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import eu.wohlben.qits.workspacedaemon.protocol.SignalService;
import eu.wohlben.qits.workspacedaemon.protocol.StartService;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import eu.wohlben.qits.workspacedaemon.protocol.WorkspaceInfo;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The backend's live-{@code workspace-daemon} directory (docs/epics/qits-workspace-daemon/): tracks
 * which workspaces have an open control socket, keyed by {@code workspaceId}, and routes correlated
 * request/reply traffic over it. It is the in-JVM half of the control plane — {@link
 * DaemonControlSocket} owns the WebSocket lifecycle and forwards frames here.
 *
 * <p>It implements framework-free {@code domain} SPIs so {@code WorkspaceService} (and other read
 * paths) can reach across the module boundary without depending on websockets: {@link
 * WorkspaceDaemonLiveness} (observational), {@link WorkspaceDaemonProvisioner} (awaits the daemon's
 * <b>autonomous self-provision</b> — clone + submodules on boot — streaming its output to the
 * {@code clone} process segment), {@link WorkspaceConfigReader} (Part 2 — reads the workspace's
 * in-container {@code .qits-config.yml} on demand, so config becomes the branch's config), and
 * {@link WorkspaceBootstrapDriver} (Part 3 — awaits the daemon's autonomous boot-time bootstrap
 * chain and re-triggers it on demand, streaming each step's progress to the host).
 *
 * <p>{@link #runCommand}/{@link #describe} remain Part-1 demonstration seams (backend → {@code
 * workspace-daemon} → backend); no existing {@code docker exec} path routes through them yet.
 */
@ApplicationScoped
public class WorkspaceDaemonRegistry
    implements WorkspaceDaemonLiveness,
        WorkspaceDaemonProvisioner,
        WorkspaceConfigReader,
        WorkspaceBootstrapDriver,
        WorkspaceServiceDriver,
        WorkspaceGitStatus,
        WorkspaceAgentActivity,
        WorkspaceDaemonInfo,
        WorkspaceGitSync {

  private static final Logger LOG = Logger.getLogger(WorkspaceDaemonRegistry.class);

  @Inject DaemonMessageCodec codec;

  @Inject ObjectMapper objectMapper;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * The session-lineage sink for the {@code SessionStart} activity hook — the behaviour-preserving
   * half of retiring the old direct-to-host {@code /agent-session} hook. {@code service} depends on
   * {@code domain}, so this framework-free service is injected directly (the Kimi ACP launch path
   * already calls it in-JVM). Guarded at the call site so a bad/late report can't tear down the
   * control socket.
   */
  @Inject CommandService commandService;

  /**
   * Last working-tree cleanliness each live daemon reported ({@link GitStatus}). In-memory only —
   * known while the daemon is connected (container RUNNING), cleared on {@link #unregister}, and
   * re-reported by the daemon on reconnect. Surfaced through {@link WorkspaceGitStatus#isClean}.
   */
  private final ConcurrentHashMap<String, Boolean> gitClean = new ConcurrentHashMap<>();

  /**
   * Last coding-agent activity each live daemon reported ({@link AgentActivity}), keyed by {@code
   * commandId} (multiple agents can run per workspace). In-memory only — same lifecycle as {@link
   * #gitClean}: populated while the daemon is connected, evicted on {@link #unregister} (and on an
   * {@code ENDED} report), re-reported on reconnect. Surfaced as a per-workspace rollup through
   * {@link WorkspaceAgentActivity#activityFor}.
   */
  private final ConcurrentHashMap<String, ActivityEntry> agentActivity = new ConcurrentHashMap<>();

  /** One tracked agent command's activity, plus the workspace it belongs to (for the rollup). */
  private record ActivityEntry(String workspaceId, AgentActivityState state) {}

  /** How long a {@link #readConfig} waits for the live daemon's {@link ConfigView} reply. */
  @ConfigProperty(name = "qits.workspace.config.describe-timeout-ms", defaultValue = "10000")
  long configDescribeTimeoutMs;

  private final ConcurrentHashMap<String, DaemonConnection> clients = new ConcurrentHashMap<>();

  /**
   * In-flight autonomous self-provisions, keyed by {@code workspaceId} — <b>on the registry, not on
   * a {@link DaemonConnection}</b>, because a provision (a real clone) outlives a socket bounce:
   * the daemon keeps cloning across a reconnect and reports {@code Provisioned} on whichever
   * connection is up when it finishes. A connection-scoped slot would be orphaned by {@link
   * #unregister}.
   */
  private final ConcurrentHashMap<String, PendingProvision> provisions = new ConcurrentHashMap<>();

  /**
   * In-flight bootstrap chains, keyed by {@code workspaceId} — like {@link #provisions}, on the
   * registry (a chain, a long {@code mvn install}, outlives a socket bounce). Unlike provisioning,
   * the boot-time chain is <b>autonomous</b>: the daemon runs it right after the self-clone, so it
   * can begin — and a fast/empty chain can finish — before the host's async observer registers its
   * await. So a {@link PendingBootstrap} buffers step events until a sink registers, and {@link
   * #completeBootstrap} retains the terminal (creating the slot if absent) rather than dropping it.
   * {@link #awaitProvision} clears any stale slot at the start of each provision cycle so a boot
   * awaiter never picks up a previous cycle's retained terminal.
   */
  private final ConcurrentHashMap<String, PendingBootstrap> bootstraps = new ConcurrentHashMap<>();

  /**
   * Host coordinators subscribed to service (dev-server) lifecycle events (Part 4). The daemon owns
   * the process lifecycle and streams every transition; the host {@code ServiceSupervisor} projects
   * them. Registered once at host startup, so it's a small, stable list — {@code
   * CopyOnWriteArrayList} makes the socket-thread iteration lock-free.
   */
  private final CopyOnWriteArrayList<WorkspaceServiceDriver.ServiceEventSink> serviceSinks =
      new CopyOnWriteArrayList<>();

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
    // The daemon is gone: its cached working-tree status is unknown until it reconnects and
    // re-reports (RUNNING-only semantics; the UI shows no badge in the meantime).
    gitClean.remove(workspaceId);
    // Likewise drop every tracked agent activity for this workspace (re-reported on reconnect).
    agentActivity.values().removeIf(entry -> entry.workspaceId().equals(workspaceId));
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
            "workspace-daemon HELLO for workspace %s (repo %s, branch %s, capability %d, daemon"
                + " %s built %s)",
            hello.workspaceId(),
            hello.repoId(),
            hello.branch(),
            hello.capabilityVersion(),
            hello.daemonVersion(),
            hello.daemonBuildTime());
        // Remember the repository the daemon serves: service (dev-server) events carry only the
        // service NAME, and the host keys supervision state by (repoId, workspaceId, id) — so the
        // ServiceEventSink needs repoId to resolve the name to a repository service definition. The
        // daemon's announced build identity is retained for the workspace registry
        // (WorkspaceDaemonInfo).
        if (client != null) {
          client.repoId = hello.repoId();
          client.daemonVersion = hello.daemonVersion();
          client.daemonBuildTime = parseInstant(hello.daemonBuildTime());
        }
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
        } else if (chunk.correlationId() != null
            && chunk.correlationId().startsWith(DaemonProtocol.BOOTSTRAP_CORRELATION_PREFIX)) {
          streamBootstrapOutput(workspaceId, chunk);
        } else if (chunk.correlationId() != null
            && chunk.correlationId().startsWith(DaemonProtocol.SERVICE_CORRELATION_PREFIX)) {
          streamServiceOutput(workspaceId, client, chunk);
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
      case ConfigView view -> {
        if (client != null) {
          client.completeConfig(view);
        }
      }
      case Provisioned provisioned ->
          completeProvision(workspaceId, ProvisionResult.ok(provisioned.head()));
      case ProvisionFailed failed ->
          completeProvision(workspaceId, ProvisionResult.failed(failed.message()));
      case BootstrapStep step -> routeBootstrapStep(workspaceId, step);
      case BootstrapOutcome outcome -> routeBootstrapOutcome(workspaceId, outcome);
      case Bootstrapped done -> completeBootstrap(workspaceId, done.ok());
      case ServiceTransition event -> routeServiceState(workspaceId, client, event);
      case GitStatus status -> onGitStatus(workspaceId, client, status);
      case AgentActivity activity -> onAgentActivity(workspaceId, client, activity);
      // qits -> workspace-daemon requests are never received here; ignore defensively.
      case Ack ignored -> {}
      case RunCommand ignored -> {}
      case Describe ignored -> {}
      case DescribeConfig ignored -> {}
      case RunBootstrap ignored -> {}
      case StartService ignored -> {}
      case SignalService ignored -> {}
      case PullBranch ignored -> {}
    }
  }

  /**
   * Cache a working-tree status report and fan out its two consequences. Every report means the
   * daemon's working-tree marker moved, so it re-homes the old host watcher's trigger: a {@link
   * WorkspaceChangeHint.Topic#FILES} hint on the workspace channel makes the detail view re-fetch
   * {@code /files} + {@code /detection}. The {@code clean} flag is cached for {@link #isClean}, and
   * a {@link WorkspaceChangeHint.Topic#GIT_STATUS} hint on the repository channel refreshes the
   * branch-tree dirty badge — but only when the flag actually flipped, so a dirty→dirty content
   * edit nudges {@code FILES} without re-invalidating the whole workspace list.
   */
  private void onGitStatus(String workspaceId, DaemonConnection client, GitStatus status) {
    String repoId = client != null ? client.repoId : null;
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.FILES);
    Boolean previous = gitClean.put(workspaceId, status.clean());
    if (previous == null || previous != status.clean()) {
      changePublisher.fire(repoId, null, WorkspaceChangeHint.Topic.GIT_STATUS);
    }
  }

  @Override
  public Optional<Boolean> isClean(String workspaceId) {
    return Optional.ofNullable(gitClean.get(workspaceId));
  }

  /**
   * Handle one agent-activity report into its two sinks. First (behaviour-preserving) sink: a
   * {@code SessionStart} still records the session-lineage row via {@link
   * CommandService#reportAgentSession} — the same DB write the retired direct {@code
   * /agent-session} hook produced, now driven off this daemon frame. Wrapped in try/catch (it
   * throws {@code BadRequestException}/{@code NotFoundException} for an invalid session id or an
   * unknown/stopped command) so an escape can't close the control socket, mirroring {@link
   * PendingBootstrap#dispatch}.
   *
   * <p>Second sink: the in-memory rollup cache. The state is stored per {@code commandId} ({@code
   * ENDED} evicts it), and a {@link WorkspaceChangeHint.Topic#AGENT_ACTIVITY} hint is fired only
   * when the workspace's rollup actually flips — so a same-state re-report (reconnect replay)
   * doesn't churn the UI. The flip fires on three channels at once, one per route that renders the
   * agent-activity bar: the workspace channel (the open workspace detail), the repository channel
   * {@code (repoId, null)} (the repository detail, mirroring {@code GIT_STATUS}), and the global
   * channel {@code (null, null)} (the project detail, which spans repositories and holds ONE
   * connection instead of one per repo).
   */
  private void onAgentActivity(
      String workspaceId, DaemonConnection client, AgentActivity activity) {
    String repoId = client != null ? client.repoId : null;
    if ("SessionStart".equals(activity.hookEvent()) && activity.sessionId() != null) {
      try {
        commandService.reportAgentSession(
            activity.commandId(), activity.sessionId(), activity.transcriptPath());
      } catch (RuntimeException e) {
        LOG.debugf(
            "agent-session lineage report failed for command %s: %s",
            activity.commandId(), e.getMessage());
      }
    }
    AgentActivityState state = parseState(activity.state());
    if (state == null) {
      return; // unknown state string — lineage above still ran; nothing to cache/flip
    }
    AgentActivityState before = rollup(workspaceId);
    if (state == AgentActivityState.ENDED) {
      agentActivity.remove(activity.commandId());
    } else {
      agentActivity.put(activity.commandId(), new ActivityEntry(workspaceId, state));
    }
    if (before != rollup(workspaceId)) {
      changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
      changePublisher.fire(repoId, null, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
      changePublisher.fire(null, null, WorkspaceChangeHint.Topic.AGENT_ACTIVITY);
    }
  }

  /**
   * The per-workspace rollup: BUSY &gt; WAITING &gt; IDLE across its tracked commands; else null.
   */
  private AgentActivityState rollup(String workspaceId) {
    boolean waiting = false;
    boolean idle = false;
    for (ActivityEntry entry : agentActivity.values()) {
      if (!entry.workspaceId().equals(workspaceId)) {
        continue;
      }
      switch (entry.state()) {
        case BUSY -> {
          return AgentActivityState.BUSY; // highest precedence — short-circuit
        }
        case WAITING -> waiting = true;
        case IDLE -> idle = true;
        case ENDED -> {
          /* never stored (ENDED evicts), but be exhaustive */
        }
      }
    }
    if (waiting) {
      return AgentActivityState.WAITING;
    }
    return idle ? AgentActivityState.IDLE : null;
  }

  private static AgentActivityState parseState(String state) {
    if (state == null) {
      return null;
    }
    try {
      return AgentActivityState.valueOf(state);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public Optional<AgentActivityState> activityFor(String workspaceId) {
    return Optional.ofNullable(rollup(workspaceId));
  }

  @Override
  public Optional<WorkspaceDaemonInfo.Info> lookup(String workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty();
    }
    return Optional.of(
        new WorkspaceDaemonInfo.Info(
            client.connectedAt, client.daemonVersion, client.daemonBuildTime));
  }

  @Override
  public Collection<WorkspaceDaemonInfo.Info> all() {
    // Only open sockets — mirrors lookup's guard so a lingering-but-closed entry never counts.
    return clients.values().stream()
        .filter(client -> client.connection.isOpen())
        .map(
            client ->
                new WorkspaceDaemonInfo.Info(
                    client.connectedAt, client.daemonVersion, client.daemonBuildTime))
        .toList();
  }

  /**
   * Parse the daemon's ISO-8601 build-time string to an {@code Instant}, tolerating {@code null}
   * (older image / unfiltered dev jar) and a malformed value (never fail a registration over a
   * cosmetic field) — either yields {@code null}, surfaced as "unknown build time".
   */
  private static Instant parseInstant(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso);
    } catch (java.time.format.DateTimeParseException e) {
      LOG.debugf("workspace-daemon reported unparseable build time '%s': %s", iso, e.getMessage());
      return null;
    }
  }

  @Override
  public void pullFromOrigin(String workspaceId, String branch) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      // No live daemon to pull — the checkout syncs on its next host git op (fast-forward /
      // merge-parent-in), so a missed notification never loses data. Fire-and-forget.
      LOG.debugf("pullFromOrigin('%s'): no workspace-daemon live for %s", branch, workspaceId);
      return;
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(codec.encode(new PullBranch(correlationId, branch)));
  }

  /** Fan a service's lifecycle transition out to every subscribed host coordinator. */
  private void routeServiceState(
      String workspaceId, DaemonConnection client, ServiceTransition event) {
    if (serviceSinks.isEmpty()) {
      return;
    }
    String repoId = client != null ? client.repoId : null;
    for (WorkspaceServiceDriver.ServiceEventSink sink : serviceSinks) {
      sink.onState(repoId, workspaceId, event.id(), event.state(), event.exitCode());
    }
  }

  /**
   * Fan a running service's streamed output (correlation {@code service:<name>}) out to the sinks.
   */
  private void streamServiceOutput(
      String workspaceId, DaemonConnection client, CommandChunk chunk) {
    if (serviceSinks.isEmpty()) {
      return;
    }
    String repoId = client != null ? client.repoId : null;
    String name =
        chunk.correlationId().substring(DaemonProtocol.SERVICE_CORRELATION_PREFIX.length());
    for (String line : chunk.text().split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      for (WorkspaceServiceDriver.ServiceEventSink sink : serviceSinks) {
        sink.onLine(repoId, workspaceId, name, chunk.stream().name(), line);
      }
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

  /** Route one bootstrap step's phase change to the awaiting sink (buffered until it registers). */
  private void routeBootstrapStep(String workspaceId, BootstrapStep step) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .deliver(sink -> sink.onStep(step.name(), step.phase()));
  }

  /**
   * Route one bootstrap step's terminal outcome to the awaiting sink (buffered until it registers).
   */
  private void routeBootstrapOutcome(String workspaceId, BootstrapOutcome outcome) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .deliver(sink -> sink.onOutcome(outcome.name(), outcome.outcome(), outcome.exitCode()));
  }

  /** Feed a bootstrap step's streamed output (correlation {@code bootstrap:<name>}) to the sink. */
  private void streamBootstrapOutput(String workspaceId, CommandChunk chunk) {
    String name =
        chunk.correlationId().substring(DaemonProtocol.BOOTSTRAP_CORRELATION_PREFIX.length());
    PendingBootstrap pending =
        bootstraps.computeIfAbsent(workspaceId, id -> new PendingBootstrap());
    for (String line : chunk.text().split("\n", -1)) {
      if (!line.isEmpty()) {
        pending.deliver(sink -> sink.onLine(name, line));
      }
    }
  }

  /**
   * Complete the workspace's bootstrap chain — <b>complete-or-retain</b>: unlike {@link
   * #completeProvision}, the autonomous boot chain can finish before the host's async observer
   * registers its await, so a terminal with no slot creates one (retaining the result) rather than
   * dropping it. The awaiter (or {@link #awaitProvision}'s next-cycle clear) removes it.
   */
  private void completeBootstrap(String workspaceId, boolean ok) {
    bootstraps
        .computeIfAbsent(workspaceId, id -> new PendingBootstrap())
        .future
        .complete(new Result(ok));
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

  /**
   * Send a {@link DescribeConfig} to the workspace's client and complete with its {@link
   * ConfigView} — the workspace's in-container {@code .qits-config.yml}, correlated by id (unlike
   * the FIFO {@link #describe}). Fails fast if no client is connected.
   */
  public CompletableFuture<ConfigView> describeConfig(String workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("No workspace-daemon connected for workspace " + workspaceId));
    }
    String correlationId = UUID.randomUUID().toString();
    CompletableFuture<ConfigView> future = client.expectConfig(correlationId);
    client.connection.sendTextAndAwait(codec.encode(new DescribeConfig(correlationId)));
    return future;
  }

  @Override
  public Optional<WorkspaceConfigView> readConfig(String workspaceId) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty(); // no daemon live to read the checkout — caller uses its default view
    }
    try {
      ConfigView view =
          describeConfig(workspaceId).get(configDescribeTimeoutMs, TimeUnit.MILLISECONDS);
      String warning = view.warning();
      QitsConfig config;
      try {
        config = objectMapper.readValue(view.configJson(), QitsConfig.class);
      } catch (Exception e) {
        // The daemon reported the file valid but its JSON doesn't map to a QitsConfig (e.g. an
        // unknown enum, which the daemon normalizes but does not validate): surface it as the
        // degrade-loudly warning and fall back to the empty config, same end state as a structural
        // parse error in-container.
        config = QitsConfig.EMPTY;
        warning = warning != null ? warning : "invalid qits config: " + e.getMessage();
      }
      return Optional.of(new WorkspaceConfigView(config, warning));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (TimeoutException | ExecutionException e) {
      // A live daemon that didn't answer in time (or the socket dropped mid-read): treat as "no
      // config available right now" rather than a hard failure — the read path shows its default.
      LOG.debugf("workspace-daemon config read failed for %s: %s", workspaceId, e.getMessage());
      return Optional.empty();
    }
  }

  @Override
  public Optional<ProvisionResult> awaitProvision(
      String repoId, // the daemon self-identifies over the socket; awaits are keyed by workspaceId
      String workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine) {
    // A new provision cycle: drop any stale bootstrap slot from a previous cycle (e.g. a
    // kill-switch
    // run whose retained terminal was never awaited), so this cycle's boot awaiter can't pick it
    // up.
    bootstraps.remove(workspaceId);
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

  /**
   * Test hook: whether a {@link PendingBootstrap} slot exists for {@code workspaceId} — lets a test
   * confirm the daemon's autonomous chain events have landed on the registry before it registers
   * its await (the retain/buffer race the autonomous model must survive).
   */
  boolean isBootstrapPending(String workspaceId) {
    return bootstraps.containsKey(workspaceId);
  }

  @Override
  public Optional<Result> awaitBootstrap(
      String repoId,
      String workspaceId,
      StepSink sink,
      Duration connectTimeout,
      Duration chainTimeout) {
    // Register the sink so buffered events (steps that beat this await, since the daemon runs the
    // chain autonomously) replay onto it, and a terminal that already arrived is picked up.
    PendingBootstrap pending =
        bootstraps.computeIfAbsent(workspaceId, id -> new PendingBootstrap());
    pending.setSink(sink);
    return awaitBootstrapFuture(workspaceId, pending, connectTimeout, chainTimeout);
  }

  @Override
  public Optional<Result> runBootstrap(
      String repoId, String workspaceId, String name, StepSink sink, Duration chainTimeout) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      return Optional.empty(); // no daemon live to run it
    }
    // A manual re-run only starts when the daemon receives RunBootstrap, so the awaiter always
    // registers first: replace any stale slot with a fresh one, set the sink, then send.
    PendingBootstrap pending = new PendingBootstrap();
    pending.setSink(sink);
    bootstraps.put(workspaceId, pending);
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(codec.encode(new RunBootstrap(correlationId, name)));
    // The daemon is already live (we just sent to it), so no connect wait.
    return awaitBootstrapFuture(workspaceId, pending, Duration.ZERO, chainTimeout);
  }

  private Optional<Result> awaitBootstrapFuture(
      String workspaceId,
      PendingBootstrap pending,
      Duration connectTimeout,
      Duration chainTimeout) {
    try {
      if (!pending.future.isDone()
          && !connectTimeout.isZero()
          && !awaitLive(workspaceId, connectTimeout)) {
        return Optional.empty(); // no daemon became live — the chain never ran
      }
      return Optional.of(pending.future.get(chainTimeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException e) {
      return Optional.of(new Result(false)); // live but silent past the deadline ⇒ treat as failed
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.of(new Result(false));
    } catch (ExecutionException e) {
      return Optional.of(new Result(false));
    } finally {
      bootstraps.remove(workspaceId, pending);
    }
  }

  @Override
  public void subscribe(WorkspaceServiceDriver.ServiceEventSink sink) {
    serviceSinks.add(sink);
  }

  @Override
  public void startService(
      String workspaceId, String serviceName, String script, Map<String, String> env) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("startService('%s'): no workspace-daemon live for %s", serviceName, workspaceId);
      return; // daemon-backed mode requires a live daemon; the host falls back to tmux otherwise
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(
        codec.encode(new StartService(correlationId, serviceName, script, env)));
  }

  @Override
  public void signalService(String workspaceId, String serviceName, String signal) {
    DaemonConnection client = clients.get(workspaceId);
    if (client == null || !client.connection.isOpen()) {
      LOG.debugf("signalService('%s'): no workspace-daemon live for %s", serviceName, workspaceId);
      return;
    }
    String correlationId = UUID.randomUUID().toString();
    client.connection.sendTextAndAwait(
        codec.encode(new SignalService(correlationId, serviceName, signal)));
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

    /** When this control socket registered — the workspace's "connected since" (registry). */
    private final Instant connectedAt = Instant.now();

    /** The repository the daemon serves, learned from its {@link Hello} — see {@code onMessage}. */
    private volatile String repoId;

    /**
     * The daemon binary's build identity announced in its {@link Hello} (registry); may be null.
     */
    private volatile String daemonVersion;

    private volatile Instant daemonBuildTime;

    private final ConcurrentHashMap<String, PendingCommand> pendingCommands =
        new ConcurrentHashMap<>();
    // WorkspaceInfo carries no correlation id (Part-1 stub), so describes are matched FIFO: a queue
    // rather than a single slot, otherwise a second concurrent describe() would overwrite the first
    // and orphan its future. Best-effort ordering is enough until a real consumer needs
    // correlation.
    private final Queue<CompletableFuture<WorkspaceInfo>> pendingDescribes =
        new ConcurrentLinkedQueue<>();
    // ConfigView DOES echo the request's correlation id, so config reads are matched by id (an
    // improvement over the FIFO pendingDescribes stub) — concurrent readConfig calls never cross.
    private final ConcurrentHashMap<String, CompletableFuture<ConfigView>> pendingConfigs =
        new ConcurrentHashMap<>();

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

    CompletableFuture<ConfigView> expectConfig(String correlationId) {
      CompletableFuture<ConfigView> future = new CompletableFuture<>();
      pendingConfigs.put(correlationId, future);
      return future;
    }

    void completeConfig(ConfigView view) {
      CompletableFuture<ConfigView> future = pendingConfigs.remove(view.correlationId());
      if (future != null) {
        future.complete(view);
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

  /**
   * One in-flight bootstrap chain: the future the awaiting host blocks on, plus a buffer of step
   * events delivered before a sink registered (the autonomous boot chain can start streaming before
   * the host's async observer awaits). {@link #setSink} replays the buffer and thereafter delivers
   * live; {@link #deliver} buffers until then. Keyed by {@code workspaceId} so it survives a socket
   * reconnect mid-chain.
   */
  private static final class PendingBootstrap {
    private final CompletableFuture<WorkspaceBootstrapDriver.Result> future =
        new CompletableFuture<>();
    private final List<Consumer<WorkspaceBootstrapDriver.StepSink>> buffered = new ArrayList<>();
    private WorkspaceBootstrapDriver.StepSink sink;

    synchronized void setSink(WorkspaceBootstrapDriver.StepSink sink) {
      this.sink = sink;
      for (Consumer<WorkspaceBootstrapDriver.StepSink> event : buffered) {
        dispatch(event);
      }
      buffered.clear();
    }

    synchronized void deliver(Consumer<WorkspaceBootstrapDriver.StepSink> event) {
      if (sink != null) {
        dispatch(event);
      } else {
        buffered.add(event);
      }
    }

    /**
     * Run a sink callback, swallowing any exception. The callbacks run on the websockets-next
     * onMessage thread (which does not guard the dispatch), and the host sink writes to the DB
     * (e.g. {@code recordOutcome} throws {@code NotFoundException} for a workspace deleted
     * mid-chain) — an escape would close the control socket, so the daemon's terminal {@code
     * Bootstrapped} never arrives and the host await hangs to timeout. A dropped per-step callback
     * is harmless; {@link #completeBootstrap} resolves the future directly, not through the sink.
     */
    private void dispatch(Consumer<WorkspaceBootstrapDriver.StepSink> event) {
      try {
        event.accept(sink);
      } catch (RuntimeException e) {
        LOG.debugf("workspace-daemon bootstrap sink callback failed (dropped): %s", e.getMessage());
      }
    }
  }
}
