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
import eu.wohlben.qits.workspaces.gitmirror.TagOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The git half of landing one branch on another: merge, optionally stamp a version into the same
 * index, commit it once, and <b>push</b>.
 *
 * <p>Pure mechanics — it owns no row, fires no event and resolves no workspace. {@link
 * WorkspaceService} owns all of that and calls this for the steps that are about git and files. The
 * git itself is {@code qits-workspaces-gitmirror}'s: this class decides the <i>order</i>, and the
 * order is the flow.
 *
 * <h2>One method, two spellings</h2>
 *
 * A <b>release</b> and a plain <b>integrate</b> are two different processes to a user — one lands on
 * the default branch and stamps a version, the other lands a task branch on its parent and stamps
 * nothing — but they are one flow to git, and {@link Mode} is the whole of the difference: whether
 * the version is stamped and bumped, what the commit subject reads, and whether the push carries the
 * release option. Every safety property below is shared by construction rather than by two
 * implementations agreeing to keep matching.
 *
 * <p>The flow is keyed by <b>(repository, source branch)</b> and by nothing else — the worktree name
 * is derived from the source branch, not from a workspace row — so a branch-keyed caller is a thin
 * resolver over this same method rather than a second copy of it.
 *
 * <h2>Where the merge happens, and why it is not the served repository any more</h2>
 *
 * It used to be a worktree <b>on the bare origin qits-artifacts serves</b>, on a volume this service
 * and that one both mount. Fast, and the source of a whole family of defects: a linked worktree
 * shares its repository's ref store, so anything this flow created was published with no push, no
 * {@code post-receive} and no CI run — and a failure left it behind in a repository other people
 * read.
 *
 * <p>Now the worktree is on a <b>mirror</b> of that repository, private to this service, refreshed
 * from the git host at step 0. Nothing outside this process sees anything this flow does until
 * receive-pack accepts the push. That makes the release a push like any other push, and nothing
 * downstream learns a new trick.
 *
 * <h2>Why nothing needs unwinding</h2>
 *
 * The worktree is created <b>detached</b>, so nothing before the push moves a ref that outlives the
 * run: the merge, the bump, the commit and now the tag all happen against a {@code HEAD} that is not
 * a branch, in a repository nobody serves. A conflict, a bump failure or a crash leaves the default
 * branch byte-identical, and the only cleanup a failure needs is removing the worktree — which
 * {@link MirrorWorktree} does on close, on every path.
 *
 * <h2>The tag, and the dance that is gone</h2>
 *
 * A release creates its annotated tag with a plain {@code git tag -a} and pushes it by name. That is
 * all it ever should have been. The three-step dance it replaces — tag, read the tag object's sha,
 * delete the ref, push by sha — existed for exactly one reason: the worktree shared the served
 * bare's ref store, so creating the tag published it, the push then reported {@code [up to date]}
 * with zero receive commands, and the {@code finally} had to sweep up a ref in a repository the
 * platform reads. On a mirror none of those three things is true, so none of the three steps is
 * needed. What survives is the only leftover that can still bite: a tag this run created and did not
 * push would refuse the same version locally next time, so it is dropped from the <i>cache</i> — and
 * that is cache hygiene, not a ref anybody could have seen.
 *
 * <h2>Why the tag rides the same push</h2>
 *
 * One {@code git push --atomic} carrying both {@code HEAD:refs/heads/<main>} and {@code
 * refs/tags/<version>} is one receive-pack, so both commands ride one pre-receive and one
 * post-receive, and either both land or neither does. That is what turns the tag into the version
 * <b>uniqueness</b> guarantee the platform never had: a non-forced push over an existing tag ref is
 * refused, and under {@code --atomic} that refusal takes the branch update with it. Two releases
 * that stamped the same second cannot both land, and the loser lands nothing at all.
 *
 * <p>It still fires from both ends. The mirror was refreshed from the host at step 0, so its tags
 * are the host's tags and {@code git tag -a} refuses a version already released before this run has
 * pushed anything; the push covers a writer who gets there later.
 *
 * <h2>Why the push is the compare-and-swap</h2>
 *
 * A release's push carries {@code -o qits.release}, which the git host's protection hook accepts for
 * <b>fast-forward updates only</b>. It is deliberately not granted force, so two releases racing
 * cannot both win: the loser is rejected as non-fast-forward and told to retry, with the default
 * branch in a correct state either way. That is git's own atomic ref update doing the work, which
 * is why this feature needs no distributed lock — the in-process repository lease upstream only
 * turns the common case from "one fails" into "one waits".
 *
 * <p><b>What it does not settle:</b> two releases of this flow. The lease is held across the whole
 * operation, push included, so they are sequential — the second builds on the first's commit and its
 * push is a clean fast-forward. The compare-and-swap decides a race against a writer <i>outside</i>
 * this flow; the tag is what decides two releases that stamped one version.
 *
 * <p>A plain integrate sends <b>no push option</b>, and that is not an oversight: the hook guards
 * the default branch and nothing else, so a task branch landing on its parent is an ordinary push.
 * It is still a compare-and-swap, because an ordinary push is fast-forward-only — that property
 * belongs to receive-pack, not to the option.
 */
