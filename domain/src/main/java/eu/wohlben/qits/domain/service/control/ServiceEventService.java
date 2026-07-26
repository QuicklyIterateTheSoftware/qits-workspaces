package eu.wohlben.qits.domain.service.control;

import eu.wohlben.qits.domain.service.dto.ServiceEventDto;
import eu.wohlben.qits.domain.service.entity.ServiceEventSeverity;
import eu.wohlben.qits.domain.service.mapper.ServiceEventMapper;
import eu.wohlben.qits.domain.service.persistence.ServiceEventRepository;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The hub service events flow through: every published event is persisted as a {@code
 * service_event} row (synchronously — events are throttled and low-volume, and a durable row is the
 * point) and everything above INFO is forwarded to the agent sink. The write replaces the old
 * in-memory ring: with the row committed before publish returns, the DB is the feed. A persistence
 * failure is logged and does not block the agent notification.
 */
@ApplicationScoped
public class ServiceEventService {

  private static final Logger LOG = Logger.getLogger(ServiceEventService.class);

  @Inject ServiceEventPersister persister;

  @Inject ServiceEventRepository serviceEventRepository;

  @Inject ServiceEventMapper serviceEventMapper;

  @Inject ServiceAgentNotifier agentNotifier;

  @Inject WorkspaceChangePublisher changePublisher;

  public void publish(ServiceEventDto event) {
    try {
      persister.persist(event);
    } catch (RuntimeException e) {
      LOG.warnf(e, "Failed to persist service event: %s", event.summary());
    }
    changePublisher.fire(
        event.repoId(), event.workspaceId(), WorkspaceChangeHint.Topic.SERVICE_EVENTS);
    if (event.severity() != null && event.severity() != ServiceEventSeverity.INFO) {
      try {
        agentNotifier.deliver(event);
      } catch (RuntimeException e) {
        LOG.warnf(e, "Agent notification failed for service event: %s", event.summary());
      }
    }
  }

  /** Durable events, newest first; null criteria mean "don't filter" (everything-visible). */
  @Transactional
  public List<ServiceEventDto> query(
      String repoId,
      String workspaceId,
      ServiceEventSeverity severity,
      Instant since,
      String source,
      int page,
      int pageSize) {
    return serviceEventRepository
        .find(repoId, workspaceId, severity, since, source, page, pageSize)
        .stream()
        .map(serviceEventMapper::toDto)
        .toList();
  }
}
