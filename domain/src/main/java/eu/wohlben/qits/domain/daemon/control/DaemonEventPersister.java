package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEvent;
import eu.wohlben.qits.domain.daemon.persistence.DaemonEventRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.UUID;

/**
 * Writes one published event as a {@code daemon_event} row. Isolated in its own bean so {@code
 * DaemonEventService} invokes it through the CDI proxy (publish runs on supervisor/scheduler
 * threads with no request context — same pattern as {@code CommandLogBatchPersister}).
 */
@ApplicationScoped
public class DaemonEventPersister {

  private static final int MAX_SUMMARY_CHARS = 2000;

  @Inject DaemonEventRepository daemonEventRepository;

  @Transactional
  @ActivateRequestContext
  public void persist(DaemonEventDto event) {
    DaemonEvent entity = new DaemonEvent();
    entity.id = UUID.randomUUID().toString();
    entity.repoId = event.repoId();
    entity.worktreeId = event.worktreeId();
    entity.daemonId = event.daemonId();
    entity.daemonName = event.daemonName();
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
    daemonEventRepository.persist(entity);
  }
}
