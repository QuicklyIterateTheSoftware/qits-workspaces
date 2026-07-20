package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.repository.entity.WorkspacePromptAttachment;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class WorkspacePromptAttachmentRepository
    implements PanacheRepository<WorkspacePromptAttachment> {

  /** A workspace's attachments, oldest first (the order they were attached). */
  public List<WorkspacePromptAttachment> listByWorkspaceId(Long workspaceId) {
    return list("workspaceId = ?1 order by createdAt", workspaceId);
  }

  /** One attachment, only if it belongs to the given workspace (guards cross-workspace access). */
  public Optional<WorkspacePromptAttachment> findByWorkspaceIdAndId(Long workspaceId, String id) {
    return find("workspaceId = ?1 and id = ?2", workspaceId, id).firstResultOptional();
  }

  /** Removes one attachment scoped to its workspace — returns true if a row was deleted. */
  public boolean deleteByWorkspaceIdAndId(Long workspaceId, String id) {
    return delete("workspaceId = ?1 and id = ?2", workspaceId, id) > 0;
  }

  /** Removes all of a workspace's attachments — idempotent (no-op when none exist). */
  public void deleteByWorkspaceId(Long workspaceId) {
    delete("workspaceId", workspaceId);
  }
}
