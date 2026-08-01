package eu.wohlben.qits.workspaces.error;

import java.util.List;

/**
 * A 409 from the integrate flow, carrying <b>which</b> 409 it is.
 *
 * <p>Integrate has four ways to refuse and they are four different things a person does something
 * different about: resolve a conflict, press the button again, refresh the list, or read the git
 * host's own sentence. Every qits service maps a domain exception through one envelope — {@code
 * {"message": …}} and nothing else — so as the API stood the only channel carrying that difference
 * was prose, and a client had to word-match it. This subclass adds a machine-readable {@code reason}
 * and, for the two conflict modes, the conflicted paths.
 *
 * <p><b>Strictly additive.</b> {@code message} is unchanged and still says the whole thing; the new
 * fields sit beside it. A client that reads only {@code message} is unaffected, and one that reads
 * {@code reason} first stops guessing. {@code WorkspacesExceptionMapper} is what widens the body,
 * and only for this type — every other {@link DomainException} keeps the bare envelope.
 */
public class IntegrateConflictException extends ConflictException {

  /**
   * Why the integrate did not happen.
   *
   * <p>The first four are the frozen contract the workspaces client was built against. {@link
   * #PUSH_REJECTED} is outside it on purpose: a push refused by the git host's protection hook is a
   * failure mode neither side could enumerate in advance, because the refusal's <em>text</em> is the
   * useful part — it names the endpoint to use and what a deployment must configure. A client that
   * does not recognise the value falls through to showing the message verbatim, which is exactly
   * the right treatment.
   *
   * <p><b>The set is additive and stays that way.</b> {@link #RELEASE_REQUIRED} joined when the two
   * doors split, and it is the enum's whole purpose working: a new refusal is a new value, never a
   * new envelope.
   */
  public enum Reason {
    /** The preflight three-way merge conflicts. Nothing was attempted; no ref moved. */
    CONFLICT,
    /** The real merge conflicted in the worktree. Aborted; no ref moved. */
    MERGE_CONFLICT,
    /** The push lost the race — the default branch moved under this integrate. Retry. */
    NOT_FAST_FORWARD,
    /** The source branch is already an ancestor of the default branch. The work is in. */
    ALREADY_INTEGRATED,
    /** The git host refused the push. {@code message} is the host's own words. Not retryable. */
    PUSH_REJECTED,
    /**
     * The wrong door: this merge or integrate would land on the repository's default branch, which
     * only a release writes. Nothing was attempted. The caller wants {@code
     * POST /workspaces/api/workspaces/{id}/release} instead, and the message names it.
     *
     * <p>A refusal about <em>which endpoint</em> rather than about the state of the branches, which
     * is why it is worth a value of its own: a client can offer the right button instead of
     * word-matching prose for the endpoint name.
     */
    RELEASE_REQUIRED
  }

  private final Reason reason;
  private final List<String> conflicts;

  public IntegrateConflictException(Reason reason, String message) {
    this(reason, message, List.of());
  }

  public IntegrateConflictException(Reason reason, String message, List<String> conflicts) {
    super(message);
    this.reason = reason;
    this.conflicts = List.copyOf(conflicts);
  }

  public Reason reason() {
    return reason;
  }

  /** The conflicted paths, or empty for the modes that have none. Never null. */
  public List<String> conflicts() {
    return conflicts;
  }
}
