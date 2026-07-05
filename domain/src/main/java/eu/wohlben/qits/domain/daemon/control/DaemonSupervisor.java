package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.dto.CommandDto;
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
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
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
 * Supervises daemon instances: one per (worktree, daemon definition), enforced singleton. Each run
 * is an ordinary registry command (kind DAEMON) — the supervisor adds the state machine around it
 * ({@code STARTING → READY → DEGRADED/CRASHED/RESTARTING → STOPPED}), the restart policy with
 * exponential backoff, readiness detection, and graceful stop. All supervisor state is in-memory
 * next to the registry's sessions (same singleton rule); a JVM restart loses it and the instances'
 * commands are reconciled to INTERRUPTED like any other command.
 *
 * <p>Every transition and observer finding is published as a {@link DaemonEventDto} through {@link
 * DaemonEventService}, which fans out to the UI feed and the worktree's agent session.
 */
@ApplicationScoped
public class DaemonSupervisor {

  private static final Logger LOG = Logger.getLogger(DaemonSupervisor.class);

  private static final long MAX_RESTART_BACKOFF_MILLIS = 30_000;

  /** One supervised daemon in one worktree. Mutated only under the supervisor monitor. */
  private static final class Instance {
    final String repoId;
    final String worktreeId;
    RepositoryDaemonDto daemon;
    DaemonStatus status = DaemonStatus.STOPPED;
    int restartCount;
    String commandId;
    boolean stopRequested;
    TailSink tail;
    ScheduledFuture<?> pending;

    /**
     * The ephemeral localhost port the container published for the daemon's {@code httpPort} — the
     * proxy target. Null when the daemon isn't web-viewable, or when the container predates the
     * port declaration (needs recreation); re-resolved on every (re)launch.
     */
    Integer hostPort;

    /**
     * File-source tails (run inside the container); started once per instance, closed on settle.
     */
    List<ContainerTailSource> tails = List.of();

    Instance(String repoId, String worktreeId, RepositoryDaemonDto daemon) {
      this.repoId = repoId;
      this.worktreeId = worktreeId;
      this.daemon = daemon;
    }
  }

  private record Key(String repoId, String worktreeId, String daemonId) {}

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

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Start {@code daemonId} in the worktree. One running instance per (worktree, daemon) is enforced
   * — "restart" beats two dev servers fighting over a port.
   */
  public synchronized DaemonInstanceDto start(String repoId, String worktreeId, String daemonId) {
    RepositoryDaemonDto daemon = repositoryDaemonService.resolve(repoId, daemonId);
    Key key = new Key(repoId, worktreeId, daemonId);
    Instance existing = instances.get(key);
    if (existing != null && isLive(existing.status)) {
      throw new BadRequestException(
          "Daemon '" + daemon.name() + "' is already running in this worktree");
    }
    Instance instance = new Instance(repoId, worktreeId, daemon);
    instances.put(key, instance);
    launch(instance);
    return toInstanceDto(instance, null, worktreeId);
  }

  /** Gracefully stop a running instance: stop signal, grace period, then force-kill fallback. */
  public synchronized DaemonInstanceDto stop(String repoId, String worktreeId, String daemonId) {
    Instance instance = instances.get(new Key(repoId, worktreeId, daemonId));
    if (instance == null || !isLive(instance.status)) {
      throw new NotFoundException("Daemon is not running in this worktree");
    }
    instance.stopRequested = true;
    cancelPending(instance);
    if (instance.status == DaemonStatus.RESTARTING) {
      // No live process — the pending relaunch was the only thing to cancel.
      transition(instance, DaemonStatus.STOPPED, DaemonEventSeverity.INFO, "stopped", null);
      return toInstanceDto(instance, null, worktreeId);
    }
    String commandId = instance.commandId;
    if (!registry.signal(commandId, instance.daemon.stopSignal())) {
      // Signal delivery failed (process already gone or kill errored) — force immediately.
      registry.terminate(commandId);
    } else {
      // The kill fallback must not run under the supervisor monitor: terminate() joins the
      // reader thread, which may itself be blocked on a synchronized callback here.
      scheduler.schedule(
          () -> {
            if (registry.isRunning(commandId)) {
              LOG.infof(
                  "Daemon '%s' ignored SIG%s for %d ms; force-killing",
                  instance.daemon.name(), instance.daemon.stopSignal(), stopGraceMillis);
              registry.terminate(commandId);
            }
          },
          stopGraceMillis,
          TimeUnit.MILLISECONDS);
    }
    return toInstanceDto(instance, null, worktreeId);
  }

  /** Every daemon of the repository with its runtime state in this worktree. */
  public List<DaemonInstanceDto> effectiveDaemons(String repoId, String worktreeId) {
    List<RepositoryDaemonDto> definitions = repositoryDaemonService.resolveAll(repoId);
    synchronized (this) {
      List<DaemonInstanceDto> result = new ArrayList<>(definitions.size());
      for (RepositoryDaemonDto definition : definitions) {
        Instance instance = instances.get(new Key(repoId, worktreeId, definition.id()));
        result.add(toInstanceDto(instance, definition, worktreeId));
      }
      return result;
    }
  }

  // --- Lifecycle internals (all under the supervisor monitor) ---------------------------------