@ApplicationScoped
public class ReleaseIntegrator {

  private static final Logger LOG = Logger.getLogger(ReleaseIntegrator.class);

  /** The push option the git host's protection hook accepts a fast-forward release under. */
  static final String RELEASE_PUSH_OPTION = "qits.release";

  @Inject GitMirrorRegistry mirrors;

  @Inject GitIdentity gitIdentity;

  @Inject VersionBumper bumper;

  /**
   * Which of the two processes this run is. The only difference between them, and therefore the only
   * thing that can drift.
   */
  public enum Mode {
    /**
     * A release: stamp a fresh version, bump the manifests into the same index, commit it as {@code
     * release(<version>): <summary>} and push with {@code -o qits.release}. The target is the
     * default branch, and this is the only door into it.
     */
    RELEASE,
    /**
     * A plain integrate: no stamp, no bump, {@code integrate(<source>): <summary>}, no push option.
     * The target is the source's parent branch and is never the default branch — a task branch
     * landing on its epic, which the epic then releases.
     */
    PLAIN
  }

  /**
   * One run of the flow.
   *
   * @param repoId the repository, which this service mirrors and pushes to
   * @param sourceBranch the branch being landed — also what the worktree is named after
   * @param targetBranch what it lands on: the default branch for {@link Mode#RELEASE}, the source's
   *     parent for {@link Mode#PLAIN}
   * @param summary the commit subject after the scope
   */
  public record Run(
      String repoId, String sourceBranch, String targetBranch, String summary, Mode mode) {}

  /**
   * What one run produced, and the record the {@code SCMRelease} publisher is handed.
   *
   * @param version the stamp, taken once at step 4 and threaded through — <b>null for {@link
   *     Mode#PLAIN}</b>, which stamps nothing
   * @param commitSha the single merge commit, carrying the bump too when there was one
   * @param branch the source branch that was landed — the merge's parents record it as a sha, never
   *     as a name
   * @param targetBranch what it landed on
   * @param publishedAt when the push was accepted
   */
  public record Landed(
      String version,
      String commitSha,
      String branch,
      String targetBranch,
      Instant publishedAt) {}

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
    // than hoped, now with no served ref store behind it to leak into.
    String version = null;
    boolean tagged = false;
    boolean pushed = false;
    try (MirrorWorktree worktree = mirror.worktree(sourceBranch, targetRef)) {
      // [3] the merge, staged but NOT committed — MERGE_HEAD stays set and the index stays open,
      // which is what lets the bump write into the same index and produce ONE commit.
      mergeNoCommit(worktree, sourceRef, sourceBranch);

      // [4] the stamp, taken ONCE. Recomputing it per file would let a slow bump write two versions
      // into one commit. A plain integrate is not a release and takes none.
      version = run.mode() == Mode.RELEASE ? VersionStamp.of(Instant.now()) : null;

      // [5] the bump, into the open index. Nothing to render without a version.
      if (version != null) {
        stageBump(worktree, version);
      }

      // [6] one commit: two parents (the merge) plus, for a release, the version change.
      String subject = subjectOf(run, version);
      String commitSha = commit(worktree, run, version, subject);

      // [7] the tag. A release only; a plain integrate tags nothing, because a tag names a version
      // and it has none.
      if (version != null) {
        TagOutcome outcome = tag(worktree, version, subject);
        if (outcome.alreadyExists()) {
          throw versionAlreadyReleased(version);
        }
        tagged = true;
      }

      // [8] the push, which is the compare-and-swap — and, with the tag riding along, the
      // uniqueness check too.
      Instant publishedAt = push(worktree, targetBranch, run.mode(), version);
      pushed = true;

      LOG.infof(
          "landed %s on %s of %s%s (%s)",
          sourceBranch, targetBranch, repoId, version == null ? "" : " as " + version, commitSha);
      return new Landed(version, commitSha, sourceBranch, targetBranch, publishedAt);
    } finally {
      // [9] cleanup. The worktree goes on every path — that is what AutoCloseable buys. What is left
      // to sweep is a tag this run created and did not manage to push: it names nothing anybody can
      // see, but it would refuse this repository's next attempt at the same version out of a local
      // leftover, so it is dropped from the cache.
      if (tagged && !pushed) {
        mirror.deleteLocalTag(version);
      }
    }
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
   * retry finds it is an ancestor of the target and says so, rather than stamping a second version
   * onto an empty merge — which is what "integrate is not idempotent by design" costs and how it is
   * paid for.
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
  // [3] merge, [5] bump, [6] commit
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
   * Writes the version into the worktree and stages it into the <b>same</b> index the merge left
   * open. A repository with no version files is still a release: the bump reports nothing changed,
   * nothing is added, and the commit below is identical in every other way. The commit is the
   * release; the files are one stack's rendering of it.
   */
  private void stageBump(MirrorWorktree worktree, String version) {
    VersionBumper.BumpResult bump = bumper.bump(worktree.path(), version);
    try {
      worktree.stage(List.copyOf(bump.changedFiles()));
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to stage the version bump: " + e.getMessage());
    }
  }

