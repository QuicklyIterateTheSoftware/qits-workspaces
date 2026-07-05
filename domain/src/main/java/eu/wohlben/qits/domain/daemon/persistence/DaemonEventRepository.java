package eu.wohlben.qits.domain.daemon.persistence;

import eu.wohlben.qits.domain.daemon.entity.DaemonEvent;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class DaemonEventRepository implements PanacheRepositoryBase<DaemonEvent, String> {

  /**
   * Durable events, newest first, filtered by whatever criteria are present (everything-visible: no
   * filter means all of it) and paginated.
   */
  public List<DaemonEvent> find(
      String repoId,
      String workspaceId,
      DaemonEventSeverity severity,
      Instant since,
      String source,
      int page,
      int pageSize) {
    List<String> conditions = new ArrayList<>();
    Parameters parameters = new Parameters();
    if (repoId != null && !repoId.isBlank()) {
      conditions.add("repoId = :repoId");
      parameters = parameters.and("repoId", repoId);
    }
    if (workspaceId != null && !workspaceId.isBlank()) {
      conditions.add("workspaceId = :workspaceId");
      parameters = parameters.and("workspaceId", workspaceId);
    }
    if (severity != null) {
      conditions.add("severity = :severity");
      parameters = parameters.and("severity", severity);
    }
    if (since != null) {
      conditions.add("timestamp >= :since");
      parameters = parameters.and("since", since);
    }
    if (source != null && !source.isBlank()) {
      conditions.add("source = :source");
      parameters = parameters.and("source", source);
    }
    String query = conditions.isEmpty() ? "" : String.join(" and ", conditions);
    Sort sort = Sort.descending("timestamp").and("id", Sort.Direction.Descending);
    return find(query, sort, parameters).page(page, pageSize).list();
  }
}
