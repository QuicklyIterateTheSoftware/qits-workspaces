package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
import eu.wohlben.qits.domain.daemon.dto.LogObserverDto;
import eu.wohlben.qits.domain.daemon.dto.LogSourceDto;
import eu.wohlben.qits.domain.daemon.dto.RepositoryDaemonDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.LogObserverKind;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import eu.wohlben.qits.domain.repository.control.ProxyOrigin;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
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
 * DAEMON), which keeps the ready-pattern, observers, per-line persistence, and terminal re-attach
 * working. The supervisor adds the state machine ({@code STARTING → READY →
 * DEGRADED/CRASHED/RESTARTING → STOPPED}), the restart policy with exponential backoff, readiness
 * detection, graceful stop, and a liveness poll (the detached session has no host-side exit
 * callback).
 *
 * <p>In-memory supervisor state is lost on a JVM restart, but the sessions are not: {@link
 * #adoptIfRunning} re-adopts a still-running session on first sighting (resuming the log follow +
 * liveness poll) instead of showing it STOPPED. Every transition and observer finding is published
 * as a {@link DaemonEventDto} through {@link DaemonEventService}, which fans out to the UI feed and
 * the workspace's agent session.
 */
@ApplicationScoped
public class DaemonSupervisor {

  private static final Logger LOG = Logger.getLogger(DaemonSupervisor.class);

  private static final long MAX_RESTART_BACKOFF_MILLIS = 30_000;

  /**
   * Env var stamped on every daemon process (and inherited by its forks) so {@link #reapStragglers}
   * can identify and kill leftovers from a previous run by marker, even ones that escaped the
   * launched process group.
   */
  private static final String DAEMON_MARKER_ENV = "QITS_DAEMON_ID";

  /** One supervised daemon in one workspace. Mutated only under the supervisor monitor. */
  private static final class Instance {
    final String repoId;
    final String workspaceId;
    RepositoryDaemonDto daemon;
    DaemonStatus status = DaemonStatus.STOPPED;
    int restartCount;
    String commandId;
    boolean stopRequested;
    TailSink tail;
    ScheduledFuture<?> pending;

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
     * File-source tails (run inside the container); started once per instance, closed on settle.
     */
    List<ContainerTailSource> tails = List.of();

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

  @Inject DaemonEventService events;

  @Inject LogClassifier classifier;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject HealthProbeService healthProbes;

  /** Without a ready pattern, STARTING flips to READY after this long (if still alive). */
  @ConfigProperty(name = "qits.daemons.ready-grace-ms", defaultValue = "10000")
  long readyGraceMillis;

  /** How long a graceful stop waits after the stop signal before force-killing. */
  @ConfigProperty(name = "qits.daemons.stop-grace-ms", defaultValue = "5000")
  long stopGraceMillis;

  /** First restart delay; doubles per consecutive restart, capped at 30s. */
  @ConfigProperty(name = "qits.daemons.restart-backoff-initial-ms", defaultValue = "1000")
  long restartBackoffInitialMillis;

  /** How often FILE log sources are polled for growth/rotation. */
  @ConfigProperty(name = "qits.daemons.file-tail-poll-ms", defaultValue = "500")
  long fileTailPollMillis;

  /**
   * How often a running daemon's detached session is polled for liveness (crash/exit detection).
   */
  @ConfigProperty(name = "qits.daemons.liveness-poll-ms", defaultValue = "2000")
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
  public synchronized DaemonInstanceDto start(String repoId, String workspaceId, String daemonId) {
    RepositoryDaemonDto daemon = repositoryDaemonService.resolve(repoId, daemonId);
    Key key = new Key(repoId, workspaceId, daemonId);
    Instance existing = instances.get(key);
    if (existing != null && isLive(existing.status)) {
      throw new BadRequestException(
          "Daemon '" + daemon.name() + "' is already running in this workspace");
    }
    Instance instance = new Instance(repoId, workspaceId, daemon);
    instances.put(key, instance);
    launch(instance);
    return toInstanceDto(instance, null, workspaceId);
  }

