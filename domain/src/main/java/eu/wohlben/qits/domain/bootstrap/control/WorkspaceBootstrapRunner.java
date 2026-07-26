package eu.wohlben.qits.domain.bootstrap.control;

import eu.wohlben.qits.domain.bootstrap.entity.BootstrapOutcome;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.domain.repository.control.WorkspaceConfigReader;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerStarted;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Surfaces the in-container workspace-daemon's bootstrap chain on the host: the daemon runs the
 * chain itself (from its own {@code .qits-config.yml}, between the self-clone and service start —
 * docs/epics/qits-workspace-daemon/ Part 3); this runner <b>awaits</b> it over the control socket
 * ({@link WorkspaceBootstrapDriver}), records each step's outcome ({@link BootstrapRunService}),
 * settles the {@code bootstrap:<name>} process segments, and gates service auto-start on the
 * result. The chain execution that used to live here (host {@code docker exec} of each command)
 * moved into the daemon; the host no longer touches the container to run bootstrap.
 *
 * <ul>
 *   <li><b>Fresh provision</b> — observes {@link WorkspaceContainerStarted} (async, the {@code
 *       ServiceLifecycleCoupler} precedent) and awaits the daemon's chain only for {@code
 *       freshProvision} transitions (a bare clone was just bootstrapped; a restarted container kept
 *       its state, and the daemon does not re-run). A plain restart, or the autorun kill switch,
 *       passes straight through to service auto-start.
 *   <li><b>Manual re-run</b> — {@link #runChainAsync}/{@link #runSingleAsync} send the daemon a
 *       re-run request; the recovery path after a failed provision-time chain.
 * </ul>
 *
 * <p>Sequencing vs service auto-start is structural: this runner is the only firer of {@link
 * WorkspaceContainerEventPublisher#fireReadyForServices} — on pass-through immediately, and after a
 * successful chain (or manual full-chain run). A <b>failed chain never fires it</b>: service
 * auto-start is skipped — a dev server on an unbootstrapped checkout would only burn its restart
 * budget crash-looping (and qits' own dogfood build guard would fail the moment something listens
 * on the dev port). The failure surfaces on the workspace surface (BOOTSTRAP hints over SSE).
 *
 * <p>Reentrancy: a manual run's {@code ensureContainer} may itself fresh-provision and fire {@link
 * WorkspaceContainerStarted} — the per-workspace in-flight guard makes the event-triggered await
 * yield to the already-running manual one (which fires ready itself on success).
 */
@ApplicationScoped
public class WorkspaceBootstrapRunner {

  private static final Logger LOG = Logger.getLogger(WorkspaceBootstrapRunner.class);

  @Inject BootstrapRunService bootstrapRunService;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject TechnicalProcessRegistry processRegistry;

  /**
   * The in-container config read (Part 2) — the only source of the bootstrap chain since Part 5
   * removed the DB store. Absent in apps without the backend (cli); a single-step run then skips
   * its existence check and forwards the requested name straight to the daemon.
   */
  @Inject Instance<WorkspaceConfigReader> configReader;

  /**
   * The socket-backed driver that awaits (and re-triggers) the daemon's chain. Optional — apps
   * without the backend (cli) have no bean; when it is absent there is no daemon to run bootstrap,
   * so the workspace passes straight through to services (the checkout still exists).
   */
  @Inject Instance<WorkspaceBootstrapDriver> driver;

  /**
   * Kill switch for the provision-time trigger (also forwarded to the daemon so it skips the run).
   */
  @ConfigProperty(name = "qits.bootstrap.autorun-enabled", defaultValue = "true")
  boolean autorunEnabled;

  /**
   * How long the host waits for the daemon's terminal {@code Bootstrapped} once a daemon is live.
   * This bounds the <b>whole chain</b>, so it must comfortably exceed the daemon's <b>per-step</b>
   * budget ({@code qits.workspace-daemon.bootstrap-timeout-ms}, default 1h) times the step count —
   * otherwise a legitimate multi-step chain the daemon is still running (and would finish
   * successfully) trips this host timeout and is falsely recorded as failed. Default 6h covers a
   * chain of several maxed-out steps; raise it for pathologically long chains. It is a dead-daemon
   * backstop, not a per-step bound — the daemon terminates each overrunning step itself.
   */
  @ConfigProperty(name = "qits.bootstrap.await-timeout-ms", defaultValue = "21600000")
  long chainAwaitMillis;

  /**
   * How long to wait for a live daemon before giving up (the daemon just provisioned, so short).
   */
  @ConfigProperty(name = "qits.bootstrap.connect-timeout-ms", defaultValue = "30000")
  long connectMillis;

  /** Workspaces with a chain (or single command) in flight; also the "chain running" surface. */
  private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

  /** Manual runs block for up to the full chain duration, so they get their own threads. */
  private final ExecutorService manualRunExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "bootstrap-manual-run");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    manualRunExecutor.shutdownNow();
  }

  /** Whether a bootstrap run is currently in flight for the workspace. */
  public boolean isChainRunning(String repoId, String workspaceId) {
    return inFlight.containsKey(key(repoId, workspaceId));
  }

  void onContainerStarted(@ObservesAsync WorkspaceContainerStarted evt) {
    TechnicalProcess process = processRegistry.find(evt.technicalProcessId()).orElse(null);
    if (!evt.freshProvision() || !autorunEnabled || driver.isUnsatisfied()) {
      // Plain restart, kill switch, or no daemon control plane: nothing between the container and
      // its services — the daemon didn't (re)run the chain, so go straight to auto-start.
      containerEvents.fireReadyForServices(
          evt.repoId(), evt.workspaceId(), evt.technicalProcessId());
      return;
    }
    if (inFlight.putIfAbsent(key(evt.repoId(), evt.workspaceId()), Boolean.TRUE) != null) {
      // A manual run provisioned this container and owns the chain; it fires ready on success. This
      // start's process can't observe that run (its ready event carries no process id), so close
      // its
      // stream cleanly rather than hang it. (Residual limitation: a green Start here does not vouch
      // for the delegated chain — see
      // docs/issues/2026-07-19_streamed-start-verdict-delegated-bootstrap.md.)
      if (process != null) {
        process.appendLine(
            "bootstrap",
            "A manually triggered bootstrap run is already in flight and owns this chain — its"
                + " outcome and the service phase are tracked on the workspace Bootstrap tab.");
        process.settleSegment("bootstrap", true);
        process.expectServices(List.of());
      }
      return;
    }
    try {
      Optional<WorkspaceBootstrapDriver.Result> result =
          awaitChain(evt.repoId(), evt.workspaceId(), process);
      boolean ok = result.map(WorkspaceBootstrapDriver.Result::ok).orElse(false);
      if (ok) {
        containerEvents.fireReadyForServices(
            evt.repoId(), evt.workspaceId(), evt.technicalProcessId());
      } else if (process != null) {
        // Failed chain (or no daemon answered): no service phase. Declaring the empty set ends the
        // process now — its verdict is already `failed` via the failed bootstrap segment.
        process.expectServices(List.of());
      }
    } catch (RuntimeException e) {
      LOG.errorf(
          e,
          "Bootstrap await failed unexpectedly for workspace %s/%s",
          evt.workspaceId(),
          evt.repoId());
      if (process != null) {
        process.appendLine("bootstrap", "Bootstrap failed unexpectedly: " + e.getMessage());
        process.settleSegment("bootstrap", false);
        process.expectServices(List.of());
      }
    } finally {
      inFlight.remove(key(evt.repoId(), evt.workspaceId()));
      // A final BOOTSTRAP hint after the guard is released so the surface's "chain running"
      // indicator clears even when the chain aborted.
      changePublisher.fire(evt.repoId(), evt.workspaceId(), WorkspaceChangeHint.Topic.BOOTSTRAP);
    }
  }

  /**
   * Re-run the whole chain on demand (async; progress arrives over BOOTSTRAP hints). On success,
   * service auto-start proceeds — the recovery path after a failed provision-time run.
   */
  public void runChainAsync(String repoId, String workspaceId) {
    submitManual(
        repoId,
        workspaceId,
        () -> {
          workspaceService.ensureContainer(repoId, workspaceId);
          Optional<WorkspaceBootstrapDriver.Result> result =
              runDaemon(repoId, workspaceId, null, null);
          if (result.map(WorkspaceBootstrapDriver.Result::ok).orElse(false)) {
            containerEvents.fireReadyForServices(repoId, workspaceId, null);
          }
        });
  }

  /**
   * Re-run one step on demand (async). Does not touch service auto-start. {@code stepId} is the
   * config-declared {@code id:} (which defaults to the step name) — resolved against the
   * workspace's ConfigView to the step name the daemon understands.
   */
  public void runSingleAsync(String repoId, String workspaceId, String stepId) {
    String stepName = resolveStepName(workspaceId, stepId);
    submitManual(
        repoId,
        workspaceId,
        () -> {
          workspaceService.ensureContainer(repoId, workspaceId);
          runDaemon(repoId, workspaceId, stepName, null);
        });
  }

  /**
   * Maps a config-declared bootstrap {@code id:} to its step name. When the config is readable the
   * id must resolve (404 otherwise); when no daemon is live yet to read it (a cold workspace — the
   * manual run itself provisions), the id passes through (ids default to names, so it is usually
   * already the step name, and the daemon errors on a genuine mismatch).
   */
  private String resolveStepName(String workspaceId, String stepId) {
    if (configReader.isUnsatisfied()) {
      return stepId;
    }
    return configReader
        .get()
        .readConfig(workspaceId)
        .map(
            view ->
                view.config().bootstrap().stream()
                    .filter(decl -> decl.id().equals(stepId))
                    .findFirst()
                    .map(QitsConfig.BootstrapDecl::name)
                    .orElseThrow(
                        () ->
                            new NotFoundException(
                                "Bootstrap step not declared in the workspace qits config: "
                                    + stepId)))
        .orElse(stepId);
  }

  /** Enter the in-flight guard and hand the work to the manual-run executor. */
  private void submitManual(String repoId, String workspaceId, Runnable work) {
    if (driver.isUnsatisfied()) {
      throw new BadRequestException(
          "No workspace-daemon control plane is available to run bootstrap for this workspace");
    }
    if (inFlight.putIfAbsent(key(repoId, workspaceId), Boolean.TRUE) != null) {
      throw new BadRequestException("A bootstrap run is already in flight for this workspace");
    }
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    manualRunExecutor.submit(
        () -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            LOG.warnf(e, "Manual bootstrap run failed for workspace %s/%s", repoId, workspaceId);
          } finally {
            inFlight.remove(key(repoId, workspaceId));
            changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
          }
        });
  }

  /** Await the daemon's autonomous boot-time chain, recording each step through {@code sink}. */
  private Optional<WorkspaceBootstrapDriver.Result> awaitChain(
      String repoId, String workspaceId, TechnicalProcess process) {
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    return driver
        .get()
        .awaitBootstrap(
            repoId,
            workspaceId,
            new RecordingSink(repoId, workspaceId, process),
            Duration.ofMillis(connectMillis),
            Duration.ofMillis(chainAwaitMillis));
  }

  /**
   * Ask the daemon to re-run the chain (or one step) and await it, recording through {@code sink}.
   */
  private Optional<WorkspaceBootstrapDriver.Result> runDaemon(
      String repoId, String workspaceId, String onlyName, TechnicalProcess process) {
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    return driver
        .get()
        .runBootstrap(
            repoId,
            workspaceId,
            onlyName,
            new RecordingSink(repoId, workspaceId, process),
            Duration.ofMillis(chainAwaitMillis));
  }

  /**
   * Turns the daemon's streamed step events into host state: {@code bootstrap:<name>} process
   * segments, {@link BootstrapRun} outcome rows (keyed by the step name — the file is the only
   * chain source, so the name <em>is</em> the stable snapshot key), and BOOTSTRAP UI hints (fired
   * by {@link BootstrapRunService#recordOutcome}).
   */
  private final class RecordingSink implements WorkspaceBootstrapDriver.StepSink {
    private final String repoId;
    private final String workspaceId;
    private final TechnicalProcess process;
    private final Set<String> openedSegments = new HashSet<>();

    private RecordingSink(String repoId, String workspaceId, TechnicalProcess process) {
      this.repoId = repoId;
      this.workspaceId = workspaceId;
      this.process = process;
    }

    @Override
    public void onStep(String name, String phase) {
      if (process == null) {
        return;
      }
      String segment = bootstrapSegment(name);
      if (openedSegments.add(segment)) {
        process.openSegment(segment);
      }
    }

    @Override
    public void onLine(String name, String line) {
      if (process != null) {
        process.appendLine(bootstrapSegment(name), line);
      }
    }

    @Override
    public void onOutcome(String name, String outcome, Integer exitCode) {
      BootstrapOutcome resolved = BootstrapOutcome.valueOf(outcome);
      // A skip has no run of its own — record no exit code (the check's non-zero is the skip
      // reason,
      // not a run outcome). commandId is always null now: the step ran in the container, not via a
      // host Command row (its live output is the bootstrap:<name> process segment, not a Command
      // log).
      Integer recordedExit = resolved == BootstrapOutcome.SKIPPED ? null : exitCode;
      bootstrapRunService.recordOutcome(
          repoId, workspaceId, name, name, resolved, null, recordedExit);
      if (process != null) {
        String segment = bootstrapSegment(name);
        if (openedSegments.add(segment)) {
          process.openSegment(segment); // a SKIP with no prior CHECK step still needs a segment
        }
        process.settleSegment(segment, resolved != BootstrapOutcome.FAILED);
      }
    }
  }

  /** The technical-process segment for one bootstrap step: {@code bootstrap:<step name>}. */
  public static String bootstrapSegment(String stepName) {
    return "bootstrap:" + stepName;
  }

  private static String key(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
