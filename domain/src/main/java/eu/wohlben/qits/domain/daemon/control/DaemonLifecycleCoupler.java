package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.dto.RepositoryDaemonDto;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.process.control.TechnicalProcess;
import eu.wohlben.qits.domain.process.control.TechnicalProcessRegistry;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerStarted;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerStopping;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Couples the container lifecycle to the daemon lifecycle from the daemon side, both directions:
 *
 * <ul>
 *   <li><b>start</b> — when a workspace container comes up (a cold&#8594;RUNNING transition in
 *       {@code WorkspaceService.ensureContainer}, signalled by {@link WorkspaceContainerStarted}),
 *       start the repository's auto-start daemons so a freshly provisioned or restarted workspace
 *       runs its dev server unattended instead of coming up daemon-less. Observed with
 *       {@code @ObservesAsync} — runs on the async observer thread and never adds to {@code
 *       ensureContainer}'s latency. Reentrancy is safe by construction: {@code supervisor.start}
 *       &#8594; {@code beginDaemonRun} &#8594; {@code ensureContainer} hits the already-RUNNING
 *       short-circuit, which does not fire the event — the cycle terminates after one cheap no-op
 *       hop.
 *   <li><b>stop</b> — when a workspace container is about to be deliberately removed ({@link
 *       WorkspaceContainerStopping}), settle its live daemons so their imminent disappearance reads
 *       as a clean STOPPED instead of a crash the restart policy would resurrect. Observed
 *       <em>synchronously</em> ({@code @Observes}) so the settle finishes before the caller's
 *       {@code containers.rm}, while the container and its sessions still exist.
 * </ul>
 */
@ApplicationScoped
public class DaemonLifecycleCoupler {

  private static final Logger LOG = Logger.getLogger(DaemonLifecycleCoupler.class);

  @Inject RepositoryDaemonService repositoryDaemonService;

  @Inject DaemonSupervisor supervisor;

  @Inject TechnicalProcessRegistry processRegistry;

  /** Kill switch for the start coupling (belt-and-suspenders over the per-daemon opt-out). */
  @ConfigProperty(name = "qits.daemons.autostart-enabled", defaultValue = "true")
  boolean autostartEnabled;

  /** Kill switch for the stop coupling. */
  @ConfigProperty(name = "qits.daemons.autostop-enabled", defaultValue = "true")
  boolean autostopEnabled;

  void onContainerStarted(@ObservesAsync WorkspaceContainerStarted evt) {
    // The technical process (if this start is stream-tracked) must always learn the auto-start
    // set — even when it is empty or the kill switch is off — because its terminal `done` waits
    // for exactly that declaration.
    TechnicalProcess process = processRegistry.find(evt.technicalProcessId()).orElse(null);
    List<RepositoryDaemonDto> autoStarts =
        !autostartEnabled
            ? List.of()
            : repositoryDaemonService.resolveAll(evt.repoId()).stream()
                .filter(RepositoryDaemonDto::autoStart)
                .toList();
    if (process != null) {
      process.expectDaemons(autoStarts.stream().map(RepositoryDaemonDto::name).toList());
    }
    for (RepositoryDaemonDto daemon : autoStarts) {
      try {
        supervisor.start(evt.repoId(), evt.workspaceId(), daemon.id(), process);
      } catch (BadRequestException alreadyRunning) {
        // An instance is already live (a concurrent manual start, or a re-adopted session). The
        // supervisor enforces one instance per (workspace, daemon); tolerating this is exactly the
        // idempotency auto-start wants — and it settles the daemon's segment so the process's
        // `done` doesn't wait on a daemon that was up all along.
        if (process != null) {
          String segment = TechnicalProcess.daemonSegment(daemon.name());
          process.appendLine(segment, "Already running — nothing to start.");
          process.settleSegment(segment, true);
        }
        LOG.debugf(
            "Auto-start skipped daemon '%s' in workspace %s: %s",
            daemon.name(), evt.workspaceId(), alreadyRunning.getMessage());
      } catch (RuntimeException e) {
        // One daemon failing to launch must not block the others; the failure already surfaces as a
        // STARTING -> CRASHED transition (daemon events + SSE) via the supervisor. The segment is
        // settled here too for launch failures that never reach the supervisor's state machine.
        if (process != null) {
          String segment = TechnicalProcess.daemonSegment(daemon.name());
          process.appendLine(segment, "Launch failed: " + e.getMessage());
          process.settleSegment(segment, false);
        }
        LOG.warnf(
            e,
            "Auto-start failed for daemon '%s' in workspace %s",
            daemon.name(),
            evt.workspaceId());
      }
    }
  }

  void onContainerStopping(@Observes WorkspaceContainerStopping evt) {
    if (!autostopEnabled) {
      return;
    }
    supervisor.settleForWorkspace(evt.repoId(), evt.workspaceId(), evt.graceful());
  }
}
