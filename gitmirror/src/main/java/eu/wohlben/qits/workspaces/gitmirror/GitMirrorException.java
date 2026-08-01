package eu.wohlben.qits.workspaces.gitmirror;

/**
 * A git operation this module could not carry out at all — a fetch that failed, a worktree that
 * could not be created, a commit git refused for a reason nothing here models.
 *
 * <p>Deliberately <b>not</b> raised for the answers that are answers: a conflicted merge, a tag name
 * already taken and a rejected push are all returned as records, because the caller acts on each of
 * them differently and an exception would flatten the three into one 500.
 */
public class GitMirrorException extends RuntimeException {

  public GitMirrorException(String message) {
    super(message);
  }

  public GitMirrorException(String message, Throwable cause) {
    super(message, cause);
  }
}
