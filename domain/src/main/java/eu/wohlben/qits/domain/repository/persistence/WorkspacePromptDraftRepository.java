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

  /**
   * Atomically inserts or updates a workspace's draft in one statement (H2 {@code MERGE} keyed on
   * the shared PK). Because the draft's primary key <em>is</em> the workspace id, a non-atomic
   * read-then-insert would let two concurrent first-saves collide on that PK; the DB-native upsert
   * serializes them under the row lock (last write wins) instead of 500ing the loser. {@code
   * updated_at} is assigned by the DB clock ({@code CURRENT_TIMESTAMP}) so its value matches what a
   * subsequent read returns — the {@code @UpdateTimestamp} on the entity governs only the (now
   * unused) Hibernate persist path.
   */
  public void upsert(Long workspaceId, String content, String serializedPrompt) {
    getEntityManager()
        .createNativeQuery(
            "merge into workspace_prompt_draft"
                + " (workspace_id_fk, content, serialized_prompt, updated_at)"
                + " key (workspace_id_fk)"
                + " values (?1, ?2, ?3, current_timestamp)")
        .setParameter(1, workspaceId)
        .setParameter(2, content)
        .setParameter(3, serializedPrompt)
        .executeUpdate();
  }

  /** Removes a workspace's draft if present — idempotent (no-op when absent). */
  public void deleteByWorkspaceId(Long workspaceId) {
    delete("workspaceId", workspaceId);
  }
}
