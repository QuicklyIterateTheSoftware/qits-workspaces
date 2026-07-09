package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner {@link WorkspaceService} calls to announce a workspace container came up. Wraps CDI
 * {@link Event#fireAsync} so firing never blocks or fails the transition that just committed
 * RUNNING — {@code ensureContainer} (and every lazy caller on a request thread) keeps its latency,
 * and the auto-start work happens on the async observer thread. {@code domain} stays
 * web-framework-free; the observer ({@code DaemonAutoStarter}) subscribes with
 * {@code @ObservesAsync}.
 */
@ApplicationScoped
public class WorkspaceContainerEventPublisher {

  @Inject Event<WorkspaceContainerStarted> started;

  public void fireStarted(String repoId, String workspaceId) {
    started.fireAsync(new WorkspaceContainerStarted(repoId, workspaceId));
  }
}
