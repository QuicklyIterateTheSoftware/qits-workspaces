package eu.wohlben.qits.workspaces.gitmirror;

import java.util.List;

/**
 * What a merge — previewed in the object store or actually staged in a worktree — came to.
 *
 * <p>A conflict is an <b>answer</b>, not a failure, which is why it is a record and not an
 * exception: the caller turns it into a 409 carrying the file list, and the file list is the only
 * thing on screen a person can act on.
 *
 * @param clean true when the merge applied with no conflicts
 * @param conflictedPaths the conflicting files, empty when clean
 * @param output git's own words, kept for the failure messages that quote them
 */
public record MergeOutcome(boolean clean, List<String> conflictedPaths, String output) {

  public static MergeOutcome clean(String output) {
    return new MergeOutcome(true, List.of(), output);
  }

  public static MergeOutcome conflicted(List<String> paths, String output) {
    return new MergeOutcome(false, List.copyOf(paths), output);
  }
}
