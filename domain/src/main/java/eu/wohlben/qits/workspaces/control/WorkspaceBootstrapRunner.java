package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.BootstrapOutcome;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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

  @Inject WorkspaceResolver workspaceResolver;

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

  /**
   * Subscribe the persistent outcome recorder at startup, so <b>every</b> chain the daemon reports
   * lands as {@code workspace_bootstrap_run} rows — including runs the host never awaited (the daemon's own
   * HTTP {@code POST /bootstrap-commands/run}, reached through the container proxy, which used to
   * answer 202 and write nothing host-side). The per-run {@link RecordingSink} keeps the process
   * segments; rows have exactly one writer, here.
   */
  void subscribeRecorder(@Observes StartupEvent event) {
    if (driver.isUnsatisfied()) {
      return;
    }
    driver
        .get()
        .subscribe(
            (repoId, workspaceId, rowId, stepName, outcome, exitCode) -> {
              try {
                BootstrapOutcome resolved = BootstrapOutcome.valueOf(outcome);
                // A skip has no run of its own — record no exit code (the check's non-zero is the
                // skip reason, not a run outcome). commandId is always null: the step ran in the
                // container, not via a host Command row.
                Integer recordedExit = resolved == BootstrapOutcome.SKIPPED ? null : exitCode;
                bootstrapRunService.recordOutcome(
                    repoId, workspaceId, rowId, stepName, stepName, resolved, null, recordedExit);
              } catch (RuntimeException e) {
                // A workspace deleted mid-chain (NotFound) or an unknown outcome string must not
                // escape into the dispatcher — a dropped row is diagnostic loss, not a failure.
                LOG.debugf(
                    "bootstrap outcome not recorded for workspace %s step '%s': %s",
                    workspaceId, stepName, e.getMessage());
              }
            });
  }

  /** Whether a bootstrap run is currently in flight for the workspace. */
  public boolean isChainRunning(Long id) {
    Workspace workspace = workspaceResolver.resolveActive(id);
    return inFlight.containsKey(key(workspace.repositoryId, workspace.workspaceId));
  }

  void onContainerStarted(@ObservesAsync WorkspaceContainerStarted evt) {
    TechnicalProcess process = processRegistry.find(evt.technicalProcessId()).orElse(null);
    if (!evt.freshProvision() || !autorunEnabled || driver.isUnsatisfied()) {
      // Plain restart, kill switch, or no daemon control plane: nothing between the container and
      // its services — the daemon didn't (re)run the chain, so go straight to auto-start.
      containerEvents.fireReadyForServices(
          evt.repoId(), evt.workspaceId(), evt.workspaceRowId(), evt.technicalProcessId());
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
          awaitChain(evt.repoId(), evt.workspaceId(), evt.workspaceRowId(), process);
      boolean ok = result.map(WorkspaceBootstrapDriver.Result::ok).orElse(false);
      if (ok) {
        containerEvents.fireReadyForServices(
            evt.repoId(), evt.workspaceId(), evt.workspaceRowId(), evt.technicalProcessId());
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
      changePublisher.fire(evt.repoId(), evt.workspaceRowId(), WorkspaceChangeHint.Topic.BOOTSTRAP);
    }
  }

  /**
   * Re-run the whole chain on demand (async; progress arrives over BOOTSTRAP hints). On success,
   * service auto-start proceeds — the recovery path after a failed provision-time run.
   */
  public void runChainAsync(Long id) {
    Workspace workspace = workspaceResolver.resolveActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    submitManual(
        repoId,
        workspaceId,
        id,
        () -> {
          workspaceService.ensureContainer(id);
          Optional<WorkspaceBootstrapDriver.Result> result =
              runDaemon(repoId, workspaceId, id, null, null);
          if (result.map(WorkspaceBootstrapDriver.Result::ok).orElse(false)) {
            containerEvents.fireReadyForServices(repoId, workspaceId, id, null);
          }
        });
  }

  /**
   * Re-run one step on demand (async). Does not touch service auto-start. {@code stepId} is the
   * config-declared {@code id:} (which defaults to the step name) — resolved against the
   * workspace's ConfigView to the step name the daemon understands.
   */
  public void runSingleAsync(Long id, String stepId) {
    Workspace workspace = workspaceResolver.resolveActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    String stepName = resolveStepName(id, stepId);
    submitManual(
        repoId,
        workspaceId,
        id,
        () -> {
          workspaceService.ensureContainer(id);
          runDaemon(repoId, workspaceId, id, stepName, null);
        });
  }

  /**
   * Maps a config-declared bootstrap {@code id:} to its step name. When the config is readable the
   * id must resolve (404 otherwise); when no daemon is live yet to read it (a cold workspace — the
   * manual run itself provisions), the id passes through (ids default to names, so it is usually
   * already the step name, and the daemon errors on a genuine mismatch).
   */
  private String resolveStepName(Long workspaceId, String stepId) {
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
  private void submitManual(String repoId, String workspaceId, Long rowId, Runnable work) {
    if (driver.isUnsatisfied()) {
      throw new BadRequestException(
          "No workspace-daemon control plane is available to run bootstrap for this workspace");
    }
    if (inFlight.putIfAbsent(key(repoId, workspaceId), Boolean.TRUE) != null) {
      throw new BadRequestException("A bootstrap run is already in flight for this workspace");
    }
    changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    manualRunExecutor.submit(
        () -> {
          try {
            work.run();
          } catch (RuntimeException e) {
            LOG.warnf(e, "Manual bootstrap run failed for workspace %s/%s", repoId, workspaceId);
          } finally {
            inFlight.remove(key(repoId, workspaceId));
            changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
          }
        });
  }

  /** Await the daemon's autonomous boot-time chain, recording each step through {@code sink}. */
  private Optional<WorkspaceBootstrapDriver.Result> awaitChain(
      String repoId, String workspaceId, Long rowId, TechnicalProcess process) {
    changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    return driver
        .get()
        .awaitBootstrap(
            rowId,
            new RecordingSink(repoId, workspaceId, rowId, process),
            Duration.ofMillis(connectMillis),
            Duration.ofMillis(chainAwaitMillis));
  }

  /**
   * Ask the daemon to re-run the chain (or one step) and await it, recording through {@code sink}.
   */
  private Optional<WorkspaceBootstrapDriver.Result> runDaemon(
      String repoId, String workspaceId, Long rowId, String onlyName, TechnicalProcess process) {
    changePublisher.fire(repoId, rowId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    return driver
        .get()
        .runBootstrap(
            rowId,
            onlyName,
            new RecordingSink(repoId, workspaceId, rowId, process),
            Duration.ofMillis(chainAwaitMillis));
  }

  /**
   * Turns the daemon's streamed step events into {@code bootstrap:<name>} process segments for the
   * awaited run. Outcome rows are <b>not</b> written here — the persistent recorder ({@link
   * #subscribeRecorder}) is their single writer, so a run with no awaiter records the same rows.
   */
  private final class RecordingSink implements WorkspaceBootstrapDriver.StepSink {

    private final Long rowId;
    private final String repoId;
    private final String workspaceId;
    private final TechnicalProcess process;
    private final Set<String> openedSegments = new HashSet<>();

    private RecordingSink(
        String repoId, String workspaceId, Long rowId, TechnicalProcess process) {
      this.rowId = rowId;
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
      // No row write here: the persistent recorder (subscribeRecorder) is the single writer of
      // BootstrapRun rows, awaited run or not. This sink only settles the process segments.
      BootstrapOutcome resolved = BootstrapOutcome.valueOf(outcome);
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
