package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import eu.wohlben.qits.workspaces.error.IntegrateConflictException.Reason;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.gitmirror.GitMirrorException;
import eu.wohlben.qits.workspaces.gitmirror.MergeOutcome;
import eu.wohlben.qits.workspaces.gitmirror.MirrorWorktree;
import eu.wohlben.qits.workspaces.gitmirror.PushOutcome;
import eu.wohlben.qits.workspaces.gitmirror.PushSpec;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The git half of landing one workspace branch on another: merge, commit it once, and <b>push</b>.
 *
 * <p>Pure mechanics — it owns no row, fires no event and resolves no workspace. {@link
 * WorkspaceService} owns all of that and calls this for the steps that are about git and files. The
 * git itself is {@code qits-workspaces-gitmirror}'s: this class decides the <i>order</i>, and the
 * order is the flow.
 *
 * <h2>What this used to be, and why it is smaller</h2>
 *
 * It was {@code ReleaseIntegrator}, and it carried two spellings of one flow: a plain integrate, and
 * a <b>release</b> that also stamped a CalVer version, rewrote every manifest into the same index,
 * tagged the commit, pushed with {@code -o qits.release} and promoted the result onto an {@code
 * environment/*} entry branch. <b>None of that lives here any more.</b> A release is a release
 * request in qits-projects now: it octopus-merges its sources on a {@code release/<id>} backing
 * branch through qits-githost's git primitives, stamps and bumps there, and the tag — not this
 * service's push — is what a release is. {@code main} is finalized after the deployment, by
 * qits-projects, and this service writes it through no door at all.
 *
 * <p>What remains is the workspace feature that was never about releasing: a {@code task/…}
 * workspace landing on the {@code epic/…} it forked from, as one pushed merge commit that stamps
 * nothing. Every safety property below is what it always was — the release simply stopped being one
 * of this method's callers.
 *
 * <p>The flow is keyed by <b>(repository, source branch)</b> and by nothing else — the worktree name
 * is derived from the source branch, not from a workspace row — so a branch-keyed caller is a thin
 * resolver over this same method rather than a second copy of it.
 *
 * <h2>Where the merge happens, and why it is not the served repository</h2>
 *
 * It used to be a worktree <b>on the bare origin qits-artifacts serves</b>, on a volume this service
 * and that one both mount. Fast, and the source of a whole family of defects: a linked worktree
 * shares its repository's ref store, so anything this flow created was published with no push, no
 * {@code post-receive} and no CI run — and a failure left it behind in a repository other people
 * read.
 *
 * <p>Now the worktree is on a <b>mirror</b> of that repository, private to this service, refreshed
 * from the git host at step 0. Nothing outside this process sees anything this flow does until
 * receive-pack accepts the push. That makes the integrate a push like any other push, and nothing
 * downstream learns a new trick.
 *
 * <h2>Why nothing needs unwinding</h2>
 *
 * The worktree is created <b>detached</b>, so nothing before the push moves a ref that outlives the
 * run: the merge and the commit happen against a {@code HEAD} that is not a branch, in a repository
 * nobody serves. A conflict or a crash leaves the target branch byte-identical, and the only cleanup
 * a failure needs is removing the worktree — which {@link MirrorWorktree} does on close, on every
 * path.
 *
 * <h2>Why the push is the compare-and-swap</h2>
 *
 * An integrate sends <b>no push option</b>, and that is not an oversight: the git host's protection
 * hook guards the default branch and nothing else, so a task branch landing on its parent is an
 * ordinary push. It is still a compare-and-swap, because an ordinary push is fast-forward-only —
 * that property belongs to receive-pack, not to any option. A target that moved underneath the merge
 * is {@link Reason#NOT_FAST_FORWARD}: nothing landed, and the caller retries.
 */
@ApplicationScoped
public class BranchIntegrator {

  private static final Logger LOG = Logger.getLogger(BranchIntegrator.class);

  @Inject GitMirrorRegistry mirrors;

  @Inject GitIdentity gitIdentity;

  /**
   * One run of the flow.
   *
   * @param repoId the repository, which this service mirrors and pushes to
   * @param sourceBranch the branch being landed — also what the worktree is named after
   * @param targetBranch what it lands on: the source's parent, and never the default branch, which
   *     this service no longer writes
   * @param summary the commit subject after the scope
   */
  public record Run(String repoId, String sourceBranch, String targetBranch, String summary) {}

  /**
   * What one run produced.
   *
   * @param commitSha the single merge commit
   * @param branch the source branch that was landed — the merge's parents record it as a sha, never
   *     as a name
   * @param targetBranch what it landed on
   */
  public record Landed(String commitSha, String branch, String targetBranch) {}

  /**
   * Run the flow for one repository.
   *
   * @throws IntegrateConflictException for every refusal a caller can act on — see {@link
   *     IntegrateConflictException.Reason}
   */
  public Landed land(Run run) {
    String repoId = run.repoId();
    String sourceBranch = run.sourceBranch();
    String targetBranch = run.targetBranch();
    RepoMirror mirror = mirrors.of(repoId);

    // [0] the mirror IS the object store now, so it has to be the host's. A preflight against a
    // stale copy is a preflight against a repository nobody has.
    refresh(mirror);
    String targetRef = "refs/heads/" + targetBranch;
    String sourceRef = "refs/heads/" + sourceBranch;
    if (mirror.resolve(targetRef).isEmpty() || mirror.resolve(sourceRef).isEmpty()) {
      throw new NotFoundException(
          "Repository " + repoId + " has no '" + sourceBranch + "' or '" + targetBranch + "'");
    }

    // [1] preflight, in the mirror's object store — nothing is checked out and no ref moves.
    requireNotAlreadyIntegrated(mirror, sourceRef, targetRef, sourceBranch, targetBranch);
    preflightMerge(mirror, sourceRef, targetRef, sourceBranch, targetBranch);

    // [2] a DETACHED worktree on the mirror: the property that makes "no partial state" true rather
    // than hoped, with no served ref store behind it to leak into.
    try (MirrorWorktree worktree = mirror.worktree(sourceBranch, targetRef)) {
      // [3] the merge, staged but NOT committed — MERGE_HEAD stays set and the index stays open,
      // which is what makes the commit below a real two-parent merge.
      mergeNoCommit(worktree, sourceRef, sourceBranch);

      // [4] one commit: two parents, and the branch names in the body.
      String commitSha = commit(worktree, run);

      // [5] the push, which is the compare-and-swap.
      push(worktree, targetBranch);

      LOG.infof("landed %s on %s of %s (%s)", sourceBranch, targetBranch, repoId, commitSha);
      return new Landed(commitSha, sourceBranch, targetBranch);
    }
    // [6] cleanup is the worktree, and it goes on every path — that is what AutoCloseable buys.
  }

  // ---------------------------------------------------------------------------------------------
  // [0] the mirror
  // ---------------------------------------------------------------------------------------------

  private void refresh(RepoMirror mirror) {
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not read the repository from the git host: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [1] preflight
  // ---------------------------------------------------------------------------------------------

  /**
   * A succeeded integrate whose response was lost leaves the source branch already merged. The
   * retry finds it is an ancestor of the target and says so, rather than producing an empty merge —
   * which is what "integrate is not idempotent by design" costs and how it is paid for.
   */
  private void requireNotAlreadyIntegrated(
      RepoMirror mirror, String sourceRef, String targetRef, String source, String target) {
    if (mirror.isAncestor(sourceRef, targetRef)) {
      throw new IntegrateConflictException(
          Reason.ALREADY_INTEGRATED,
          "Branch '"
              + source
              + "' is already integrated into '"
              + target
              + "': it is an ancestor of it, so there is nothing to land.");
    }
  }

  /**
   * The real three-way merge, in the object store, with no working tree involved ({@code merge-tree
   * --write-tree}). Catching the common case here means the usual conflict costs no worktree at all;
   * step 3 is the backstop for the rest.
   */
  private void preflightMerge(
      RepoMirror mirror, String sourceRef, String targetRef, String source, String target) {
    MergeOutcome preview;
    try {
      preview = mirror.previewMerge(targetRef, sourceRef);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to preflight the merge: " + e.getMessage());
    }
    if (preview.clean()) {
      return;
    }
    throw new IntegrateConflictException(
        Reason.CONFLICT,
        "Merging '" + source + "' into '" + target + "' conflicts. Nothing landed and '"
            + target
            + "' is unchanged.",
        preview.conflictedPaths());
  }

  // ---------------------------------------------------------------------------------------------
  // [3] merge, [4] commit
  // ---------------------------------------------------------------------------------------------

  private void mergeNoCommit(MirrorWorktree worktree, String sourceRef, String source) {
    MergeOutcome merged;
    try {
      merged = worktree.mergeNoCommit(sourceRef, identity());
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git merge failed: " + e.getMessage());
    }
    if (merged.clean()) {
      return;
    }
    // The backstop for whatever merge-tree did not see. The merge is already aborted, so the
    // worktree is removable, and this is the same 409 the preflight would have produced — never the
    // 500 a bare non-zero exit used to become.
    throw new IntegrateConflictException(
        Reason.MERGE_CONFLICT,
        "Merging '" + source + "' conflicts. Nothing landed and the target branch is unchanged.",
        merged.conflictedPaths());
  }

  /**
   * The one commit.
   *
   * <p>The subject says which process this was, so a reader of {@code git log} never has to infer
   * it, and the body names both branches — the merge's parents record the graph, but the branch
   * <em>names</em> do not survive the merge otherwise, and they are what a human reads.
   */
  private String commit(MirrorWorktree worktree, Run run) {
    String subject = "integrate(" + run.sourceBranch() + "): " + run.summary();
    String body =
        "Integrates workspace branch `"
            + run.sourceBranch()
            + "` into `"
            + run.targetBranch()
            + "`.";
    try {
      return worktree.commit(subject, body, identity());
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to commit the merge: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [5] the push
  // ---------------------------------------------------------------------------------------------

  /**
   * The push, and no option rides with it: the git host's hook guards the default branch and nothing
   * else, so a task branch landing on its parent is an ordinary ref and an ordinary push moves it.
   */
  private void push(MirrorWorktree worktree, String target) {
    PushOutcome outcome;
    try {
      outcome = worktree.push(PushSpec.of(PushSpec.Ref.branch("HEAD", target)));
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("The push failed: " + e.getMessage());
    }
    if (outcome.accepted()) {
      return;
    }
    throw classifyPushFailure(outcome, target);
  }

  /**
   * Reads a rejected push for what it was.
   *
   * <p>Order matters: a hook refusal and a non-fast-forward are both "[…rejected]" lines and only
   * the {@code remote} marker tells them apart, so the remote one comes first. A push the git host's
   * protection hook declined must surface as a <b>4xx carrying the hook's own message</b> — never a
   * 500 — because that message is the only thing on screen that says what to do instead.
   */
  private RuntimeException classifyPushFailure(PushOutcome outcome, String target) {
    String remoteRefusal = outcome.remoteRefusal();
    if (remoteRefusal != null) {
      return new IntegrateConflictException(
          Reason.PUSH_REJECTED, "The git host refused the push: " + remoteRefusal);
    }
    if (outcome.saysNotFastForward()) {
      return new IntegrateConflictException(
          Reason.NOT_FAST_FORWARD,
          "'"
              + target
              + "' moved while this merge was being built, so the push was not a fast-forward."
              + " Nothing landed — try again.");
    }
    String output = outcome.output();
    return new InternalServerErrorException(
        "The push failed: " + (output == null || output.isBlank() ? "no output" : output));
  }

  private eu.wohlben.qits.workspaces.gitmirror.CommitIdentity identity() {
    return gitIdentity.forMirror();
  }
}
