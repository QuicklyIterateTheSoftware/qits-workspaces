package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.WorkspaceEvent;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class WorkspaceEventRepository implements PanacheRepository<WorkspaceEvent> {

  /** A workspace's events in chronological order. */
  public List<WorkspaceEvent> findByWorkspaceOrderByAt(Long workspaceId) {
    return list("workspace.id = ?1 order by at, id", workspaceId);
  }
}
