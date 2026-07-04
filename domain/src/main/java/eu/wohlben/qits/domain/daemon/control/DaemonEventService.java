package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The hub daemon events flow through: it retains a bounded recent-events ring for the UI feed and
 * forwards everything above INFO to the agent sink. In-memory next to the supervisor — the durable
 * history is the daemon commands' logs.
 */
@ApplicationScoped
public class DaemonEventService {

  private static final Logger LOG = Logger.getLogger(DaemonEventService.class);

  private static final int MAX_RETAINED = 500;

  private final Deque<DaemonEventDto> recent = new ArrayDeque<>();

  @Inject DaemonAgentNotifier agentNotifier;

  public void publish(DaemonEventDto event) {
    synchronized (recent) {
      recent.addLast(event);
      while (recent.size() > MAX_RETAINED) {
        recent.removeFirst();
      }
    }
    if (event.severity() != null && event.severity() != DaemonEventSeverity.INFO) {
      try {
        agentNotifier.deliver(event);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Agent notification failed for daemon event: %s", event.summary());
      }
    }
  }

  /** Recent events for one worktree, newest first. */
  public List<DaemonEventDto> recent(String repoId, String worktreeId) {
    synchronized (recent) {
      return recent.stream()
          .filter(e -> e.repoId().equals(repoId) && e.worktreeId().equals(worktreeId))
          .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
          .toList();
    }
  }
}
