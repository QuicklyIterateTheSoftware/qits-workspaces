package eu.wohlben.qits.workspaces.dto;

import java.time.Instant;

/**
 * One command that ran in a workspace, as the workspace history shows it.
 *
 * <p>A deliberately narrow view of the command context's own {@code CommandDto}: the history page
 * renders a table of what ran and how it ended, and these six fields are all of it. Importing the
 * full DTO would drag {@code CommandKind}, {@code CommandStatus} and the agent-session lineage into
 * this jar for no gain — {@code status} is therefore a plain String, whose values are the command
 * context's status names.
 */
public record WorkspaceCommandDto(
    String id,
    String actionName,
    String status,
    Integer exitCode,
    Instant launchedAt,
    Instant finishedAt) {}