  /** Gracefully stop a running instance: stop signal, grace period, then force-kill fallback. */
  public synchronized DaemonInstanceDto stop(String repoId, String workspaceId, String daemonId) {
    Instance instance = instances.get(new Key(repoId, workspaceId, daemonId));
    if (instance == null || !isLive(instance.status)) {
      throw new NotFoundException("Daemon is not running in this workspace");
    }
    instance.stopRequested = true;
    cancelPending(instance);
    cancelLiveness(instance);
    cancelHealth(instance);
    if (instance.status == DaemonStatus.RESTARTING) {
      // No live session — the pending relaunch was the only thing to cancel.
      transition(instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "stopped", null);
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
      if (commandId.equals(instance.commandId) && instance.status != DaemonStatus.STOPPED) {
        transition(instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "stopped", null);
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
        if (instance.status == DaemonStatus.RESTARTING) {
          // No live session — the pending relaunch (just cancelled) was all there was to stop.
          transition(
              instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "workspace stopped", null);
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
        if (instance.status != DaemonStatus.STOPPED) {
          transition(
              instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "workspace stopped", null);
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
  public List<DaemonInstanceDto> effectiveDaemons(String repoId, String workspaceId) {
    List<RepositoryDaemonDto> definitions = repositoryDaemonService.resolveAll(repoId);
    // Lazily reconcile a daemon still running from before a qits restart: its supervisor state was
    // lost, but its detached session lives on. Probe each untracked daemon once — if its session is
    // alive, re-adopt it (resume the log follow + liveness poll) so it reads RUNNING again with
    // logs, instead of the blanket "STOPPED, command INTERRUPTED" the old in-memory model produced.
    for (RepositoryDaemonDto definition : definitions) {
      adoptIfRunning(repoId, workspaceId, definition);
    }
    synchronized (this) {
      List<DaemonInstanceDto> result = new ArrayList<>(definitions.size());
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
   * registry command (the "follower"), which keeps the ready-pattern, observers, per-line
   * persistence, and terminal re-attach working unchanged. Liveness is polled from the session, not
   * a host exit callback.
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
    // A fresh start reads the log from the top, so the ready line is seen and flips STARTING→READY.
    // An adopted session is already READY and tails from the end, so no old line re-triggers it.
    if (!adopt && daemon.readyPattern() != null) {
      sinks.add(
          new ReadyPatternSink(Pattern.compile(daemon.readyPattern()), () -> markReady(instance)));
    }
    List<ObservedLineListener> outputObservers = new ArrayList<>();
    for (var observer : daemon.observers()) {
      outputObservers.add(observerFor(observer, instance));
    }
    ProcessOutputTap outputTap =
        outputObservers.isEmpty() ? null : new ProcessOutputTap(outputObservers);

    String publicBase =
        daemon.webView() != null
            ? DaemonProxyPath.servedBase(
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
    environment.put(DAEMON_MARKER_ENV, daemon.id());
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
        outputTap,
        sinks.toArray(CommandOutputSink[]::new));

    resolveOrigin(instance);
    if (instance.tails.isEmpty()) {
      instance.tails = startFileTails(instance);
    }

    if (adopt) {
      transition(
          instance,
          DaemonStatus.READY,
          DaemonEventSeverity.INFO,
          "adopted (already running after a qits restart)",
          null);
    } else {
      transition(
          instance,
          DaemonStatus.STARTING,
          DaemonEventSeverity.INFO,
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
   * {@link DaemonStatus} or publishes a daemon event.
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
   * One observer instance per (observer definition, source), so LOG_LEVEL batches stay contiguous
   * within one source and anchors are coherent. Findings are dispatched onto the scheduler rather
   * than synchronously: a producer delivers lines under its own monitor (the file tail's final
   * drain even runs under the supervisor monitor), so acquiring the supervisor monitor inline from
   * an observer callback could deadlock.
   */
  private ObservedLineListener observerFor(LogObserverDto observer, Instance instance) {
    if (observer.kind() == LogObserverKind.PATTERN) {
      return new PatternLogObserver(
          Pattern.compile(observer.pattern()),
          observer.severity() != null ? observer.severity() : DaemonEventSeverity.ERROR,
          finding -> scheduler.execute(() -> onFinding(instance, finding)));
    }
    return new LogLevelObserver(
        classifier, scheduler, finding -> scheduler.execute(() -> onFinding(instance, finding)));
  }

  /**
   * One {@code tail -F} per FILE source, run <em>inside the workspace's container</em> (the working
   * tree lives at {@code /workspace} there, not on the host), each feeding its own set of
   * observers. Source paths are relative to {@code /workspace} and re-guarded here against
   * traversal (they were validated lexically at definition time too). Tails outlive restarts and
   * stop when the instance settles — see {@link #transition}.
   */
  private List<ContainerTailSource> startFileTails(Instance instance) {
    List<LogSourceDto> sources = instance.daemon.sources();
    if (sources == null || sources.isEmpty() || instance.daemon.observers().isEmpty()) {
      return List.of();
    }
    String container = containers.containerName(instance.workspaceId, instance.repoId);
    List<ContainerTailSource> tails = new ArrayList<>();
    for (LogSourceDto source : sources) {
      if (!isSafeRelativePath(source.path())) {
        LOG.warnf("Skipping log source outside the workspace: %s", source.path());
        continue;
      }
      List<ObservedLineListener> observers = new ArrayList<>();
      for (var observer : instance.daemon.observers()) {
        observers.add(observerFor(observer, instance));
      }
      tails.add(
          new ContainerTailSource(tailArgv(container, source.path()), source.path(), observers));
    }
    return tails;
  }

  /**
   * The command that tails one source inside the container: {@code tail -n 0 -F -- <path>} run with
   * workdir {@code /workspace}. {@code -n 0} starts at end (history is not replayed); {@code -F}
   * retries a missing file and reopens on rotation, so a late-appearing log is read from line 1.
   */
  private List<String> tailArgv(String container, String path) {
    List<String> argv =
        new ArrayList<>(containers.execArgv(container, false, "/workspace", Map.of()));
    argv.add("tail");
    argv.add("--sleep-interval=" + (fileTailPollMillis / 1000.0));
    argv.add("-n");
    argv.add("0");
    argv.add("-F");
    argv.add("--");
    argv.add(path);
    return argv;
  }

  /**
   * Rejects an absolute source path or one that could climb out of {@code /workspace} via {@code
   * ..}.
   */
  private static boolean isSafeRelativePath(String path) {
    if (path == null || path.isBlank() || path.startsWith("/") || path.indexOf('\0') >= 0) {
      return false;
    }
    for (String segment : path.split("/")) {
      if (segment.equals("..")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Kill any process in the workspace's container left over from a previous run of this daemon —
   * chiefly Quarkus dev mode's forked application JVM, which escapes the launched process group (so
   * a stop's {@code kill -- -pgid} misses it), keeps binding the http port, and would wedge this
   * start ("Port 8080 seems to be in use"). Every daemon process is tagged {@link
   * #DAEMON_MARKER_ENV}={@code <id>} (inherited by its forks), so the escaped child is found via
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
    String marker = DAEMON_MARKER_ENV + "=" + instance.daemon.id();
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

  private static void closeTails(Instance instance) {
    for (ContainerTailSource tail : instance.tails) {
      tail.close();
    }
    instance.tails = List.of();
  }

  private synchronized void markReady(Instance instance) {
    if (instance.status == DaemonStatus.STARTING) {
      cancelPending(instance);
      transition(
          instance, DaemonStatus.READY, DaemonEventSeverity.INFO, "ready (pattern matched)", null);
    }
  }

  private synchronized void graceReady(Instance instance, String commandId) {
    if (instance.status == DaemonStatus.STARTING && commandId.equals(instance.commandId)) {
      transition(
          instance,
          DaemonStatus.READY,
          DaemonEventSeverity.INFO,
          "ready (grace period elapsed)",
          null);
    }
  }

  private synchronized void onFinding(Instance instance, ObserverFinding finding) {
    if (!isLive(instance.status)) {
      return; // Late-arriving classification after the run ended.
    }
    events.publish(
        new DaemonEventDto(
            instance.repoId,
            instance.workspaceId,
            instance.daemon.id(),
            instance.daemon.name(),
            DaemonEventKind.ERROR_DETECTED,
            finding.severity(),
            instance.status,
            finding.errorType() + ": " + finding.summary(),
            finding.logExcerpt(),
            instance.commandId,
            finding.source(),
            finding.anchorFrom(),
            finding.anchorTo(),
            finding.sourceEpoch(),
            Instant.now()));
    if (instance.status == DaemonStatus.READY && finding.severity() == DaemonEventSeverity.ERROR) {
      // The ERROR_DETECTED event above already carried the evidence; the DEGRADED transition
      // itself is INFO so the agent isn't notified twice for one finding.
      transition(
          instance,
          DaemonStatus.DEGRADED,
          DaemonEventSeverity.INFO,
          "degraded (errors in output; process still alive)",
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
          DaemonStatus.STOPPED,
          DaemonEventSeverity.INFO,
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
            instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "exited cleanly", null);
      } else {
        transition(
            instance,
            DaemonStatus.CRASHED,
            DaemonEventSeverity.ERROR,
            "crashed (exit " + exitCode + ")",
            tail);
      }
      return;
    }
    if (instance.restartCount >= instance.daemon.maxRestarts()) {
      transition(
          instance,
          DaemonStatus.CRASHED,
          DaemonEventSeverity.ERROR,
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
        DaemonStatus.RESTARTING,
        DaemonEventSeverity.WARNING,
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
    if (instance.stopRequested || instance.status != DaemonStatus.RESTARTING) {
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
          DaemonStatus.CRASHED,
          DaemonEventSeverity.ERROR,
          "relaunch failed: " + e.getMessage(),
          null);
    }
  }

  /**
   * Re-read the daemon definition from the repository before an automatic relaunch, so a mid-run
   * edit (webView added, startScript changed, observers/env updated) takes effect on the fresh
   * process instead of the supervisor resurrecting the launch-time snapshot. Falls back to the
   * pinned copy if the definition was deleted mid-flight — {@link #launch} still needs something to
   * start, and the next liveness/settle cycle will clean it up. Without this, the proxy's {@link
   * #proxyTarget} (which reads {@code instance.daemon.webView()}) and the REST list (which prefers
   * the database definition) answer from two different snapshots after an {@code ON_FAILURE}
   * restart — see docs/issues
   * resolved/2026-07-06_daemon-relaunch-uses-stale-definition-after-webview-update.
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
      DaemonStatus status,
      DaemonEventSeverity severity,
      String summary,
      String logExcerpt) {
    instance.status = status;
    changePublisher.fire(instance.repoId, instance.workspaceId, WorkspaceChangeHint.Topic.DAEMONS);
    if (!isLive(status)) {
      closeTails(instance);
    }
    // No process to probe outside STARTING/READY/DEGRADED (RESTARTING included — during the
    // backoff there is nothing listening, and the relaunch starts a fresh probe epoch anyway).
    if (status != DaemonStatus.STARTING
        && status != DaemonStatus.READY
        && status != DaemonStatus.DEGRADED) {
      cancelHealth(instance);
    }
    events.publish(
        new DaemonEventDto(
            instance.repoId,
            instance.workspaceId,
            instance.daemon.id(),
            instance.daemon.name(),
            DaemonEventKind.STATUS_CHANGED,
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
  public record ProxyTarget(DaemonStatus status, ProxyOrigin origin) {}

  private static void cancelPending(Instance instance) {
    if (instance.pending != null) {
      instance.pending.cancel(false);
      instance.pending = null;
    }
  }

  private static boolean isLive(DaemonStatus status) {
    return status == DaemonStatus.STARTING
        || status == DaemonStatus.READY
        || status == DaemonStatus.DEGRADED
        || status == DaemonStatus.RESTARTING;
  }

  private DaemonInstanceDto toInstanceDto(
      Instance instance, RepositoryDaemonDto definition, String workspaceId) {
    RepositoryDaemonDto daemon = definition != null ? definition : instance.daemon;
    String proxyPath =
        daemon.webView() != null
            ? DaemonProxyPath.servedBase(workspaceId, daemon.id(), daemon.webView().basePath())
            : null;
    if (instance == null) {
      return new DaemonInstanceDto(
          daemon,
          DaemonStatus.STOPPED,
          0,
          null,
          proxyPath,
          HealthProbeService.snapshotOrUnknown(null, daemon.healthChecks()));
    }
    return new DaemonInstanceDto(
        daemon,
        instance.status,
        instance.restartCount,
        instance.commandId,
        proxyPath,
        HealthProbeService.snapshotOrUnknown(instance.health, daemon.healthChecks()));
  }
}
