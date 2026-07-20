package eu.wohlben.qits.domain.repository.control;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.error.PayloadTooLargeException;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptDraftRepository;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CRUD for a workspace's persisted prompt draft (one row per workspace). The draft is host-side
 * data only — no container involvement — so it works on a {@code STOPPED} workspace without
 * materializing anything. The {@code content} blob is stored opaquely (the server validates only
 * that it is well-formed JSON within a size cap); {@code serializedPrompt} rides alongside as the
 * server-readable launch-ready markdown.
 */
@ApplicationScoped
public class WorkspacePromptDraftService {

  @Inject WorkspaceResolver workspaceResolver;

  @Inject WorkspacePromptDraftRepository draftRepository;

  @Inject WorkspacePromptAttachmentRepository attachmentRepository;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject ObjectMapper objectMapper;

  /**
   * The draft payload is capped here — the combined UTF-8 size of {@code content} and {@code
   * serializedPrompt} over this yields a 413 (both are unbounded {@code @Lob}s, so both count).
   */
  @ConfigProperty(name = "qits.workspace.prompt-draft-max-bytes", defaultValue = "2097152")
  long maxBytes;

  /**
   * Whether this workspace has a draft the {@code taskPrompt} tool would serve — a non-blank {@code
   * serializedPrompt} or at least one attachment. Read-only, container-free; the launch path uses
   * it to decide whether a bootstrap turn is worth pushing (no draft ⇒ nothing to fetch, so the
   * session stays idle).
   */
  @Transactional
  public boolean hasDeliverablePrompt(String repoId, String workspaceId) {
    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    boolean hasMarkdown =
        draftRepository
            .findByWorkspaceId(workspace.id)
            .map(d -> d.serializedPrompt)
            .filter(s -> s != null && !s.isBlank())
            .isPresent();
    return hasMarkdown || !attachmentRepository.listByWorkspaceId(workspace.id).isEmpty();
  }

  /** The workspace's current draft, or 404 when none has been saved. */
  public WorkspacePromptDraft getDraft(String repoId, String workspaceId) {
    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    return draftRepository
        .findByWorkspaceId(workspace.id)
        .orElseThrow(() -> new NotFoundException("No prompt draft for workspace: " + workspaceId));
  }

  /**
   * Idempotent upsert of a workspace's draft. Validates the opaque {@code content} against the size
   * cap (413) and JSON well-formedness (400) before writing; {@code serializedPrompt} is stored
   * verbatim. Returns the persisted entity so the caller can read the fresh {@code updatedAt}.
   */
  @Transactional
  public WorkspacePromptDraft saveDraft(
      String repoId, String workspaceId, String content, String serializedPrompt) {
    // Validate the payload before touching the DB — the cheap in-memory guards fail fast, so a
    // buggy autosave loop of rejected requests costs no repository round-trips.
    long serializedBytes =
        serializedPrompt == null ? 0 : serializedPrompt.getBytes(StandardCharsets.UTF_8).length;
    if (content.getBytes(StandardCharsets.UTF_8).length + serializedBytes > maxBytes) {
      throw new PayloadTooLargeException("Prompt draft exceeds the " + maxBytes + "-byte limit");
    }
    try {
      // readValue (not readTree) so empty/blank input and trailing garbage after a complete value
      // are both rejected — readTree accepts an empty document and stops at the first value.
      objectMapper
          .readerFor(JsonNode.class)
          .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(content);
    } catch (Exception e) {
      throw new BadRequestException("Prompt draft content is not valid JSON", e);
    }

    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    // Atomic DB-level upsert (H2 MERGE) rather than a read-then-insert: the draft's PK *is* the
    // workspace id (shared 1:1 PK/FK), so two concurrent first-saves for a draftless workspace —
    // the exact cross-device flow this feature targets — would both find no row and both insert the
    // same PK, and the loser's insert would 500 on the constraint violation. MERGE serializes them
    // under the row lock (last write wins), so a first-insert race is a clean upsert, not a 500.
    // See docs/issues/resolved/2026-07-20_prompt-draft-concurrent-first-insert-500.md.
    draftRepository.upsert(workspace.id, content, serializedPrompt);
    // Notify other open clients (another device/browser) to rehydrate — they apply the refetched
    // draft only when their local copy is pristine, so this never clobbers mid-typing.
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
    // Re-read so the returned entity carries the DB-assigned updatedAt (the value a later GET will
    // return) — the client stores it to dedup its own SSE echo, so it must match byte-for-byte.
    return draftRepository
        .findByWorkspaceId(workspace.id)
        .orElseThrow(() -> new NotFoundException("No prompt draft for workspace: " + workspaceId));
  }

  /**
   * Records that this workspace's draft was handed to an agent run — stamps {@code last_run_at},
   * copies the live {@code prompt_version} into {@code last_run_prompt_version}, and records the
   * launched {@code commandId} (which owns the run's session lineage). Fires a {@code PROMPT_DRAFT}
   * hint so open views reflect "handed to the agent". A no-op when the workspace has no draft row.
   * Called from {@code AgentLaunchService} after a launch that delivered the bootstrap turn.
   */
  @Transactional
  public void recordRun(String repoId, String workspaceId, String commandId) {
    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    draftRepository.recordRun(workspace.id, commandId);
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
  }

  /**
   * Removes a workspace's draft and its attachment rows (idempotent — a no-op when none exist). The
   * attachments are the draft's payload, so clearing the draft clears them too.
   */
  @Transactional
  public void deleteDraft(String repoId, String workspaceId) {
    Workspace workspace = workspaceResolver.resolveActive(repoId, workspaceId);
    draftRepository.deleteByWorkspaceId(workspace.id);
    attachmentRepository.deleteByWorkspaceId(workspace.id);
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
  }
}