  /**
   * The two subjects.
   *
   * <p>The scope says which process this was, so a reader of {@code git log} never has to infer it:
   * {@code release(<version>)} carries the stamp a release produced, {@code integrate(<source>)}
   * carries the branch a plain merge landed.
   *
   * <p>It is its own method because a release's tag message is this same line — one release, one
   * sentence, whether it is read off the commit or off the tag.
   */
  private static String subjectOf(Run run, String version) {
    return version != null
        ? "release(" + version + "): " + run.summary()
        : "integrate(" + run.sourceBranch() + "): " + run.summary();
  }

  /**
   * The one commit.
   *
   * <p>The body names both branches — the merge's parents record the graph, but the branch
   * <em>names</em> do not survive the merge otherwise, and they are what a human reads.
   */
  private String commit(MirrorWorktree worktree, Run run, String version, String subject) {
    boolean release = version != null;
    String body =
        release
            ? "Integrates workspace branch `" + run.sourceBranch() + "`."
            : "Integrates workspace branch `"
                + run.sourceBranch()
                + "` into `"
                + run.targetBranch()
                + "` without a release.";
    try {
      return worktree.commit(subject, body, identity());
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to commit the merge: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [7] the tag
  // ---------------------------------------------------------------------------------------------

  /**
   * {@code git tag -a <version> -m <subject> HEAD} in the mirror's worktree, with the release
   * commit's own identity as the tagger — one release, one name on it.
   *
   * <p>The tag name is the version string exactly: {@code 2026.801.63140}, no {@code v} prefix. It
   * is the same string the manifests, the event and the image tags carry, and a prefix here would be
   * a second spelling of one identity.
   */
  private TagOutcome tag(MirrorWorktree worktree, String version, String subject) {
    try {
      return worktree.tag(version, subject, identity());
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to tag the release: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [8] the push
  // ---------------------------------------------------------------------------------------------

  private Instant push(MirrorWorktree worktree, String target, Mode mode, String version) {
    PushSpec spec = PushSpec.of(PushSpec.Ref.branch("HEAD", target));
    if (version != null) {
      // All or nothing. A refused tag must not leave the branch advanced, and a refused branch must
      // not leave a tag naming a release that did not happen. Pushed by NAME: the tag lives in the
      // mirror, which nobody serves, so there is nothing to unreference first.
      spec =
          PushSpec.of(
                  PushSpec.Ref.branch("HEAD", target),
                  PushSpec.Ref.tag("refs/tags/" + version, version))
              .asAtomic();
    }
    // Only a release needs the option: the git host's hook guards the default branch and nothing
    // else, so a plain integrate's target is an ordinary ref and an ordinary push moves it.
    if (mode == Mode.RELEASE) {
      spec = spec.withOption(RELEASE_PUSH_OPTION);
    }
    PushOutcome outcome;
    try {
      outcome = worktree.push(spec);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("The push failed: " + e.getMessage());
    }
    if (outcome.accepted()) {
      return Instant.now();
    }
    throw classifyPushFailure(outcome, target, version);
  }

  /**
   * Reads a rejected push for what it was.
   *
   * <p>Order matters twice. The tag refusal is read first because it is the only one whose cause is
   * not the branch at all, and under {@code --atomic} it also renders the branch as rejected — read
   * the other way round, a duplicate version would report itself as a lost race. After that, a hook
   * refusal and a non-fast-forward are both "[…rejected]" lines and only the {@code remote} marker
   * tells them apart, so the remote one comes next. A push the git host's protection hook declined
   * must surface as a <b>4xx carrying the hook's own message</b> — never a 500 — because that
   * message is the only thing on screen that says what to do instead.
   */
  private RuntimeException classifyPushFailure(PushOutcome outcome, String target, String version) {
    if (version != null && outcome.saysAlreadyExists()) {
      return versionAlreadyReleased(version);
    }
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

  /**
   * The one refusal a version stamp can produce, in the one wording both halves of the guarantee
   * use.
   *
   * <p>It is <b>retryable</b>, and that is the whole reason it is not {@link Reason#PUSH_REJECTED}:
   * the next stamp is a different second, so pressing the button again is the right thing to do,
   * where a hook refusal means it never will be.
   */
  private static IntegrateConflictException versionAlreadyReleased(String version) {
    return new IntegrateConflictException(
        Reason.VERSION_ALREADY_RELEASED,
        "Version "
            + version
            + " is already tagged in this repository, so this release was refused whole: nothing"
            + " landed and the default branch is unchanged. Try again — a release a second later"
            + " stamps a different version.");
  }

  private eu.wohlben.qits.workspaces.gitmirror.CommitIdentity identity() {
    return gitIdentity.forMirror();
  }
}
