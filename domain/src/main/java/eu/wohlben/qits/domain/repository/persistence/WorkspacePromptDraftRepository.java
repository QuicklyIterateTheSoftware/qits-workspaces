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
    // The MERGE lists only content/serialized_prompt/updated_at, so on update H2 leaves the other
    // columns (prompt_version, last_run_*) untouched; on insert they take their DDL defaults
    // (prompt_version 0, last_run_* null).
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
    // Bump the version in the same transaction — a PUT means the composition changed (the frontend
    // only saves when dirty), so every upsert names a new draft state. Kept a separate statement to
    // avoid a self-referencing subquery inside the MERGE.
    getEntityManager()
        .createNativeQuery(
            "update workspace_prompt_draft set prompt_version = prompt_version + 1"
                + " where workspace_id_fk = ?1")
        .setParameter(1, workspaceId)
        .executeUpdate();
  }

  /**
   * Records that the draft's current version was handed to an agent run: stamps {@code
   * last_run_at}, copies the live {@code prompt_version} into {@code last_run_prompt_version}, and
   * records the launched command id. Idempotent no-op when the workspace has no draft row.
   */
  public void recordRun(Long workspaceId, String commandId) {
    getEntityManager()
        .createNativeQuery(
            "update workspace_prompt_draft set last_run_at = current_timestamp,"
                + " last_run_prompt_version = prompt_version, last_run_command_id = ?2"
                + " where workspace_id_fk = ?1")
        .setParameter(1, workspaceId)
        .setParameter(2, commandId)
        .executeUpdate();
  }

  /** Removes a workspace's draft if present — idempotent (no-op when absent). */
  public void deleteByWorkspaceId(Long workspaceId) {
    delete("workspaceId", workspaceId);
  }
}
