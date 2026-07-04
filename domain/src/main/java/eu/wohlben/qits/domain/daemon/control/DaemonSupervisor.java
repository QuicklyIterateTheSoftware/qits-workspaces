package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.dto.CommandDto;
import eu.wohlben.qits.domain.daemon.control.DaemonResolutionService.ResolvedDaemon;
import eu.wohlben.qits.domain.daemon.dto.DaemonConfigurationDto;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.dto.DaemonInstanceDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.LogObserverKind;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    ResolvedDaemon daemon;
    DaemonStatus status = DaemonStatus.STOPPED;
    int restartCount;
    String commandId;
    boolean stopRequested;
    TailSink tail;
    ScheduledFuture<?> pending;

    Instance(String repoId, String worktreeId, ResolvedDaemon daemon) {
      this.repoId = repoId;
      this.worktreeId = worktreeId;
      this.daemon = daemon;
    }
  }

  private record Key(String repoId, String worktreeId, String daemonId) {}

  private final Map<Key, Instance> instances = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "daemon-supervisor");
            thread.setDaemon(true);
            return thread;
          });

  @Inject CommandService commandService;

  @Inject CommandRegistry registry;

  @Inject DaemonResolutionService resolutionService;

  @Inject DaemonEventService events;

  @Inject LogClassifier classifier;

  /** Without a ready pattern, STARTING flips to READY after this long (if still alive). */
  @ConfigProperty(name = "qits.daemons.ready-grace-ms", defaultValue = "10000")
  long readyGraceMillis;

  /** How long a graceful stop waits after the stop signal before force-killing. */
  @ConfigProperty(name = "qits.daemons.stop-grace-ms", defaultValue = "5000")
  long stopGraceMillis;

  /** First restart delay; doubles per consecutive restart, capped at 30s. */
  @ConfigProperty(name = "qits.daemons.restart-backoff-initial-ms", defaultValue = "1000")
  long restartBackoffInitialMillis;

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * Start {@code daemonId} in the worktree. One running instance per (worktree, daemon) is enforced
   * — "restart" beats two dev servers fighting over a port.
   */
  public synchronized DaemonInstanceDto start(String repoId, String worktreeId, String daemonId) {
    ResolvedDaemon daemon = resolutionService.resolveForRepository(repoId, daemonId);
    Key key = new Key(repoId, worktreeId, daemonId);
    Instance existing = instances.get(key);
    if (existing != null && isLive(existing.status)) {
      throw new BadRequestException(
          "Daemon '" + daemon.name() + "' is already running in this worktree");
    }
    Instance instance = new Instance(repoId, worktreeId, daemon);
    instances.put(key, instance);
    launch(instance);
    return toInstanceDto(instance, null);
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
      return toInstanceDto(instance, null);
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
    return toInstanceDto(instance, null);
  }

  /** Every effective daemon of the repository with its runtime state in this worktree. */
  public List<DaemonInstanceDto> effectiveDaemons(String repoId, String worktreeId) {
    List<DaemonConfigurationDto> definitions = resolutionService.effectiveDaemons(repoId);
    synchronized (this) {
      List<DaemonInstanceDto> result = new ArrayList<>(definitions.size());
      for (DaemonConfigurationDto definition : definitions) {
        Instance instance = instances.get(new Key(repoId, worktreeId, definition.id()));
        result.add(toInstanceDto(instance, definition));
      }
      return result;
    }
  }

  // --- Lifecycle internals (all under the supervisor monitor) ---------------------------------

  private void launch(Instance instance) {
    ResolvedDaemon daemon = instance.daemon;
    List<CommandOutputSink> sinks = new ArrayList<>();
    instance.tail = new TailSink();
    sinks.add(instance.tail);
    if (daemon.readyPattern() != null) {
      sinks.add(
          new ReadyPatternSink(Pattern.compile(daemon.readyPattern()), () -> markReady(instance)));
    }
    for (var observer : daemon.observers()) {
      if (observer.kind() == LogObserverKind.PATTERN) {
        sinks.add(
            new PatternLogObserver(
                Pattern.compile(observer.pattern()),
                observer.severity() != null ? observer.severity() : DaemonEventSeverity.ERROR,
                finding -> onFinding(instance, finding)));
      } else {
        sinks.add(
            new LogLevelObserver(classifier, scheduler, finding -> onFinding(instance, finding)));
      }
    }

    CommandDto command =
        commandService.launchDaemon(
            instance.repoId,
            instance.worktreeId,
            daemon.name(),
            daemon.startScript(),
            daemon.environment(),
            (commandId, exitCode, terminatedManually) ->
                handleExit(instance, commandId, exitCode, terminatedManually),
            sinks.toArray(CommandOutputSink[]::new));
    instance.commandId = command.id();
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
        event(
            instance,
            DaemonEventKind.ERROR_DETECTED,
            finding.severity(),
            instance.status,
            finding.errorType() + ": " + finding.summary(),
            finding.logExcerpt()));
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
    events.publish(
        event(instance, DaemonEventKind.STATUS_CHANGED, severity, status, summary, logExcerpt));
  }

  private DaemonEventDto event(
      Instance instance,
      DaemonEventKind kind,
      DaemonEventSeverity severity,
      DaemonStatus status,
      String summary,
      String logExcerpt) {
    return new DaemonEventDto(
        instance.repoId,
        instance.worktreeId,
        instance.daemon.id(),
        instance.daemon.name(),
        kind,
        severity,
        status,
        summary,
        logExcerpt,
        instance.commandId,
        Instant.now());
  }

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

  private DaemonInstanceDto toInstanceDto(Instance instance, DaemonConfigurationDto definition) {
    DaemonConfigurationDto daemon =
        definition != null
            ? definition
            : new DaemonConfigurationDto(
                instance.daemon.id(),
                instance.daemon.name(),
                instance.daemon.description(),
                instance.daemon.startScript(),
                instance.daemon.readyPattern(),
                instance.daemon.stopSignal(),
                instance.daemon.restartPolicy(),
                instance.daemon.maxRestarts(),
                instance.daemon.scope(),
                instance.daemon.repositoryId(),
                instance.daemon.environment(),
                instance.daemon.observers());
    if (instance == null) {
      return new DaemonInstanceDto(daemon, DaemonStatus.STOPPED, 0, null);
    }
    return new DaemonInstanceDto(
        daemon, instance.status, instance.restartCount, instance.commandId);
  }
}
