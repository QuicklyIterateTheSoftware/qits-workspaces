package eu.wohlben.qits.domain.workspace.control;

import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner producers call to announce a workspace change. Wraps CDI {@link Event#fireAsync} so
 * firing never blocks or fails the mutating transaction — some producers ({@code
 * DaemonSupervisor.transition}) run under a monitor, so the emit must return immediately and hand
 * off to the async observer thread. {@code domain} stays web-framework-free: the SSE plumbing that
 * consumes these hints lives in {@code service} and subscribes with {@code @ObservesAsync}.
 */
@ApplicationScoped
public class WorkspaceChangePublisher {

  @Inject Event<WorkspaceChangeHint> event;

  public void fire(String repoId, String workspaceId, Topic topic) {
    event.fireAsync(new WorkspaceChangeHint(repoId, workspaceId, topic));
  }
}
