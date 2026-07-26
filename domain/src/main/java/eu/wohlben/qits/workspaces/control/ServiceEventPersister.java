package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import eu.wohlben.qits.workspaces.entity.ServiceEvent;
import eu.wohlben.qits.workspaces.persistence.ServiceEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Writes one published event as a {@code service_event} row. Isolated in its own bean so {@code
 * ServiceEventService} invokes it through the CDI proxy (publish runs on supervisor/scheduler
 * threads with no request context — same pattern as {@code CommandLogBatchPersister}).
 */
@ApplicationScoped
public class ServiceEventPersister {

  private static final int MAX_SUMMARY_CHARS = 2000;

  @Inject ServiceEventRepository serviceEventRepository;

  @Transactional
  @ActivateRequestContext
  public void persist(ServiceEventDto event) {
    ServiceEvent entity = new ServiceEvent();
    entity.id = UUID.randomUUID().toString();
    entity.repoId = event.repoId();
    entity.workspaceId = event.workspaceId();
    entity.serviceId = event.serviceId();
    entity.serviceName = event.serviceName();
    entity.kind = event.kind();
    entity.severity = event.severity();
    entity.status = event.status();
    entity.summary =
        event.summary() != null && event.summary().length() > MAX_SUMMARY_CHARS
            ? event.summary().substring(0, MAX_SUMMARY_CHARS)
            : event.summary();
    entity.logExcerpt = event.logExcerpt();
    entity.commandId = event.commandId();
    entity.source = event.source();
    entity.anchorFrom = event.anchorFrom();
    entity.anchorTo = event.anchorTo();
    entity.sourceEpoch = event.sourceEpoch();
    entity.timestamp = event.timestamp();
    serviceEventRepository.persist(entity);
  }
}
