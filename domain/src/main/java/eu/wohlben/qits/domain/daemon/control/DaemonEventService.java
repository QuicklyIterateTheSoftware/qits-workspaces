package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.mapper.DaemonEventMapper;
import eu.wohlben.qits.domain.daemon.persistence.DaemonEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The hub daemon events flow through: every published event is persisted as a {@code daemon_event}
 * row (synchronously — events are throttled and low-volume, and a durable row is the point) and
 * everything above INFO is forwarded to the agent sink. The write replaces the old in-memory ring:
 * with the row committed before publish returns, the DB is the feed. A persistence failure is
 * logged and does not block the agent notification.
 */
@ApplicationScoped
public class DaemonEventService {

  private static final Logger LOG = Logger.getLogger(DaemonEventService.class);

  @Inject DaemonEventPersister persister;

  @Inject DaemonEventRepository daemonEventRepository;

  @Inject DaemonEventMapper daemonEventMapper;

  @Inject DaemonAgentNotifier agentNotifier;

  public void publish(DaemonEventDto event) {
    try {
      persister.persist(event);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Failed to persist daemon event: %s", event.summary());
    }
    if (event.severity() != null && event.severity() != DaemonEventSeverity.INFO) {
      try {
        agentNotifier.deliver(event);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Agent notification failed for daemon event: %s", event.summary());
      }
    }
  }

  /** Durable events, newest first; null criteria mean "don't filter" (everything-visible). */
  @Transactional
  public List<DaemonEventDto> query(
      String repoId,
      String worktreeId,
      DaemonEventSeverity severity,
      Instant since,
      String source,
      int page,
      int pageSize) {
    return daemonEventRepository
        .find(repoId, worktreeId, severity, since, source, page, pageSize)
        .stream()
        .map(daemonEventMapper::toDto)
        .toList();
  }
}
