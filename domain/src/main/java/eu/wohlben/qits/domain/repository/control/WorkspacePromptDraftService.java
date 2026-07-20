package eu.wohlben.qits.domain.repository.control;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.error.PayloadTooLargeException;
import eu.wohlben.qits.domain.repository.entity.Workspace;
import eu.wohlben.qits.domain.repository.entity.WorkspacePromptDraft;
import eu.wohlben.qits.domain.repository.persistence.RepositoryRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspacePromptDraftRepository;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
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

  @Inject RepositoryRepository repositoryRepository;

  @Inject WorkspaceRepository workspaceRepository;

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

  /** The active workspace behind {@code repoId}/{@code workspaceId}, or 404. */
  private Workspace resolveWorkspace(String repoId, String workspaceId) {
    repositoryRepository
        .findByIdOptional(repoId)
        .orElseThrow(() -> new NotFoundException("Repository not found: " + repoId));
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
  }

  /** The workspace's current draft, or 404 when none has been saved. */
  public WorkspacePromptDraft getDraft(String repoId, String workspaceId) {
    Workspace workspace = resolveWorkspace(repoId, workspaceId);
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

    Workspace workspace = resolveWorkspace(repoId, workspaceId);
    WorkspacePromptDraft draft =
        draftRepository
            .findByWorkspaceId(workspace.id)
            .orElseGet(
                () -> {
                  WorkspacePromptDraft fresh = new WorkspacePromptDraft();
                  fresh.workspaceId = workspace.id;
                  return fresh;
                });
    draft.content = content;
    draft.serializedPrompt = serializedPrompt;
    draftRepository.persist(draft);
    // Notify other open clients (another device/browser) to rehydrate — they apply the refetched
    // draft only when their local copy is pristine, so this never clobbers mid-typing.
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
    return draft;
  }

  /**
   * Removes a workspace's draft and its attachment rows (idempotent — a no-op when none exist). The
   * attachments are the draft's payload, so clearing the draft clears them too.
   */
  @Transactional
  public void deleteDraft(String repoId, String workspaceId) {
    Workspace workspace = resolveWorkspace(repoId, workspaceId);
    draftRepository.deleteByWorkspaceId(workspace.id);
    attachmentRepository.deleteByWorkspaceId(workspace.id);
    changePublisher.fire(repoId, workspaceId, WorkspaceChangeHint.Topic.PROMPT_DRAFT);
  }
}
