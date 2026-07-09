package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.dto.RepositoryDaemonDto;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.repository.control.WorkspaceContainerStarted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Couples the container lifecycle to the daemon lifecycle from the daemon side: when a workspace
 * container comes up (a cold&#8594;RUNNING transition in {@code WorkspaceService.ensureContainer},
 * signalled by {@link WorkspaceContainerStarted}), start the repository's auto-start daemons so a
 * freshly provisioned or restarted workspace runs its dev server unattended, instead of coming up
 * daemon-less until someone opens the Daemons tab.
 *
 * <p>Runs on the CDI async observer thread, so it never adds to {@code ensureContainer}'s latency.
 * Reentrancy is safe by construction: {@code supervisor.start} &#8594; {@code beginDaemonRun}
 * &#8594; {@code ensureContainer} hits the already-RUNNING short-circuit, which does not fire the
 * event — the cycle terminates after one cheap no-op hop.
 */
@ApplicationScoped
public class DaemonAutoStarter {

  private static final Logger LOG = Logger.getLogger(DaemonAutoStarter.class);

  @Inject RepositoryDaemonService repositoryDaemonService;

  @Inject DaemonSupervisor supervisor;

  /** Kill switch for the whole coupling (belt-and-suspenders over the per-daemon opt-out). */
  @ConfigProperty(name = "qits.daemons.autostart-enabled", defaultValue = "true")
  boolean autostartEnabled;

  void onContainerStarted(@ObservesAsync WorkspaceContainerStarted evt) {
    if (!autostartEnabled) {
      return;
    }
    List<RepositoryDaemonDto> definitions = repositoryDaemonService.resolveAll(evt.repoId());
    for (RepositoryDaemonDto daemon : definitions) {
      if (!daemon.autoStart()) {
        continue;
      }
      try {
        supervisor.start(evt.repoId(), evt.workspaceId(), daemon.id());
      } catch (BadRequestException alreadyRunning) {
        // An instance is already live (a concurrent manual start, or a re-adopted session). The
        // supervisor enforces one instance per (workspace, daemon); tolerating this is exactly the
        // idempotency auto-start wants.
        LOG.debugf(
            "Auto-start skipped daemon '%s' in workspace %s: %s",
            daemon.name(), evt.workspaceId(), alreadyRunning.getMessage());
      } catch (RuntimeException e) {
        // One daemon failing to launch must not block the others; the failure already surfaces as a
        // STARTING -> CRASHED transition (daemon events + SSE) via the supervisor, never here.
        LOG.warnf(
            e,
            "Auto-start failed for daemon '%s' in workspace %s",
            daemon.name(),
            evt.workspaceId());
      }
    }
  }
}