  private void launch(Instance instance) {
    RepositoryDaemonDto daemon = instance.daemon;
    List<CommandOutputSink> sinks = new ArrayList<>();
    instance.tail = new TailSink();
    sinks.add(instance.tail);
    if (daemon.readyPattern() != null) {
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
        daemon.httpPort() != null ? DaemonProxyPath.base(instance.worktreeId, daemon.id()) : null;
    CommandDto command =
        commandService.launchDaemon(
            instance.repoId,
            instance.worktreeId,
            daemon.name(),
            daemon.startScript(),
            daemon.environment(),
            daemon.otel(),
            publicBase,
            (commandId, exitCode, terminatedManually) ->
                handleExit(instance, commandId, exitCode, terminatedManually),
            outputTap,
            sinks.toArray(CommandOutputSink[]::new));
    instance.commandId = command.id();
    resolveHostPort(instance);
    if (instance.tails.isEmpty()) {
      instance.tails = startFileTails(instance);
    }
    transition(
        instance,
        DaemonStatus.STARTING,
        DaemonEventSeverity.INFO,
        instance.restartCount == 0
            ? "starting"
            : "starting (restart " + instance.restartCount + "/" + daemon.maxRestarts() + ")",
        null);
    if (daemon.readyPattern() == null) {
      String launchedCommandId = command.id();
      instance.pending =
          scheduler.schedule(
              () -> graceReady(instance, launchedCommandId),
              readyGraceMillis,
              TimeUnit.MILLISECONDS);
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
   * One {@code tail -F} per FILE source, run <em>inside the worktree's container</em> (the working
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
    String container = containers.containerName(instance.worktreeId, instance.repoId);
    List<ContainerTailSource> tails = new ArrayList<>();
    for (LogSourceDto source : sources) {
      if (!isSafeRelativePath(source.path())) {
        LOG.warnf("Skipping log source outside the worktree: %s", source.path());
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
            instance.worktreeId,
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

  private void transition(
      Instance instance,
      DaemonStatus status,
      DaemonEventSeverity severity,
      String summary,
      String logExcerpt) {
    instance.status = status;
    if (!isLive(status)) {
      closeTails(instance);
    }
    events.publish(
        new DaemonEventDto(
            instance.repoId,
            instance.worktreeId,
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
   * Resolve the published localhost port for a web-viewable daemon — the container is guaranteed
   * provisioned here ({@code prepare}'s ensureContainer ran inside launchDaemon). A null result
   * means the container predates the definition's {@code httpPort} (publishing is create-time
   * only): the daemon still runs, but the web view stays unavailable until the container is
   * recreated — surfaced as a WARNING event rather than failing the launch.
   */
  private void resolveHostPort(Instance instance) {
    Integer httpPort = instance.daemon.httpPort();
    if (httpPort == null) {
      instance.hostPort = null;
      return;
    }
    String container = containers.containerName(instance.worktreeId, instance.repoId);
    instance.hostPort = containers.hostPort(container, httpPort);
    if (instance.hostPort == null) {
      events.publish(
          new DaemonEventDto(
              instance.repoId,
              instance.worktreeId,
              instance.daemon.id(),
              instance.daemon.name(),
              DaemonEventKind.STATUS_CHANGED,
              DaemonEventSeverity.WARNING,
              instance.status,
              "web view unavailable: the worktree container does not publish port "
                  + httpPort
                  + " — recreate the container to pick it up",
              null,
              instance.commandId,
              null,
              null,
              null,
              null,
              Instant.now()));
    }
  }

  /**
   * The live proxy target for a (worktreeId, daemonId) pair — the daemon web-view proxy's only
   * lookup. The pair is unambiguous even though worktree slugs repeat across repositories, because
   * a daemon id is a UUID owned by exactly one repository. The port comes exclusively from
   * supervisor state (never from any request component) and targets localhost — the SSRF
   * constraint. A present target with a null {@code hostPort} means the container must be recreated
   * to publish the port.
   */
  public synchronized Optional<ProxyTarget> proxyTarget(String worktreeId, String daemonId) {
    for (Map.Entry<Key, Instance> entry : instances.entrySet()) {
      Key key = entry.getKey();
      if (key.worktreeId().equals(worktreeId) && key.daemonId().equals(daemonId)) {
        Instance instance = entry.getValue();
        if (instance.daemon.httpPort() == null) {
          return Optional.empty();
        }
        return Optional.of(new ProxyTarget(instance.status, instance.hostPort));
      }
    }
    return Optional.empty();
  }

  /** A web-viewable daemon instance as the proxy sees it: status + published localhost port. */
  public record ProxyTarget(DaemonStatus status, Integer hostPort) {}

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
      Instance instance, RepositoryDaemonDto definition, String worktreeId) {
    RepositoryDaemonDto daemon = definition != null ? definition : instance.daemon;
    String proxyPath =
        daemon.httpPort() != null ? DaemonProxyPath.base(worktreeId, daemon.id()) : null;
    if (instance == null) {
      return new DaemonInstanceDto(daemon, DaemonStatus.STOPPED, 0, null, proxyPath);
    }
    return new DaemonInstanceDto(
        daemon, instance.status, instance.restartCount, instance.commandId, proxyPath);
  }
}
