package eu.wohlben.qits.domain.repository.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A workspace's persisted prompt-composition draft — one row per workspace, rehydrated on load so a
 * half-built prompt survives a refresh (and follows the operator across devices). The draft is
 * <em>semi-opaque</em>: {@link #content} is a JSON document the composing UI owns and the server
 * never interprets, while {@link #serializedPrompt} is the launch-ready markdown the client already
 * produced — the server-readable part a later step's {@code taskPrompt} MCP tool serves to the
 * agent.
 *
 * <p>The primary key <em>is</em> the owning {@link Workspace}'s {@code id} ({@code
 * workspace_id_fk}, a shared PK/FK): the draft is strictly 1:1 with a workspace, so no separate
 * generated id is needed. The workspace row is soft-deleted, so the on-delete-cascade FK never
 * fires in the normal discard flow — {@code WorkspaceService.doDiscard} deletes this row
 * explicitly.
 */
@Entity
@Table(name = "workspace_prompt_draft")
public class WorkspacePromptDraft extends PanacheEntityBase {

  /** Shares the owning {@link Workspace}'s {@code id} — this is both the PK and the FK. */
  @Id
  @Column(name = "workspace_id_fk")
  public Long workspaceId;

  /**
   * Opaque composition JSON (picks, references, canvas, chat draft, …), never read by the server.
   */
  @Lob
  @Column(nullable = false)
  public String content;

  /** The launch-ready markdown the client serialized — served verbatim to the agent, nullable. */
  @Lob
  @Column(name = "serialized_prompt")
  public String serializedPrompt;

  /**
   * A monotonic counter bumped by every content-changing {@code upsert}. It names the exact draft
   * state a launch handed to the agent (recorded in {@link #lastRunPromptVersion}), so the Agents
   * tab can tell an un-run edit from an already-delivered one and not silently re-run it on
   * re-attach. Distinct from {@link #updatedAt} (a wall-clock timestamp two rapid saves could
   * share) — this is a strict integer ordering.
   */
  @Column(name = "prompt_version", nullable = false)
  public long promptVersion;

  /** When a bootstrap turn was last delivered from this draft to an agent run; null until then. */
  @Column(name = "last_run_at")
  public Instant lastRunAt;

  /** The {@link #promptVersion} that was delivered on the last run; null until the first run. */
  @Column(name = "last_run_prompt_version")
  public Long lastRunPromptVersion;

  /**
   * The launched {@code Command} id of the last run — it owns the agent-session lineage (its
   * session refs carry the {@code sessionId}), so this links a composed prompt version to the
   * session that ran it. Null until the first run.
   */
  @Column(name = "last_run_command_id")
  public String lastRunCommandId;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
