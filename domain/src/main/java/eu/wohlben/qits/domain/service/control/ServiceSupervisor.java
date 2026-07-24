package eu.wohlben.qits.domain.service.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.daemon.control.RepositoryDaemonService;
import eu.wohlben.qits.domain.daemon.dto.RepositoryDaemonDto;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.SegmentLineSink;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import eu.wohlben.qits.domain.repository.control.ProxyOrigin;
import eu.wohlben.qits.domain.repository.control.WorkspaceDaemonLiveness;
import eu.wohlben.qits.domain.repository.control.WorkspaceServiceDriver;
import eu.wohlben.qits.domain.service.dto.ServiceEventDto;
import eu.wohlben.qits.domain.service.dto.ServiceInstanceDto;
import eu.wohlben.qits.domain.service.entity.ServiceEventKind;
import eu.wohlben.qits.domain.service.entity.ServiceEventSeverity;
import eu.wohlben.qits.domain.service.entity.ServiceStatus;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Supervises daemon instances: one per (workspace, daemon definition), enforced singleton. The
 * daemon itself runs as a detached session inside the container ({@link
 * ContainerRuntime#startDaemon}) so it outlives a qits restart; qits streams its output by
 * following the session's mirror log with an ordinary registry command (the "follower", kind
 * DAEMON), which keeps the ready-pattern, per-line persistence, and terminal re-attach working. The
 * supervisor adds the state machine ({@code STARTING → READY → CRASHED/RESTARTING → STOPPED}), the
 * restart policy with exponential backoff, readiness detection, graceful stop, and a liveness poll
 * (the detached session has no host-side exit callback).
 *
 * <p>In-memory supervisor state is lost on a JVM restart, but the sessions are not: {@link
 * #adoptIfRunning} re-adopts a still-running session on first sighting (resuming the log follow +
 * liveness poll) instead of showing it STOPPED. Every transition is published as a {@link
 * ServiceEventDto} through {@link ServiceEventService}, which fans out to the UI feed and the
 * workspace's agent session.
 */
@ApplicationScoped
public class ServiceSupervisor {

  private static final Logger LOG = Logger.getLogger(ServiceSupervisor.class);

  private static final long MAX_RESTART_BACKOFF_MILLIS = 30_000;

  /**
   * Env var stamped on every daemon process (and inherited by its forks) so {@link #reapStragglers}
   * can identify and kill leftovers from a previous run by marker, even ones that escaped the
   * launched process group.
   */
  private static final String SERVICE_MARKER_ENV = "QITS_SERVICE_ID";

  /** One supervised daemon in one workspace. Mutated only under the supervisor monitor. */
  private static final class Instance {
    final String repoId;
    final String workspaceId;
    RepositoryDaemonDto daemon;
    ServiceStatus status = ServiceStatus.STOPPED;
    int restartCount;
    String commandId;
    boolean stopRequested;
    TailSink tail;
    ScheduledFuture<?> pending;

    /**
     * True when this service is supervised by the in-container workspace-daemon (Part 4) and this
     * instance is only a host-side <b>projection</b> of the events it streams — the daemon owns
     * spawn/restart/backoff/group-kill, so the host runs no tmux session, follower, liveness poll,
     * or reap for it. False for the tmux fallback (no live daemon), which drives the process
     * itself.
     */
    boolean daemonBacked;

    /** Polls the detached daemon session's liveness (it has no host-side exit callback). */
    ScheduledFuture<?> liveness;

    /**
     * Live healthcheck state for the current launch epoch — runtime-only, never persisted. Each
     * (re)launch builds a fresh set, so a probe tick outliving a settled run can only write into a
     * discarded object. Null when the daemon isn't running (reads all-UNKNOWN).
     */
    HealthProbeService.ProbeSet health;

    /**
     * Where the daemon web-view proxy connects to reach the daemon's {@code webView.port} inside
     * the container — its DNS name + port on the shared network. Null when the daemon isn't
     * web-viewable; re-resolved on every (re)launch.
     */
    ProxyOrigin origin;

    /**
     * The technical process tracking the container start that auto-started this instance, or null
     * for manual/adopted starts. Its {@code daemon:<name>} segment receives the startup log and
     * settles on the first terminal-ish transition (READY/CRASHED/STOPPED).
     */
    TechnicalProcess process;

    Instance(String repoId, String workspaceId, RepositoryDaemonDto daemon) {
      this.repoId = repoId;
      this.workspaceId = workspaceId;
      this.daemon = daemon;
    }
  }

  private record Key(String repoId, String workspaceId, String daemonId) {}

  private final Map<Key, Instance> instances = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          4,
          runnable -> {
            Thread thread = new Thread(runnable, "daemon-supervisor");
            thread.setDaemon(true);
            return thread;
          });

  @Inject CommandService commandService;

  @Inject CommandRegistry registry;

  @Inject RepositoryDaemonService repositoryDaemonService;

  @Inject ServiceEventService events;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject HealthProbeService healthProbes;

  /**
   * The in-container supervision driver (Part 4). Present only in the backend app (its impl is
   * {@code WorkspaceDaemonRegistry}); absent in cli/tests, where this supervisor keeps the tmux
   * path. When present and a workspace's daemon is live, that workspace's services are
   * <b>daemon-backed</b> — this supervisor projects their streamed events instead of driving tmux.
   */
  @Inject jakarta.enterprise.inject.Instance<WorkspaceServiceDriver> serviceDriver;

  /** Liveness of a workspace's control socket — the daemon-backed vs tmux-fallback gate. */
  @Inject jakarta.enterprise.inject.Instance<WorkspaceDaemonLiveness> liveness;

  /**
   * Subscribe the projection sink once at startup, so a daemon's service events reach this
   * supervisor's state machine, segments, and proxy. No-op when the driver is absent (cli/tests).
   */
  @PostConstruct
  void subscribeProjection() {
    if (!serviceDriver.isUnsatisfied()) {
      serviceDriver.get().subscribe(new ProjectionSink());
    }
  }

  /**
   * Whether {@code workspaceId}'s services are supervised by a live in-container daemon (Part 4).
   * If so, this supervisor projects the daemon's events; otherwise it drives the tmux fallback (the
   * degradation contract — a stale, pre-daemon image still works exactly as before).
   */
  private boolean daemonBacked(String workspaceId) {
    return !serviceDriver.isUnsatisfied()
        && !liveness.isUnsatisfied()
        && liveness.get().isDaemonLive(workspaceId);
  }

  /** Without a ready pattern, STARTING flips to READY after this long (if still alive). */
  @ConfigProperty(name = "qits.services.ready-grace-ms", defaultValue = "10000")
  long readyGraceMillis;

  /** How long a graceful stop waits after the stop signal before force-killing. */
  @ConfigProperty(name = "qits.services.stop-grace-ms", defaultValue = "5000")
  long stopGraceMillis;

  /** First restart delay; doubles per consecutive restart, capped at 30s. */
  @ConfigProperty(name = "qits.services.restart-backoff-initial-ms", defaultValue = "1000")
  long restartBackoffInitialMillis;

  /**
   * How often a running daemon's detached session is polled for liveness (crash/exit detection).
   */
  @ConfigProperty(name = "qits.services.liveness-poll-ms", defaultValue = "2000")
  long livenessPollMillis;

  /** (repo, workspace, daemon) keys already probed for adoption of a pre-restart session. */
  private final java.util.Set<Key> adoptionProbed =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Start {@code daemonId} in the workspace. One running instance per (workspace, daemon) is
   * enforced — "restart" beats two dev servers fighting over a port.
   */
  public synchronized ServiceInstanceDto start(String repoId, String workspaceId, String daemonId) {
    return start(repoId, workspaceId, daemonId, null);
  }

  /**
   * {@link #start(String, String, String)} with an optional {@link TechnicalProcess}: the auto
   * start path passes the container start's process so this daemon's startup log streams into its
   * {@code daemon:<name>} segment and the segment settles on READY/CRASHED/STOPPED.
   */
  public synchronized ServiceInstanceDto start(
      String repoId, String workspaceId, String daemonId, TechnicalProcess process) {
    RepositoryDaemonDto daemon = repositoryDaemonService.resolve(repoId, daemonId);
    Key key = new Key(repoId, workspaceId, daemonId);
    Instance existing = instances.get(key);
    if (existing != null && isLive(existing.status)) {
      throw new BadRequestException(
          "Daemon '" + daemon.name() + "' is already running in this workspace");
    }
    Instance instance = new Instance(repoId, workspaceId, daemon);
    instance.process = process;
    instances.put(key, instance);
    if (daemonBacked(workspaceId)) {
      // Daemon-backed: register a projection only. An auto-start (process != null, from the
      // lifecycle coupler) needs no instruction — the daemon self-starts the service from its
      // in-container config; we pre-register so its streamed events settle this segment/status. A
      // manual start (no process) asks the daemon to start it now.
      instance.daemonBacked = true;
      instance.tail = new TailSink();
      instance.status = ServiceStatus.STARTING;
      if (process == null) {
        serviceDriver
            .get()
            .startService(workspaceId, daemon.name(), daemon.startScript(), daemon.environment());
      }
    } else {
      launch(instance); // tmux fallback (no live daemon) — drive the process ourselves
    }
    return toInstanceDto(instance, null, workspaceId);
  }

  /** Gracefully stop a running instance: stop signal, grace period, then force-kill fallback. */
  public synchronized ServiceInstanceDto stop(String repoId, String workspaceId, String daemonId) {
    Instance instance = instances.get(new Key(repoId, workspaceId, daemonId));
    if (instance == null || !isLive(instance.status)) {
      throw new NotFoundException("Daemon is not running in this workspace");
    }
    instance.stopRequested = true;
    cancelPending(instance);
    cancelLiveness(instance);
    cancelHealth(instance);
    if (instance.daemonBacked) {
      // The daemon owns the process: ask it to stop (it reports back STOPPED, which the projection
      // sink settles). No host tmux signal/force-kill/follower involved.
      serviceDriver
          .get()
          .signalService(workspaceId, instance.daemon.name(), instance.daemon.stopSignal());
      return toInstanceDto(instance, null, workspaceId);
    }
    if (instance.status == ServiceStatus.RESTARTING) {
      // No live session — the pending relaunch was the only thing to cancel.
      transition(instance, ServiceStatus.STOPPED, ServiceEventSeverity.INFO, "stopped", null);
      return toInstanceDto(instance, null, workspaceId);
    }
    String commandId = instance.commandId;
    String container = containers.containerName(workspaceId, repoId);
    // Graceful stop signal to the detached session's process group, then settle (force-kill if it
    // ignored the signal) after the grace period — off the supervisor monitor, since terminate()
    // joins the follower's reader thread which may deliver a line into synchronized markReady.
    containers.signalDaemon(container, daemonId, instance.daemon.stopSignal());
    scheduler.schedule(
        () -> finishStop(instance, commandId, container), stopGraceMillis, TimeUnit.MILLISECONDS);
    return toInstanceDto(instance, null, workspaceId);
  }

  /**
   * Grace period elapsed after a stop signal: force-kill a session that ignored it, stop the
   * follower, and settle STOPPED.
   */
  private void finishStop(Instance instance, String commandId, String container) {
    if (containers.daemonAlive(container, instance.daemon.id())) {
      LOG.infof(
          "Daemon '%s' ignored SIG%s for %d ms; force-killing",
          instance.daemon.name(), instance.daemon.stopSignal(), stopGraceMillis);
      containers.killDaemon(container, instance.daemon.id());
    }
    registry.terminate(commandId); // stop the follower tail
    synchronized (this) {
      if (commandId.equals(instance.commandId) && instance.status != ServiceStatus.STOPPED) {
        transition(instance, ServiceStatus.STOPPED, ServiceEventSeverity.INFO, "stopped", null);
      }
    }
  }

  /**
   * Settle every live daemon of a workspace when its container is about to be deliberately removed
   * ({@code stopContainer} / discard). This is the missing half of daemon↔workspace lifecycle
   * coupling: without it, the liveness poll reads the imminent container disappearance as a crash
   * and the restart policy resurrects the just-stopped container. Setting {@code stopRequested} on
   * each instance first makes both {@link #handleExit} and {@link #relaunch} take the STOPPED/INFO
   * path, so nothing crashes, restarts, or resurrects.
   *
   * <p>Runs <em>synchronously</em> on the caller's (event-firing) thread so it completes before
   * {@code containers.rm}. {@code graceful} = true (stopContainer) sends each daemon's stop signal
   * and waits up to {@code stop-grace-ms} for a clean flush; false (discard) settles bookkeeping
   * only and lets {@code rm} kill the processes. Mirrors {@link #stop}/{@link #finishStop}, but
   * batched over the workspace's live instances and blocking (there is no live container left to
   * schedule the settle against afterwards).
   */
  public void settleForWorkspace(String repoId, String workspaceId, boolean graceful) {
    String container = containers.containerName(workspaceId, repoId);
    List<Instance> targets = new ArrayList<>();
    synchronized (this) {
      for (Map.Entry<Key, Instance> entry : instances.entrySet()) {
        Key key = entry.getKey();
        if (!key.repoId().equals(repoId) || !key.workspaceId().equals(workspaceId)) {
          continue;
        }
        Instance instance = entry.getValue();
        if (!isLive(instance.status)) {
          continue;
        }
        instance.stopRequested = true;
        cancelPending(instance);
        cancelLiveness(instance);
        cancelHealth(instance);
        if (instance.daemonBacked) {
          // Daemon-backed: the container (and its PID-1 daemon + services) is about to be removed.
          // Ask the daemon to stop the service on a graceful stop, then settle STOPPED here —
          // deterministically, since the container's imminent rm may beat the daemon's STOPPED
          // event. No host tmux/follower to tear down.
          if (graceful && !serviceDriver.isUnsatisfied()) {
            serviceDriver
                .get()
                .signalService(workspaceId, instance.daemon.name(), instance.daemon.stopSignal());
          }
          transition(
              instance,
              ServiceStatus.STOPPED,
              ServiceEventSeverity.INFO,
              "workspace stopped",
              null);
        } else if (instance.status == ServiceStatus.RESTARTING) {
          // No live session — the pending relaunch (just cancelled) was all there was to stop.
          transition(
              instance,
              ServiceStatus.STOPPED,
              ServiceEventSeverity.INFO,
              "workspace stopped",
              null);
        } else {
          targets.add(instance);
        }
      }
      // A future container of this workspace should be re-probed for adoption from scratch.
      adoptionProbed.removeIf(
          key -> key.repoId().equals(repoId) && key.workspaceId().equals(workspaceId));
    }

    // Signal + grace + follower termination run off the monitor — terminate() joins the follower's
    // reader thread, which may deliver a line into synchronized markReady (same reason finishStop
    // runs off-monitor).
    if (graceful) {
      for (Instance instance : targets) {
        containers.signalDaemon(container, instance.daemon.id(), instance.daemon.stopSignal());
      }
      awaitAllDeadOrTimeout(container, targets, stopGraceMillis);
    }
    for (Instance instance : targets) {
      if (graceful && containers.daemonAlive(container, instance.daemon.id())) {
        LOG.infof(
            "Daemon '%s' ignored SIG%s for %d ms during workspace stop; force-killing",
            instance.daemon.name(), instance.daemon.stopSignal(), stopGraceMillis);
        containers.killDaemon(container, instance.daemon.id());
      }
      if (instance.commandId != null) {
        registry.terminate(instance.commandId); // stop the follower tail
      }
    }

    synchronized (this) {
      for (Instance instance : targets) {
        if (instance.status != ServiceStatus.STOPPED) {
          transition(
              instance,
              ServiceStatus.STOPPED,
              ServiceEventSeverity.INFO,
              "workspace stopped",
              null);
        }
      }
    }
  }

  /** Poll until every target daemon's session is gone or the grace window elapses. */
  private void awaitAllDeadOrTimeout(String container, List<Instance> targets, long graceMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis);
    while (System.nanoTime() < deadline) {
      boolean anyAlive = false;
      for (Instance instance : targets) {
        if (containers.daemonAlive(container, instance.daemon.id())) {
          anyAlive = true;
          break;
        }
      }
      if (!anyAlive) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** Every daemon of the repository with its runtime state in this workspace. */
  public List<ServiceInstanceDto> effectiveDaemons(String repoId, String workspaceId) {
    List<RepositoryDaemonDto> definitions = repositoryDaemonService.resolveAll(repoId);
    // Lazily reconcile a daemon still running from before a qits restart: its supervisor state was
    // lost, but its detached session lives on. Probe each untracked daemon once — if its session is
    // alive, re-adopt it (resume the log follow + liveness poll) so it reads RUNNING again with
    // logs, instead of the blanket "STOPPED, command INTERRUPTED" the old in-memory model produced.
    for (RepositoryDaemonDto definition : definitions) {
      adoptIfRunning(repoId, workspaceId, definition);
    }
    synchronized (this) {
      List<ServiceInstanceDto> result = new ArrayList<>(definitions.size());
      for (RepositoryDaemonDto definition : definitions) {
        Instance instance = instances.get(new Key(repoId, workspaceId, definition.id()));
        result.add(toInstanceDto(instance, definition, workspaceId));
      }
      return result;
    }
  }

  /**
   * If a daemon's detached session is alive but no live instance is tracked (the classic
   * post-restart case, but also a session qits otherwise lost track of), re-adopt it. Probed at
   * most once per key so a UI poll doesn't hammer the container runtime; a session that isn't
   * running when first probed is simply left settled.
   */
  private void adoptIfRunning(String repoId, String workspaceId, RepositoryDaemonDto definition) {
    if (daemonBacked(workspaceId)) {
      return; // daemon-backed adoption is event-driven (the daemon re-reports on socket reconnect)
    }
    Key key = new Key(repoId, workspaceId, definition.id());
    synchronized (this) {
      Instance existing = instances.get(key);
      if (existing != null && isLive(existing.status)) {
        return; // already tracked and running
      }
      if (!adoptionProbed.add(key)) {
        return; // already probed once — don't re-probe on every poll
      }
    }
    String container = containers.containerName(workspaceId, repoId);
    boolean alive;
    try {
      alive = containers.exists(container) && containers.daemonAlive(container, definition.id());
    } catch (RuntimeException e) {
      LOG.debugf(e, "Adoption probe failed for daemon %s", definition.id());
      return;
    }
    if (!alive) {
      return;
    }
    synchronized (this) {
      Instance existing = instances.get(key);
      if (existing != null && isLive(existing.status)) {
        return;
      }
      Instance instance = new Instance(repoId, workspaceId, definition);
      instances.put(key, instance);
      try {
        launch(instance, true);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Failed to adopt running daemon '%s'", definition.name());
        instances.remove(key);
      }
    }
  }

  // --- Lifecycle internals (all under the supervisor monitor) ---------------------------------

  private void launch(Instance instance) {
    launch(instance, false);
  }

  /**
   * (Re)launch or adopt a daemon. The daemon itself runs as a detached session inside the container
   * ({@link ContainerRuntime#startDaemon} — a tmux session for docker) so it outlives a qits
   * restart; qits streams its output by following the session's mirror log with an ordinary
   * registry command (the "follower"), which keeps the ready-pattern, per-line persistence, and
   * terminal re-attach working unchanged. Liveness is polled from the session, not a host exit
   * callback.
   *
   * @param adopt true when reconciling an already-running session found on boot: skip the reap and
   *     the session start, follow the mirror log from its end (history is already persisted, not
   *     re-emitted), and consider the live daemon READY.
   */
  private void launch(Instance instance, boolean adopt) {
    RepositoryDaemonDto daemon = instance.daemon;
    String container = containers.containerName(instance.workspaceId, instance.repoId);

    List<CommandOutputSink> sinks = new ArrayList<>();
    instance.tail = new TailSink();
    sinks.add(instance.tail);
    // A process-tracked auto-start streams the startup log into the process's daemon segment; the
    // sink self-closes (and is pruned) once the segment settles, so a chatty daemon stops feeding
    // an already-decided expander. Relaunches re-add it — lines keep landing until settlement.
    if (instance.process != null) {
      sinks.add(
          new SegmentLineSink(instance.process, TechnicalProcess.daemonSegment(daemon.name())));
    }
    // A fresh start reads the log from the top, so the ready line is seen and flips STARTING→READY.
    // An adopted session is already READY and tails from the end, so no old line re-triggers it.
    if (!adopt && daemon.readyPattern() != null) {
      sinks.add(
          new ReadyPatternSink(Pattern.compile(daemon.readyPattern()), () -> markReady(instance)));
    }
    String publicBase =
        daemon.webView() != null
            ? ServiceProxyPath.servedBase(
                instance.workspaceId, daemon.id(), daemon.webView().basePath())
            : null;

    // Tag every daemon process with the reap marker (inherited by its forks via
    // /proc/<pid>/environ)
    // and, on a fresh start, reap any straggler from a previous run first — a child (e.g. Quarkus
    // dev's forked JVM) that escaped the session and still binds the web-view port would wedge
    // this start.
    // See
    // docs/issues/resolved/2026-07-05_daemon-stop-orphans-forked-quarkus-jvm.md.
    Map<String, String> environment = new HashMap<>(daemon.environment());
    environment.put(SERVICE_MARKER_ENV, daemon.id());
    if (!adopt) {
      reapStragglers(instance);
    }

    CommandService.DaemonRun run =
        commandService.beginDaemonRun(
            instance.repoId,
            instance.workspaceId,
            daemon.name(),
            daemon.startScript(),
            environment,
            daemon.otel(),
            publicBase);
    instance.commandId = run.command().id();

    if (!adopt) {
      containers.startDaemon(container, daemon.id(), daemon.startScript(), run.environment());
    }

    String followScript =
        "tail -n " + (adopt ? "0" : "+1") + " -F " + containers.daemonLogPath(daemon.id());
    commandService.followDaemon(
        instance.commandId,
        container,
        followScript,
        (commandId, exitCode, terminatedManually) -> {}, // follower exit doesn't drive lifecycle
        sinks.toArray(CommandOutputSink[]::new));

    resolveOrigin(instance);

    if (adopt) {
      transition(
          instance,
          ServiceStatus.READY,
          ServiceEventSeverity.INFO,
          "adopted (already running after a qits restart)",
          null);
    } else {
      transition(
          instance,
          ServiceStatus.STARTING,
          ServiceEventSeverity.INFO,
          instance.restartCount == 0
              ? "starting"
              : "starting (restart " + instance.restartCount + "/" + daemon.maxRestarts() + ")",
          null);
      if (daemon.readyPattern() == null) {
        String launchedCommandId = instance.commandId;
        instance.pending =
            scheduler.schedule(
                () -> graceReady(instance, launchedCommandId),
                readyGraceMillis,
                TimeUnit.MILLISECONDS);
      }
    }
    startLivenessPoll(instance, instance.commandId);
    startHealthProbes(instance, container, publicBase, adopt);
  }

  /**
   * Schedule the definition's healthchecks for this launch epoch. Probes run in the container's own
   * network namespace, with the daemon's env plus {@code QITS_PUBLIC_BASE} (a COMMAND check probing
   * an app that serves under the proxy base needs it). A state flip fires the same payload-free
   * DAEMONS topic hint as a lifecycle transition — flips only, never per tick, so live clients
   * refetch on change instead of polling. Health stays a display sidecar: nothing here touches
   * {@link ServiceStatus} or publishes a daemon event.
   */
  private void startHealthProbes(
      Instance instance, String container, String publicBase, boolean adopt) {
    cancelHealth(instance);
    Map<String, String> probeEnv = new HashMap<>(instance.daemon.environment());
    if (publicBase != null) {
      probeEnv.put("QITS_PUBLIC_BASE", publicBase);
    }
    instance.health =
        healthProbes.start(
            scheduler,
            container,
            instance.daemon.healthChecks(),
            probeEnv,
            adopt,
            () ->
                changePublisher.fire(
                    instance.repoId, instance.workspaceId, WorkspaceChangeHint.Topic.DAEMONS));
  }

  private static void cancelHealth(Instance instance) {
    if (instance.health != null) {
      instance.health.cancel();
      instance.health = null;
    }
  }

  /** Begin polling the detached session's liveness for the current run of {@code instance}. */
  private void startLivenessPoll(Instance instance, String commandId) {
    cancelLiveness(instance);
    instance.liveness =
        scheduler.scheduleWithFixedDelay(
            () -> checkLiveness(instance, commandId),
            livenessPollMillis,
            livenessPollMillis,
            TimeUnit.MILLISECONDS);
  }

  /**
   * One liveness tick: if the detached daemon session has ended, stop the follower and drive the
   * restart policy through {@link #handleExit}. The container probes and {@code registry.terminate}
   * run <em>outside</em> the supervisor monitor — terminate joins the follower's reader thread,
   * which may itself deliver a ready-pattern line into the (synchronized) {@link #markReady}.
   */
  private void checkLiveness(Instance instance, String commandId) {
    synchronized (this) {
      if (!commandId.equals(instance.commandId) || !isLive(instance.status)) {
        cancelLiveness(instance);
        return;
      }
    }
    String container = containers.containerName(instance.workspaceId, instance.repoId);
    if (containers.daemonAlive(container, instance.daemon.id())) {
      return;
    }
    Integer code = containers.daemonExitCode(container, instance.daemon.id());
    int exitCode =
        code != null ? code : 1; // no recorded code => killed/crashed => treat as failure
    synchronized (this) {
      if (!commandId.equals(instance.commandId) || !isLive(instance.status)) {
        cancelLiveness(instance);
        return;
      }
      cancelLiveness(instance);
    }
    registry.terminate(commandId); // stop the follower tail (it would follow a dead log forever)
    synchronized (this) {
      handleExit(instance, commandId, exitCode, false);
    }
  }

  private static void cancelLiveness(Instance instance) {
    if (instance.liveness != null) {
      instance.liveness.cancel(false);
      instance.liveness = null;
    }
  }

  /**
   * Kill any process in the workspace's container left over from a previous run of this daemon —
   * chiefly Quarkus dev mode's forked application JVM, which escapes the launched process group (so
   * a stop's {@code kill -- -pgid} misses it), keeps binding the http port, and would wedge this
   * start ("Port 8080 seems to be in use"). Every daemon process is tagged {@link
   * #SERVICE_MARKER_ENV}={@code <id>} (inherited by its forks), so the escaped child is found via
   * {@code /proc/<pid>/environ} regardless of process group. The per-daemon UUID keeps the scan off
   * any other process (crucial: with the test fake this runs on the host, so a broader match could
   * kill unrelated host processes), and the scanning shell carries no marker so it can't kill
   * itself.
   *
   * <p>A holder the marker can't reach — one predating this mechanism, or a session qits otherwise
   * lost track of — is handled instead by {@link #adoptIfRunning} re-adopting it, not by killing
   * whatever binds the port. Best-effort: a failure here just leaves the old start-collision
   * behavior, so it never blocks a launch. See
   * docs/issues/resolved/2026-07-05_daemon-stop-orphans-forked-quarkus-jvm.md.
   */
  private void reapStragglers(Instance instance) {
    String container = containers.containerName(instance.workspaceId, instance.repoId);
    // The daemon id is a server-generated UUID (hex + hyphens) — safe to interpolate. -z: environ
    // is
    // NUL-separated, so each KEY=VALUE is one record; -F: fixed string, exact match.
    String marker = SERVICE_MARKER_ENV + "=" + instance.daemon.id();
    String script =
        "for p in /proc/[0-9]*; do grep -qzF '"
            + marker
            + "' \"$p/environ\" 2>/dev/null && kill -9 \"${p#/proc/}\" 2>/dev/null; done; true";
    try {
      containers.exec(container, null, Map.of(), "bash", "-c", script);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Straggler reap failed for daemon %s", instance.daemon.id());
    }
  }

  private synchronized void markReady(Instance instance) {
    if (instance.status == ServiceStatus.STARTING) {
      cancelPending(instance);
      transition(
          instance,
          ServiceStatus.READY,
          ServiceEventSeverity.INFO,
          "ready (pattern matched)",
          null);
    }
  }

  private synchronized void graceReady(Instance instance, String commandId) {
    if (instance.status == ServiceStatus.STARTING && commandId.equals(instance.commandId)) {
      transition(
          instance,
          ServiceStatus.READY,
          ServiceEventSeverity.INFO,
          "ready (grace period elapsed)",
          null);
    }
  }

  private synchronized void handleExit(
      Instance instance, String commandId, int exitCode, boolean terminatedManually) {
    if (!commandId.equals(instance.commandId)) {
      return; // A stale exit from a previous run of this instance.
    }
    cancelPending(instance);
    String tail = instance.tail != null ? instance.tail.excerpt() : null;

    if (instance.stopRequested || terminatedManually) {
      transition(
          instance,
          ServiceStatus.STOPPED,
          ServiceEventSeverity.INFO,
          "stopped (exit " + exitCode + ")",
          null);
      return;
    }

    boolean wantRestart =
        switch (instance.daemon.restartPolicy()) {
          case ALWAYS -> true;
          case ON_FAILURE -> exitCode != 0;
          case NEVER -> false;
        };
    if (!wantRestart) {
      if (exitCode == 0) {
        transition(
            instance, ServiceStatus.STOPPED, ServiceEventSeverity.INFO, "exited cleanly", null);
      } else {
        transition(
            instance,
            ServiceStatus.CRASHED,
            ServiceEventSeverity.ERROR,
            "crashed (exit " + exitCode + ")",
            tail);
      }
      return;
    }
    if (instance.restartCount >= instance.daemon.maxRestarts()) {
      transition(
          instance,
          ServiceStatus.CRASHED,
          ServiceEventSeverity.ERROR,
          "crashed (exit "
              + exitCode
              + "), giving up after "
              + instance.restartCount
              + " restart(s)",
          tail);
      return;
    }
    long backoff =
        Math.min(
            restartBackoffInitialMillis * (1L << Math.min(instance.restartCount, 20)),
            MAX_RESTART_BACKOFF_MILLIS);
    transition(
        instance,
        ServiceStatus.RESTARTING,
        ServiceEventSeverity.WARNING,
        "crashed (exit "
            + exitCode
            + "), restarting in "
            + backoff
            + " ms (attempt "
            + (instance.restartCount + 1)
            + "/"
            + instance.daemon.maxRestarts()
            + ")",
        tail);
    instance.pending = scheduler.schedule(() -> relaunch(instance), backoff, TimeUnit.MILLISECONDS);
  }

  private synchronized void relaunch(Instance instance) {
    if (instance.stopRequested || instance.status != ServiceStatus.RESTARTING) {
      return;
    }
    instance.restartCount++;
    refreshDefinition(instance);
    try {
      launch(instance);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Relaunch of daemon '%s' failed", instance.daemon.name());
      transition(
          instance,
          ServiceStatus.CRASHED,
          ServiceEventSeverity.ERROR,
          "relaunch failed: " + e.getMessage(),
          null);
    }
  }

  /**
   * Re-read the daemon definition from the repository before an automatic relaunch, so a mid-run
   * edit (webView added, startScript changed, env updated) takes effect on the fresh process
   * instead of the supervisor resurrecting the launch-time snapshot. Falls back to the pinned copy
   * if the definition was deleted mid-flight — {@link #launch} still needs something to start, and
   * the next liveness/settle cycle will clean it up. Without this, the proxy's {@link #proxyTarget}
   * (which reads {@code instance.daemon.webView()}) and the REST list (which prefers the database
   * definition) answer from two different snapshots after an {@code ON_FAILURE} restart — see
   * docs/issues resolved/2026-07-06_daemon-relaunch-uses-stale-definition-after-webview-update.
   */
  private void refreshDefinition(Instance instance) {
    try {
      instance.daemon = repositoryDaemonService.resolve(instance.repoId, instance.daemon.id());
    } catch (NotFoundException e) {
      LOG.debugf(
          "Daemon '%s' definition gone at relaunch; keeping the pinned copy", instance.daemon.id());
    }
  }

  private void transition(
      Instance instance,
      ServiceStatus status,
      ServiceEventSeverity severity,
      String summary,
      String logExcerpt) {
    instance.status = status;
    settleProcessSegment(instance, status, summary);
    changePublisher.fire(instance.repoId, instance.workspaceId, WorkspaceChangeHint.Topic.DAEMONS);
    // No process to probe outside STARTING/READY (RESTARTING included — during the backoff there is
    // nothing listening, and the relaunch starts a fresh probe epoch anyway).
    if (status != ServiceStatus.STARTING && status != ServiceStatus.READY) {
      cancelHealth(instance);
    }
    events.publish(
        new ServiceEventDto(
            instance.repoId,
            instance.workspaceId,
            instance.daemon.id(),
            instance.daemon.name(),
            ServiceEventKind.STATUS_CHANGED,
            severity,
            status,
            summary,
            logExcerpt,
            instance.commandId,
            null,
            null,
            null,
            null,
            Instant.now()));
  }

  /**
   * Settle a process-tracked instance's {@code daemon:<name>} segment on the first decisive status:
   * READY (or a deliberate STOPPED during the window) settles {@code ok}, CRASHED settles {@code
   * failed} — with the transition summary appended as the segment's closing line. RESTARTING is
   * deliberately not terminal: the segment stays open across the backoff and settles with the
   * retry's outcome. Idempotent via the process's first-verdict-wins settle.
   */
  private static void settleProcessSegment(
      Instance instance, ServiceStatus status, String summary) {
    if (instance.process == null) {
      return;
    }
    String segment = TechnicalProcess.daemonSegment(instance.daemon.name());
    switch (status) {
      case READY, STOPPED -> {
        instance.process.appendLine(segment, summary);
        instance.process.settleSegment(segment, true);
      }
      case CRASHED -> {
        instance.process.appendLine(segment, summary);
        instance.process.settleSegment(segment, false);
      }
      default -> {}
    }
  }

  /**
   * Resolve where the web-view proxy reaches a web-viewable daemon's port — the container's DNS
   * name on the shared network + the real container port (the container is guaranteed provisioned
   * here, {@code prepare}'s ensureContainer ran inside beginDaemonRun). There is no create-time
   * port constraint, so this always resolves for a web-viewable daemon; null only when the daemon
   * isn't web-viewable.
   */
  private void resolveOrigin(Instance instance) {
    Integer httpPort = instance.daemon.webView() != null ? instance.daemon.webView().port() : null;
    if (httpPort == null) {
      instance.origin = null;
      return;
    }
    String container = containers.containerName(instance.workspaceId, instance.repoId);
    instance.origin = containers.resolveTarget(container, httpPort);
  }

  /**
   * The live proxy target for a (workspaceId, daemonId) pair — the daemon web-view proxy's only
   * lookup. The pair is unambiguous even though workspace slugs repeat across repositories, because
   * a daemon id is a UUID owned by exactly one repository. The port comes exclusively from
   * supervisor state (never from any request component) and targets localhost — the SSRF
   * constraint. A present target with a null {@code origin} means the daemon isn't reachable (e.g.
   * the container is gone) — the proxy 502s.
   */
  public synchronized Optional<ProxyTarget> proxyTarget(String workspaceId, String daemonId) {
    for (Map.Entry<Key, Instance> entry : instances.entrySet()) {
      Key key = entry.getKey();
      if (key.workspaceId().equals(workspaceId) && key.daemonId().equals(daemonId)) {
        Instance instance = entry.getValue();
        if (instance.daemon.webView() == null) {
          return Optional.empty();
        }
        return Optional.of(new ProxyTarget(instance.status, instance.origin));
      }
    }
    return Optional.empty();
  }

  /** A web-viewable daemon instance as the proxy sees it: status + the container-port origin. */
  public record ProxyTarget(ServiceStatus status, ProxyOrigin origin) {}

  private static void cancelPending(Instance instance) {
    if (instance.pending != null) {
      instance.pending.cancel(false);
      instance.pending = null;
    }
  }

  private static boolean isLive(ServiceStatus status) {
    return status == ServiceStatus.STARTING
        || status == ServiceStatus.READY
        || status == ServiceStatus.RESTARTING;
  }

  private ServiceInstanceDto toInstanceDto(
      Instance instance, RepositoryDaemonDto definition, String workspaceId) {
    RepositoryDaemonDto daemon = definition != null ? definition : instance.daemon;
    String proxyPath =
        daemon.webView() != null
            ? ServiceProxyPath.servedBase(workspaceId, daemon.id(), daemon.webView().basePath())
            : null;
    if (instance == null) {
      return new ServiceInstanceDto(
          daemon,
          ServiceStatus.STOPPED,
          0,
          null,
          proxyPath,
          HealthProbeService.snapshotOrUnknown(null, daemon.healthChecks()));
    }
    return new ServiceInstanceDto(
        daemon,
        instance.status,
        instance.restartCount,
        instance.commandId,
        proxyPath,
        HealthProbeService.snapshotOrUnknown(instance.health, daemon.healthChecks()));
  }

  // --- Daemon-backed projection (Part 4) ------------------------------------------------------

  /**
   * Projects the in-container daemon's service events onto this supervisor's existing state machine
   * (SSE, {@code daemon:<name>} segment, web-view proxy origin), reusing {@link #transition}. The
   * daemon owns the process lifecycle, so nothing here spawns/restarts/polls — this only reflects
   * what it reports. Callbacks arrive on the control-socket thread; each synchronizes on the
   * supervisor monitor like every other transition path.
   *
   * <p>Streamed lines feed the crash-excerpt {@link TailSink}.
   */
  private final class ProjectionSink implements WorkspaceServiceDriver.ServiceEventSink {

    @Override
    public void onState(
        String repoId, String workspaceId, String serviceName, String state, Integer exitCode) {
      if (repoId == null) {
        return; // no repo context (daemon Hello not yet seen) — can't resolve the definition
      }
      ServiceStatus mapped = mapStatus(state);
      if (mapped == null) {
        LOG.debugf("Ignoring unknown service state '%s' for '%s'", state, serviceName);
        return;
      }
      synchronized (ServiceSupervisor.this) {
        Instance instance = findByName(repoId, workspaceId, serviceName);
        if (instance == null) {
          // The daemon reports a service the host didn't pre-register (a reconnect re-report after
          // a
          // qits restart, or a config-declared service with no coupler run) — event-driven
          // adoption.
          RepositoryDaemonDto definition = resolveDefinition(repoId, serviceName);
          if (definition == null) {
            LOG.debugf(
                "Ignoring event for service '%s' with no repository definition in %s",
                serviceName, repoId);
            return;
          }
          instance = new Instance(repoId, workspaceId, definition);
          instance.tail = new TailSink();
          instances.put(new Key(repoId, workspaceId, definition.id()), instance);
        }
        instance.daemonBacked = true;
        if (mapped == ServiceStatus.READY) {
          resolveOrigin(instance); // the service is bound now — resolve the proxy target
        }
        if (mapped == ServiceStatus.RESTARTING) {
          instance.restartCount++;
        }
        String excerpt =
            mapped == ServiceStatus.CRASHED && instance.tail != null
                ? instance.tail.excerpt()
                : null;
        transition(instance, mapped, severityFor(mapped), summaryFor(mapped, exitCode), excerpt);
      }
    }

    @Override
    public void onLine(
        String repoId, String workspaceId, String serviceName, String stream, String line) {
      synchronized (ServiceSupervisor.this) {
        Instance instance = findByName(repoId, workspaceId, serviceName);
        if (instance == null || !instance.daemonBacked || instance.tail == null) {
          return;
        }
        instance.tail.write(line + "\n"); // feeds the crash excerpt on a later CRASHED transition
      }
    }
  }

  /**
   * Find a repository-workspace's supervised instance by service name (caller holds the monitor).
   * Keyed by repoId too: a workspace slug like {@code work} repeats across repositories, so name +
   * slug alone would cross-match another repo's service.
   */
  private Instance findByName(String repoId, String workspaceId, String serviceName) {
    for (Instance instance : instances.values()) {
      if (instance.repoId.equals(repoId)
          && instance.workspaceId.equals(workspaceId)
          && instance.daemon.name().equals(serviceName)) {
        return instance;
      }
    }
    return null;
  }

  /** Test hook: whether {@code workspaceId}'s services are currently daemon-backed (Part 4). */
  boolean isDaemonBacked(String workspaceId) {
    return daemonBacked(workspaceId);
  }

  /**
   * Resolve a repository's daemon definition by service name (a DB read; the orphan case is null).
   */
  private RepositoryDaemonDto resolveDefinition(String repoId, String serviceName) {
    try {
      for (RepositoryDaemonDto definition : repositoryDaemonService.resolveAll(repoId)) {
        if (definition.name().equals(serviceName)) {
          return definition;
        }
      }
    } catch (RuntimeException e) {
      LOG.debugf(e, "service definition lookup failed for '%s' in repo %s", serviceName, repoId);
    }
    return null;
  }

  private static ServiceStatus mapStatus(String state) {
    if (state == null) {
      return null;
    }
    return switch (state) {
      case "STARTING" -> ServiceStatus.STARTING;
      case "READY" -> ServiceStatus.READY;
      case "RESTARTING" -> ServiceStatus.RESTARTING;
      case "CRASHED" -> ServiceStatus.CRASHED;
      case "STOPPED" -> ServiceStatus.STOPPED;
      default -> null;
    };
  }

  private static ServiceEventSeverity severityFor(ServiceStatus status) {
    return switch (status) {
      case CRASHED -> ServiceEventSeverity.ERROR;
      case RESTARTING -> ServiceEventSeverity.WARNING;
      default -> ServiceEventSeverity.INFO;
    };
  }

  private static String summaryFor(ServiceStatus status, Integer exitCode) {
    String suffix = exitCode != null ? " (exit " + exitCode + ")" : "";
    return switch (status) {
      case STARTING -> "starting";
      case READY -> "ready";
      case RESTARTING -> "restarting" + suffix;
      case CRASHED -> "crashed" + suffix;
      case STOPPED -> "stopped" + suffix;
      default -> status.name();
    };
  }
}
