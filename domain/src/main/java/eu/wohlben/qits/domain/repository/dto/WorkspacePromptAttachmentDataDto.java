package eu.wohlben.qits.domain.repository.dto;

import eu.wohlben.qits.domain.repository.entity.PromptAttachmentSource;
import java.time.Instant;

/**
 * A prompt attachment returned <em>with</em> its image payload — the row's metadata plus the
 * base64-encoded bytes, so the compose UI can rebuild the removable thumbnail rows after a refresh
 * (the draft blob references attachments by id only and never carries the bytes). This is the only
 * client-facing attachment shape; the agent reads the raw bytes off the entity via {@code
 * taskPrompt}, never through a DTO.
 *
 * @param id the service-generated id the draft blob references
 * @param mimeType the effective (sniffed) media type
 * @param label the human label ("Sketch 1")
 * @param source where the image came from
 * @param createdAt when it was attached
 * @param dataBase64 the base64-encoded image bytes (backs the {@code data:} thumbnail URL)
 */
public record WorkspacePromptAttachmentDataDto(
    String id,
    String mimeType,
    String label,
    PromptAttachmentSource source,
    Instant createdAt,
    String dataBase64) {}
