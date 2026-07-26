package eu.wohlben.qits.domain.repository.dto;

import java.time.Instant;

/**
 * A workspace's persisted prompt draft as returned to the client.
 *
 * @param content the opaque composition JSON the UI owns
 * @param serializedPrompt the launch-ready markdown (nullable), the server-readable part
 * @param promptVersion the monotonic version bumped on each content-changing save
 * @param lastRunAt when a bootstrap turn was last delivered from this draft (nullable)
 * @param lastRunPromptVersion the {@code promptVersion} that was last handed to an agent (nullable)
 * @param lastRunCommandId the launched command id of the last run (nullable)
 * @param updatedAt when the draft was last saved
 */
public record WorkspacePromptDraftDto(
    String content,
    String serializedPrompt,
    long promptVersion,
    Instant lastRunAt,
    Long lastRunPromptVersion,
    String lastRunCommandId,
    Instant updatedAt) {}
