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

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
