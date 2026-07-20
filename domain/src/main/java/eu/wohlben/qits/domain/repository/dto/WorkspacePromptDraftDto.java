package eu.wohlben.qits.domain.repository.dto;

import java.time.Instant;

/**
 * A workspace's persisted prompt draft as returned to the client.
 *
 * @param content the opaque composition JSON the UI owns
 * @param serializedPrompt the launch-ready markdown (nullable), the server-readable part
 * @param updatedAt when the draft was last saved
 */
public record WorkspacePromptDraftDto(String content, String serializedPrompt, Instant updatedAt) {}
