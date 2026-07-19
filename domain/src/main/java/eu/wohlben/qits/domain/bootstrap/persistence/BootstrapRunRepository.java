package eu.wohlben.qits.domain.bootstrap.persistence;

import eu.wohlben.qits.domain.bootstrap.entity.BootstrapRun;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class BootstrapRunRepository implements PanacheRepositoryBase<BootstrapRun, String> {

  /** Last-run rows of one workspace row (the active workspace resolves to one row). */
  public List<BootstrapRun> findByWorkspaceRow(Long workspaceRowId) {
    return list("workspace.id", workspaceRowId);
  }

  public Optional<BootstrapRun> findByWorkspaceRowAndBootstrapCommand(
      Long workspaceRowId, String bootstrapCommandId) {
    return find("workspace.id = ?1 and bootstrapCommandId = ?2", workspaceRowId, bootstrapCommandId)
        .firstResultOptional();
  }
}
