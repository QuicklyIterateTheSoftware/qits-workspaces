package eu.wohlben.qits.workspaces.control;

import java.time.Instant;

/**
 * The port a release is announced to the <b>platform at large</b> through — the seam the {@code
 * SoftwareRelease} event will hang off, and the only reason it exists today.
 *
 * <p>{@code bus/SoftwareReleaseAnnouncer} in the deployable is the implementation, and it publishes
 * {@code SoftwareRelease {projectId, repository, branch, version}}. The split is the {@code
 * RunAnnouncer} precedent from qits-ci, down to being declared in the domain module and implemented
 * in the deployable so the domain stays free of the bus and its transport.
 *
 * <p><b>Only a release is announced.</b> A plain integrate lands a task branch on its parent,
 * stamps no version and is not a release — an event that fired for both would make "a release
 * happened" unlistenable, which is the one thing this event exists to be.
 *
 * <p><b>Why the pushing service is the natural publisher.</b> Only the process that ran {@code git
 * push} knows that the push succeeded, atomically, with which version — the git host sees a ref
 * update and cannot name the release, and CI sees a commit and cannot tell a release from any other
 * merge. Step 7 of the release flow is therefore the only place in the platform that can make this
 * statement, which is why it returns a record through one method instead of vanishing into the
 * middle of a larger one.
 *
 * <p><b>Announced after the push, not after the transaction.</b> The push is irreversible the
 * instant receive-pack accepts it: {@code main} has moved, post-receive has fired, CI is already
 * building. A statement made only if the surrounding transaction later commits would be a statement
 * that can be false in the direction that matters — silent about a release that really happened.
 *
 * <p>Injected as {@code Instance<ReleaseAnnouncer>} and genuinely optional: with no implementation
 * present the release flow is unchanged and simply says nothing. That is what keeps the suite's own
 * double the only implementation a test sees.
 */
public interface ReleaseAnnouncer {

  /**
   * A release was pushed to a repository's default branch.
   *
   * @param projectId the project owning the repository, read off {@link
   *     RepositoryLookup.RepositoryView}. Passed rather than looked up again: the caller already
   *     holds the view, and a publisher that re-asked would make the event's project a second
   *     question with a second answer
   * @param repoId the repository the release landed in
   * @param branch the <b>source</b> branch that was released. There is no target parameter: the
   *     target is always the default branch, which is the whole feature
   * @param version the stamp this release carries, {@code YYYY.MMDD.HHMMSS}
   * @param commitSha the merge commit that carries both the merge and the version bump
   * @param publishedAt when the push was accepted — the event log's {@code occurredAt}, and never
   *     null, because a null one is a 400 on the wire
   */
  void onReleasePublished(
      String projectId,
      String repoId,
      String branch,
      String version,
      String commitSha,
      Instant publishedAt);
}
