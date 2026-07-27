package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner producers call to announce a workspace change. Wraps CDI {@link Event#fireAsync} so
 * firing never blocks or fails the mutating transaction — some producers ({@code
 * ServiceSupervisor.transition}) run under a monitor, so the emit must return immediately and hand
 * off to the async observer thread. {@code domain} stays web-framework-free: the SSE plumbing that
 * consumes these hints lives in {@code service} and subscribes with {@code @ObservesAsync}.
 */
@ApplicationScoped
public class WorkspaceChangePublisher {

  @Inject Event<WorkspaceChangeHint> event;

  /**
   * Announce a change. { workspaceRowId} names the workspace scope; pass { null} for the
   * repository scope, and null for both to reach the global channel.
   */
  public void fire(String repoId, Long workspaceRowId, Topic topic) {
    event.fireAsync(new WorkspaceChangeHint(repoId, workspaceRowId, topic));
  }
}
