package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liners {@link WorkspaceService} calls to announce a workspace container's lifecycle
 * edges, observed in the daemon area ({@code DaemonLifecycleCoupler}). {@code domain} stays
 * web-framework-free; the coupling is CDI events, inverting the forbidden direct dependency.
 *
 * <p>The two directions fire deliberately differently:
 *
 * <ul>
 *   <li><b>started</b> — {@link Event#fireAsync}, so firing never blocks or fails the transition
 *       that just committed RUNNING; {@code ensureContainer} (and every lazy caller on a request
 *       thread) keeps its latency and the auto-start work happens on the async observer thread.
 *   <li><b>stopping</b> — synchronous {@link Event#fire}, so the settle completes <em>before</em>
 *       the caller removes the container: the observer must see the container (and its sessions)
 *       alive.
 * </ul>
 */
@ApplicationScoped
public class WorkspaceContainerEventPublisher {

  @Inject Event<WorkspaceContainerStarted> started;

  @Inject Event<WorkspaceContainerStopping> stopping;

  public void fireStarted(String repoId, String workspaceId) {
    fireStarted(repoId, workspaceId, null);
  }

  /** {@code technicalProcessId} correlates the async daemon phase with the start's log stream. */
  public void fireStarted(String repoId, String workspaceId, String technicalProcessId) {
    started.fireAsync(new WorkspaceContainerStarted(repoId, workspaceId, technicalProcessId));
  }

  /** Synchronous by design — settling must finish before the caller's {@code containers.rm}. */
  public void fireStopping(String repoId, String workspaceId, boolean graceful) {
    stopping.fire(new WorkspaceContainerStopping(repoId, workspaceId, graceful));
  }
}
