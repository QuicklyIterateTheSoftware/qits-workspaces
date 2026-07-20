package eu.wohlben.qits.domain.repository.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A single image attached to a workspace's prompt draft — a sketch export or a pasted screenshot,
 * stored as its own row (n:1 with the workspace) rather than base64 inside the draft's opaque
 * {@code content} blob. Keeping the bytes here keeps the blob small, lets the server enforce a
 * per-image cap and a PNG/JPEG magic-byte sniff at upload time, and is what a later step's {@code
 * taskPrompt} MCP tool turns into {@code ImageContent} blocks. The opaque blob references these
 * rows by {@link #id} only.
 *
 * <p>The {@code workspace_id_fk} FK is {@code on delete cascade}, but the workspace row is only
 * soft-deleted, so that cascade never fires in practice — {@code WorkspaceService} (and {@code
 * WorkspacePromptDraftService.deleteDraft}) delete these rows explicitly, same as {@link
 * WorkspacePromptDraft}.
 */
@Entity
@Table(name = "workspace_prompt_attachment")
public class WorkspacePromptAttachment extends PanacheEntityBase {

  /** A service-generated {@code UUID.randomUUID().toString()} — the blob references it verbatim. */
  @Id public String id;

  /** The owning {@link Workspace}'s surrogate {@code id}. */
  @Column(name = "workspace_id_fk", nullable = false)
  public Long workspaceId;

  /** The effective media type — the sniffed type, which wins over the client's claim. */
  @Column(name = "mime_type", nullable = false)
  public String mimeType;

  /** A human label the composing UI shows ("Sketch 1", "Pasted image 1"). */
  @Column(nullable = false)
  public String label;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public PromptAttachmentSource source;

  /** The raw image bytes (already base64-decoded), served to the agent as an image block. */
  @Lob
  @Column(nullable = false)
  public byte[] bytes;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
