package eu.wohlben.qits.domain.repository.entity;

/**
 * The resolution state of a workspace. Workspaces are soft-deleted: cleanup/discard removes the
 * on-disk workspace and its branch but keeps the row as a persistent record of the unit of work.
 * Only one {@code ACTIVE} workspace may exist per {@code (repository, workspaceId)} at a time;
 * resolved rows accumulate as history (so a workspace id can be reused once its predecessor is
 * resolved).
 */
public enum WorkspaceStatus {
  /** Live: checked out on disk, with a branch. */
  ACTIVE,
  /** Resolved by integrating its branch (cleaned up after a merge). */
  INTEGRATED,
  /** Resolved by abandoning it (discarded without integrating). */
  ABANDONED
}
