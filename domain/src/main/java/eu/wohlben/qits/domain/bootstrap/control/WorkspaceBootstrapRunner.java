package eu.wohlben.qits.domain.bootstrap.control;

import eu.wohlben.qits.domain.bootstrap.dto.BootstrapCommandDto;
import eu.wohlben.qits.domain.bootstrap.entity.BootstrapOutcome;
import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.control.CommandService.RunOutcome;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.process.control.SegmentLineSink;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import eu.wohlben.qits.domain.repository.control.QitsConfig;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerEventPublisher;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerStarted;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Runs a repository's bootstrap chain inside a workspace container: sequentially, in {@code
 * orderIndex} order, at {@code /workspace}, through the ordinary command machinery (every execute
 * is a persisted, log-streamed {@code Command} row). Two triggers:
 *
 * <ul>
 *   <li><b>Fresh provision</b> — observes {@link WorkspaceContainerStarted} (async, the {@code
 *       DaemonLifecycleCoupler} precedent) and runs the chain only for {@code freshProvision}
 *       transitions: a bare clone needs bootstrapping, a restarted container kept its state. When
 *       there is nothing to run (plain restart, empty chain, kill switch) the event passes straight
 *       through to daemon auto-start.
 *   <li><b>Manual re-run</b> — {@link #runChainAsync}/{@link #runSingleAsync} from the workspace
 *       surface; also the recovery path after a failed provision-time chain and the path for
 *       configs that gained commands after provisioning.
 * </ul>
 *
 * <p>Sequencing vs daemon auto-start is structural: this runner is the only firer of {@link
 * WorkspaceContainerEventPublisher#fireReadyForDaemons} — on pass-through immediately, after a
 * successful chain, and after a successful manual full-chain run (recovery; the coupler tolerates
 * already-running daemons). A <b>failed chain never fires it</b>: the remaining commands are
 * aborted and daemon auto-start is skipped — a dev server on an unbootstrapped checkout would just
 * burn its restart budget crash-looping (and qits' own dogfood build guard would fail the moment
 * something listens on the dev port). The workspace stays usable; the failure surfaces on the
 * workspace surface (BOOTSTRAP hints over SSE) and in the failed command's log.
 *
 * <p>Per command: the optional {@code check} script runs first ({@code bash -lc} via {@code docker
 * exec}); non-zero means "not needed" — recorded SKIPPED, no command row, chain continues. The
 * execute script then runs via {@link CommandService#launchScriptAndAwait} with a generous await
 * timeout ({@code qits.bootstrap.await-timeout-ms}); on timeout the process is <b>terminated</b>
 * and the chain fails — unlike {@code launchAndAwait}'s leave-running policy, a straggling install
 * whose successors were aborted is pure waste and would fight the manual re-run.
 *
 * <p>Reentrancy: a manual run's {@code ensureContainer} may itself fresh-provision and fire {@link
 * WorkspaceContainerStarted} — the per-workspace in-flight guard makes the event-triggered chain
 * yield to the already-running manual one (which fires ready itself on success). The guard also
 * backs the surface's "chain running" indicator and the manual-trigger conflict error.
 */
@ApplicationScoped
public class WorkspaceBootstrapRunner {

  private static final Logger LOG = Logger.getLogger(WorkspaceBootstrapRunner.class);

  @Inject BootstrapCommandService bootstrapCommandService;

  @Inject BootstrapRunService bootstrapRunService;

  @Inject CommandService commandService;

  @Inject CommandRegistry commandRegistry;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject TechnicalProcessRegistry processRegistry;

  /** Kill switch for the provision-time trigger (manual runs stay available). */
  @ConfigProperty(name = "qits.bootstrap.autorun-enabled", defaultValue = "true")
  boolean autorunEnabled;

  /**
   * How long one execute script may run before the chain gives up on it. Generous by design — a
   * cold {@code mvn install} takes long — and a timeout terminates the process (see class doc).
   */
  @ConfigProperty(name = "qits.bootstrap.await-timeout-ms", defaultValue = "3600000")
  long awaitTimeoutMillis;

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
    List<BootstrapCommandDto> chain =
        evt.freshProvision() && autorunEnabled
            ? bootstrapCommandService.resolveAll(evt.repoId())
            : List.of();
    if (chain.isEmpty()) {
      // Plain restart, empty chain, or kill switch: nothing between the container and its daemons.
      containerEvents.fireReadyForDaemons(
          evt.repoId(), evt.workspaceId(), evt.technicalProcessId());
      return;
    }
    if (inFlight.putIfAbsent(key(evt.repoId(), evt.workspaceId()), Boolean.TRUE) != null) {
      // A manual run provisioned this container and owns the chain; it fires ready on success. This
      // start's process can't observe that run (its ready event carries no process id), so close
      // its stream cleanly rather than hang it. Its verdict covers only the provision it watched —
      // the delegated chain's real outcome and the daemon phase are on the workspace Bootstrap tab.
      // (Residual limitation: a green Start here does not vouch for the delegated chain — see
      // docs/issues/2026-07-19_streamed-start-verdict-delegated-bootstrap.md.)
      if (process != null) {
        process.appendLine(
            "bootstrap",
            "A manually triggered bootstrap run is already in flight and owns this chain — its"
                + " outcome and the daemon phase are tracked on the workspace Bootstrap tab.");
        process.settleSegment("bootstrap", true);
        process.expectDaemons(List.of());
      }
      return;
    }
    try {
      boolean ok = runChain(evt.repoId(), evt.workspaceId(), chain, process);
      if (ok) {
        containerEvents.fireReadyForDaemons(
            evt.repoId(), evt.workspaceId(), evt.technicalProcessId());
      } else if (process != null) {
        // Failed chain: no daemon phase. Declaring the empty set ends the process now — its
        // verdict is already `failed` via the failed bootstrap segment.
        process.expectDaemons(List.of());
      }
    } catch (RuntimeException e) {
      // runChain guards each command, but a failure *between* commands (e.g. the opening BOOTSTRAP
      // hint fire) would otherwise skip both fireReadyForDaemons and expectDaemons and leave the
      // start's stream hanging until the idle backstop. End it as failed instead.
      LOG.errorf(
          e,
          "Bootstrap chain failed unexpectedly for workspace %s/%s",
          evt.workspaceId(),
          evt.repoId());
      if (process != null) {
        process.appendLine("bootstrap", "Bootstrap failed unexpectedly: " + e.getMessage());
        process.settleSegment("bootstrap", false);
        process.expectDaemons(List.of());
      }
    } finally {
      inFlight.remove(key(evt.repoId(), evt.workspaceId()));
      // Mirror submitManual's finally: a final BOOTSTRAP hint after the guard is released so the
      // surface's "chain running" indicator clears even when the chain aborted (runChain's failure
      // paths record no outcome, so no other hint follows) — and the success path is no longer racy
      // (its last recordOutcome hint fires while the guard is still held).
      changePublisher.fire(evt.repoId(), evt.workspaceId(), WorkspaceChangeHint.Topic.BOOTSTRAP);
    }
  }

  /**
   * Re-run the whole chain on demand (async; progress arrives over BOOTSTRAP hints). On success,
   * daemon auto-start proceeds — the recovery path after a failed provision-time run.
   */
  public void runChainAsync(String repoId, String workspaceId) {
    submitManual(
        repoId,
        workspaceId,
        () -> {
          List<BootstrapCommandDto> chain = bootstrapCommandService.resolveAll(repoId);
          workspaceService.ensureContainer(repoId, workspaceId);
          if (runChain(repoId, workspaceId, chain, null)) {
            containerEvents.fireReadyForDaemons(repoId, workspaceId, null);
          }
        });
  }

  /** Re-run one command on demand (async). Does not touch daemon auto-start. */
  public void runSingleAsync(String repoId, String workspaceId, String commandId) {
    BootstrapCommandDto command = bootstrapCommandService.resolve(repoId, commandId);
    submitManual(
        repoId,
        workspaceId,
        () -> {
          workspaceService.ensureContainer(repoId, workspaceId);
          runCommand(repoId, workspaceId, command, null);
        });
  }

  /** Enter the in-flight guard and hand the work to the manual-run executor. */
  private void submitManual(String repoId, String workspaceId, Runnable work) {
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

  /**
   * The chain proper: every command in order, aborting on the first failure. Returns whether the
   * whole chain passed (skips count as passed). Caller holds the in-flight guard.
   */
  private boolean runChain(
      String repoId,
      String workspaceId,
      List<BootstrapCommandDto> chain,
      TechnicalProcess process) {
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.BOOTSTRAP);
    for (int i = 0; i < chain.size(); i++) {
      BootstrapCommandDto command = chain.get(i);
      boolean ok;
      try {
        ok = runCommand(repoId, workspaceId, command, process);
      } catch (RuntimeException e) {
        LOG.warnf(
            e,
            "Bootstrap command '%s' failed to launch in workspace %s/%s",
            command.name(),
            workspaceId,
            repoId);
        // Record FAILED for the throwing command — parity with runCommand's non-zero-exit path.
        // Without this the surface keeps showing this command's previous (green) run after a chain
        // that actually aborted, and the tab's failed-warning indicator never lights.
        bootstrapRunService.recordOutcome(
            repoId, workspaceId, command.id(), command.name(), BootstrapOutcome.FAILED, null, null);
        if (process != null) {
          String segment = bootstrapSegment(command.name());
          process.appendLine(segment, "Launch failed: " + e.getMessage());
          process.settleSegment(segment, false);
        }
        ok = false;
      }
      if (!ok) {
        // Abort the rest loudly: their segments show up as failed instead of silently missing.
        for (BootstrapCommandDto remaining : chain.subList(i + 1, chain.size())) {
          if (process != null) {
            String segment = bootstrapSegment(remaining.name());
            process.appendLine(segment, "Skipped — an earlier bootstrap command failed.");
            process.settleSegment(segment, false);
          }
        }
        return false;
      }
    }
    return true;
  }

  /**
   * One command: consult {@code check} (non-zero → SKIPPED, no command row), then run the execute
   * script to completion and record the outcome. Returns whether the chain may continue.
   */
  private boolean runCommand(
      String repoId, String workspaceId, BootstrapCommandDto command, TechnicalProcess process) {
    String segment = bootstrapSegment(command.name());
    if (process != null) {
      process.openSegment(segment);
    }

    if (command.checkScript() != null) {
      ContainerRuntime.ExecResult check =
          containers.exec(
              containers.containerName(workspaceId, repoId),
              "/workspace",
              command.environment(),
              "bash",
              "-lc",
              command.checkScript());
      if (check.exitCode() != 0) {
        bootstrapRunService.recordOutcome(
            repoId,
            workspaceId,
            command.id(),
            command.name(),
            BootstrapOutcome.SKIPPED,
            null,
            null);
        if (process != null) {
          process.appendLine(
              segment, "Skipped — check script exited " + check.exitCode() + " (not needed).");
          process.settleSegment(segment, true);
        }
        return true;
      }
    }

    RunOutcome outcome =
        commandService.launchScriptAndAwait(
            repoId,
            workspaceId,
            command.name(),
            command.executeScript(),
            command.environment(),
            awaitTimeoutMillis,
            process != null
                ? new CommandOutputSink[] {new SegmentLineSink(process, segment)}
                : new CommandOutputSink[0]);
    boolean timedOut = outcome.exitCode() < 0;
    if (timedOut) {
      // A straggling execute whose successors are aborted anyway would only fight the re-run.
      commandRegistry.terminate(outcome.commandId());
      if (process != null) {
        process.appendLine(
            segment, "Timed out after " + awaitTimeoutMillis + " ms — terminated (chain aborted).");
      }
    }
    boolean ok = outcome.exitCode() == 0;
    bootstrapRunService.recordOutcome(
        repoId,
        workspaceId,
        command.id(),
        command.name(),
        ok ? BootstrapOutcome.SUCCEEDED : BootstrapOutcome.FAILED,
        outcome.commandId(),
        timedOut ? null : outcome.exitCode());
    if (process != null) {
      process.settleSegment(segment, ok);
    }
    return ok;
  }

  /** The technical-process segment for one bootstrap command: {@code bootstrap:<base name>}. */
  public static String bootstrapSegment(String commandName) {
    return "bootstrap:" + QitsConfig.baseName(commandName);
  }

  private static String key(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
