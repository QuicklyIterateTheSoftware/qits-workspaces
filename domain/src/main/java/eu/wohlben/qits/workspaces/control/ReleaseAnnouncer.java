package eu.wohlben.qits.workspaces.control;

import java.time.Instant;

/**
 * The port a release is announced to the <b>platform at large</b> through — the seam the {@code
 * SoftwareRelease} event will hang off, and the only reason it exists today.
 *
 * <p><b>Nothing implements it yet, deliberately.</b> The event feature is a named follow-up: after
 * the {@code qits-eventstream} extraction settles, the deployable gains that dependency and one
 * bean here publishes {@code SoftwareRelease {projectId, repository, branch, version}}. What this
 * feature owes that feature is a clean place to stand, and a port is cheaper to leave than to carve
 * out of a method later — {@code RunAnnouncer} in qits-ci is the precedent this copies, down to
 * being declared in the domain module and implemented in the deployable so the domain stays free of
 * the bus and its transport.
 *
 * <p><b>Why the pushing service is the natural publisher.</b> Only the process that ran {@code git
 * push} knows that the push succeeded, atomically, with which version — the git host sees a ref
 * update and cannot name the release, and CI sees a commit and cannot tell a release from any other
 * merge. Step 7 of the integrate flow is therefore the only place in the platform that can make
 * this statement, which is why it returns a record through one method instead of vanishing into the
 * middle of a larger one.
 *
 * <p><b>Announced after the push, not after the transaction.</b> The push is irreversible the
 * instant receive-pack accepts it: {@code main} has moved, post-receive has fired, CI is already
 * building. A statement made only if the surrounding transaction later commits would be a statement
 * that can be false in the direction that matters — silent about a release that really happened.
 *
 * <p>Injected as {@code Instance<ReleaseAnnouncer>} and genuinely optional: with no implementation
 * present the integrate flow is unchanged and simply says nothing.
 *
 * <p>One gap, named rather than closed: the event's {@code projectId} is not available here. {@link
 * RepositoryLookup.RepositoryView} is {@code (id, mainBranch)} and carries no project, so the view
 * has to widen when the event lands. Naming it here saves the discovery later.
 */
public interface ReleaseAnnouncer {

  /**
   * A release was pushed to a repository's default branch.
   *
   * @param repoId the repository the release landed in
   * @param branch the <b>source</b> branch that was integrated. There is no target parameter: the
   *     target is always the default branch, which is the whole feature
   * @param version the stamp this release carries, {@code YYYY.MMDD.HHMMSS}
   * @param commitSha the merge commit that carries both the merge and the version bump
   * @param publishedAt when the push was accepted — the event log's {@code occurredAt}, and never
   *     null, because a null one is a 400 on the wire
   */
  void onReleasePublished(
      String repoId, String branch, String version, String commitSha, Instant publishedAt);
}
