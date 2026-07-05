package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspaceStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class WorkspaceRepository implements PanacheRepository<Workspace> {

  // --- ACTIVE-only (operational) -----------------------------------------------------------------
  // Workspaces are soft-deleted, so resolved rows linger. Everything that operates on a live
  // workspace
  // (terminal, command launch, merge, discard, branch resolution) must use these ACTIVE finders.

  public Optional<Workspace> findActiveByRepositoryAndWorkspaceId(
      String repositoryId, String workspaceId) {
    return find(
            "repository.id = ?1 and workspaceId = ?2 and status = ?3",
            repositoryId,
            workspaceId,
            WorkspaceStatus.ACTIVE)
        .firstResultOptional();
  }

  public List<Workspace> findActiveByRepositoryId(String repositoryId) {
    return list("repository.id = ?1 and status = ?2", repositoryId, WorkspaceStatus.ACTIVE);
  }

  public boolean existsActiveByRepositoryAndWorkspaceId(String repositoryId, String workspaceId) {
    return count(
            "repository.id = ?1 and workspaceId = ?2 and status = ?3",
            repositoryId,
            workspaceId,
            WorkspaceStatus.ACTIVE)
        > 0;
  }

  // --- Any-status (history / discovery) ----------------------------------------------------------

  /** Every workspace (active + resolved) for a repository, newest first — for the history view. */
  public List<Workspace> findByRepositoryId(String repositoryId) {
    return list("repository.id = ?1 order by id desc", repositoryId);
  }
}
