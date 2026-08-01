package eu.wohlben.qits.workspaces.gitmirror;

/**
 * How far a branch is ahead of and behind another, as {@code rev-list --left-right --count} counts
 * it.
 *
 * <p>{@code null} on both sides means git could not compare the two — an unresolvable ref, most
 * often a branch the mirror has not fetched yet. It is deliberately distinct from {@code (0, 0)}:
 * "in step" and "unknown" drive different decisions, and a cleanup that read the second as the first
 * would delete unmerged work.
 */
public record AheadBehind(Integer ahead, Integer behind) {

  public static final AheadBehind IN_STEP = new AheadBehind(0, 0);
  public static final AheadBehind UNKNOWN = new AheadBehind(null, null);
}
