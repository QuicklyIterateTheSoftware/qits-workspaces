package eu.wohlben.qits.domain.repository.persistence;

import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class WorkspacePromptDraftRepository implements PanacheRepository<WorkspacePromptDraft> {

  /** The draft for a workspace, keyed by the workspace's {@code id} (the draft's shared PK/FK). */
  public Optional<WorkspacePromptDraft> findByWorkspaceId(Long workspaceId) {
    return findByIdOptional(workspaceId);
  }

  /** Removes a workspace's draft if present — idempotent (no-op when absent). */
  public void deleteByWorkspaceId(Long workspaceId) {
    delete("workspaceId", workspaceId);
  }
}
