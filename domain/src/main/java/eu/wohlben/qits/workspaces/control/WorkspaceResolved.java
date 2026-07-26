package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;

/**
 * Fired when a workspace reaches a terminal state — integrated or abandoned. Its container and
 * branch are gone; the row survives as history.
 *
 * <p>This exists because the delete is <strong>soft</strong>. Rows in other contexts that hang off a
 * workspace (prompt drafts, prompt attachments, bootstrap runs) are cascaded by a foreign key that
 * therefore never fires, so before the extraction {@code WorkspaceService} reached across and
 * hard-deleted them itself. It can no longer see those tables, and shouldn't: this event replaces
 * the reach-across, and generalizes to every future per-workspace child table.
 *
 * <p>Fired <strong>synchronously, inside the resolving transaction</strong> — deliberately not
 * {@code fireAsync}, unlike {@link WorkspaceChangeHint}. Observers must see, and be able to join,
 * the same transaction that flipped the status; an async observer would race the commit and could
 * delete rows for a resolution that then rolled back.
 *
 * @param repoId the repository the workspace belonged to
 * @param workspaceId the reusable slug — no longer unique among ACTIVE rows after this
 * @param workspaceRowId the surrogate id, the stable key for child rows
 * @param resolution {@code INTEGRATED} or {@code ABANDONED}
 */
public record WorkspaceResolved(
    String repoId, String workspaceId, Long workspaceRowId, WorkspaceStatus resolution) {}
