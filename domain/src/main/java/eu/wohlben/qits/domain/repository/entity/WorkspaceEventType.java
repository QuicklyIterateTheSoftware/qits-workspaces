package eu.wohlben.qits.domain.repository.entity;

/** A lifecycle event in a workspace's history timeline. */
public enum WorkspaceEventType {
  /** The workspace was created off its parent. */
  CREATED,
  /** The workspace's branch was merged into a target branch (the workspace stays active). */
  MERGED,
  /** The parent was merged into the workspace (kept up to date). */
  UPDATED_FROM_PARENT,
  /** The workspace was resolved by integration (branch merged, then cleaned up). */
  INTEGRATED,
  /** The workspace was resolved by abandonment (discarded). */
  ABANDONED
}
