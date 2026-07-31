package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import eu.wohlben.qits.workspaces.error.IntegrateConflictException.Reason;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The git half of an integrate: merge a branch into the repository's default branch, stamp the
 * version into the same index, commit it once, and <b>push</b>.
 *
 * <p>Pure mechanics — it owns no row, fires no event and resolves no workspace. {@link
 * WorkspaceService#integrateWorkspace} owns all of that and calls this for the seven steps that are
 * about git and files.
 *
 * <h2>Why it pushes a repository it is already holding</h2>
 *
 * The bare origin is on this service's own disk, so advancing {@code main} could be one {@code git
 * merge} inside it — which is precisely what {@code WorkspaceService.mergeIntoTarget} does, and it
 * is why <b>no merge this service has ever performed produced a CI run</b>: a filesystem ref update
 * fires no {@code post-receive}. Pushing over HTTP to the ordinary git host makes receive-pack the
 * sole writer of the default branch, so the protection hook sees every release and the existing
 * post-receive → qits-ci → build chain happens for the ordinary reason. The release is a push like
 * any other push, and nothing downstream learns a new trick.
 *
 * <h2>Why nothing needs unwinding</h2>
 *
 * The worktree is created <b>detached</b>, so steps 2–6 move no ref anywhere: the merge, the bump
 * and the commit all happen against a {@code HEAD} that is not a branch. A conflict, a bump failure
 * or a crash leaves the default branch byte-identical, and the only cleanup a failure needs is
 * removing the worktree — which happens in a {@code finally}. The commit a failed push leaves
 * behind is unreferenced and is git's to collect.
 *
 * <h2>Why the push is the compare-and-swap</h2>
 *
 * The push carries {@code -o qits.release}, which the git host's protection hook accepts for
 * <b>fast-forward updates only</b>. It is deliberately not granted force, so two integrates racing
 * cannot both win: the loser is rejected as non-fast-forward and told to retry, with the default
 * branch in a correct state either way. That is git's own atomic ref update doing the work, which
 * is why this feature needs no distributed lock — the in-process repository lease upstream only
 * turns the common case from "one fails" into "one waits".
 *
 * <h2>Three inherited sharp edges, fixed here</h2>
 *
 * <ul>
 *   <li><b>Stale worktrees were never pruned.</b> A crashed merge leaves its admin registered and
 *       the <i>next</i> one fails with "already checked out" — a failure that outlives the process
 *       that caused it. {@link #prepareWorktree} prunes first, and removes a leftover directory the
 *       prune cannot (prune drops the registration, not the files).
 *   <li><b>{@code .tmp-merge-<currentTimeMillis>} collides</b> within a millisecond. The name here
 *       is the workspace row id, which is unique by construction.
 *   <li><b>{@code GitExecutor} had no timeout.</b> The push is this service's first <i>network</i>
 *       git call and integrate answers synchronously, so it gets a deadline; the local calls keep
 *       today's behaviour, where a bound would only turn slow into broken.
 * </ul>
 */
@ApplicationScoped
public class ReleaseIntegrator {

  private static final Logger LOG = Logger.getLogger(ReleaseIntegrator.class);

  /** The push option the git host's protection hook accepts a fast-forward release under. */
  static final String RELEASE_PUSH_OPTION = "qits.release";

  @Inject GitExecutor git;

  @Inject GitIdentity gitIdentity;

  @Inject VersionBumper bumper;

  @Inject GitHostAddress gitHost;

  @ConfigProperty(name = "qits.repositories.data-dir", defaultValue = "data/repositories")
  String dataDir;

  /**
   * How long the push may take before the integrate fails rather than holding a request thread. Two
   * minutes is generous for a push whose objects the receiving end already has (client and server
   * share an object store here, so the pack is nearly empty) and short enough that a wedged host is
   * an error rather than a hang.
   */
  @ConfigProperty(name = "qits.workspace.integrate.push-timeout-ms", defaultValue = "120000")
  long pushTimeoutMs;

  /**
   * What one integrate produced, and the record the future {@code SoftwareRelease} publisher is
   * handed.
   *
   * @param version the stamp, taken once at step 4 and threaded through
   * @param commitSha the single merge commit carrying both the merge and the bump
   * @param branch the source branch that was integrated — the merge's parents record it as a sha,
   *     never as a name
   * @param publishedAt when the push was accepted
   */
  public record PublishedRelease(
      String version, String commitSha, String branch, Instant publishedAt) {}

  /**
   * Run the flow for one repository.
   *
   * @param workspaceRowId names the worktree; only its uniqueness is used
   * @throws IntegrateConflictException for every refusal a caller can act on — see {@link
   *     IntegrateConflictException.Reason}
   */
  public PublishedRelease integrate(
      String repoId, String sourceBranch, String targetBranch, String summary, Long workspaceRowId) {
    Path originPath = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    if (!Files.exists(originPath)) {
      throw new NotFoundException("Repository origin not found on disk");
    }

    // [1] preflight, in the bare's object store — nothing is checked out and no ref moves.
    requireNotAlreadyIntegrated(originPath, sourceBranch, targetBranch);
    preflightMerge(originPath, sourceBranch, targetBranch);

    // [2] a DETACHED worktree: the property that makes "no partial state" true rather than hoped.
    Path worktree = prepareWorktree(originPath, repoId, targetBranch, workspaceRowId);
    try {
      // [3] the merge, staged but NOT committed — MERGE_HEAD stays set and the index stays open,
      // which is what lets the bump write into the same index and produce ONE commit.
      mergeNoCommit(worktree, sourceBranch);

      // [4] the stamp, taken ONCE. Recomputing it per file would let a slow bump write two versions
      // into one commit.
      String version = VersionStamp.of(Instant.now());

      // [5] the bump, into the open index.
      stageBump(worktree, version);

      // [6] one commit: two parents (the merge) plus the version change.
      String commitSha = commitRelease(worktree, version, summary, sourceBranch);

      // [7] the push, which is the compare-and-swap.
      Instant publishedAt = push(worktree, repoId, targetBranch);

      LOG.infof(
          "integrated %s into %s of %s as %s (%s)",
          sourceBranch, targetBranch, repoId, version, commitSha);
      return new PublishedRelease(version, commitSha, sourceBranch, publishedAt);
    } finally {
      // [9] cleanup. In a finally because every failure above leaves the worktree behind and the
      // NEXT integrate is what pays for it.
      removeWorktree(originPath, worktree);
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
  private void requireNotAlreadyIntegrated(Path originPath, String source, String target) {
    try {
      GitExecutor.ExecResult ancestor =
          git.execAllowNonZero(
              originPath.toFile(), "git", "merge-base", "--is-ancestor", "--end-of-options", source, target);
      if (ancestor.exitCode() == 0) {
        throw new IntegrateConflictException(
            Reason.ALREADY_INTEGRATED,
            "Branch '"
                + source
                + "' is already integrated into '"
                + target
                + "': it is an ancestor of it, so there is nothing to release.");
      }
    } catch (IntegrateConflictException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to compare '" + source + "' with '" + target + "': " + e.getMessage());
    }
  }

  /**
   * The real three-way merge, in the object store, with no working tree involved ({@code merge-tree
   * --write-tree}). It exits 1 to report conflicts, which is an answer rather than a failure — hence
   * {@code execAllowNonZero}. Catching the common case here means the usual conflict costs no
   * worktree at all; step 3 is the backstop for the rest.
   */
  private void preflightMerge(Path originPath, String source, String target) {
    GitExecutor.ExecResult result;
    try {
      result =
          git.execAllowNonZero(
              originPath.toFile(),
              "git",
              "merge-tree",
              "--write-tree",
              "--name-only",
              "--end-of-options",
              target,
              source);
    } catch (Exception e) {
      throw new InternalServerErrorException("Failed to preflight the merge: " + e.getMessage());
    }
    if (result.exitCode() == 0) {
      return;
    }
    if (result.exitCode() == 1) {
      throw new IntegrateConflictException(
          Reason.CONFLICT,
          "Merging '" + source + "' into '" + target + "' conflicts. Nothing was released and '"
              + target
              + "' is unchanged.",
          GitExecutor.conflictedFiles(result.output()));
    }
    throw new InternalServerErrorException(
        "Failed to preflight the merge [" + result.exitCode() + "]: " + result.output());
  }

  // ---------------------------------------------------------------------------------------------
  // [2] the detached worktree
  // ---------------------------------------------------------------------------------------------

  private Path prepareWorktree(Path originPath, String repoId, String target, Long workspaceRowId) {
    Path worktree =
        Path.of(dataDir, repoId, "workspaces", ".tmp-integrate-" + workspaceRowId).toAbsolutePath();
    try {
      Files.createDirectories(worktree.getParent());
      // Prune first: a crashed integrate leaves the registration behind and `worktree add` then
      // refuses forever. Prune drops the registration but never the files, so a surviving directory
      // is removed by hand and the registration re-pruned.
      git.exec(originPath.toFile(), "git", "worktree", "prune");
      if (Files.exists(worktree)) {
        deleteRecursively(worktree);
        git.exec(originPath.toFile(), "git", "worktree", "prune");
      }
      // --detach is the whole safety property: no branch ref exists in this worktree, so nothing
      // between here and the push can move one.
      git.exec(
          originPath.toFile(),
          "git",
          "worktree",
          "add",
          "--detach",
          worktree.toString(),
          "refs/heads/" + target);
      return worktree;
    } catch (Exception e) {
      throw new InternalServerErrorException(
          "Failed to prepare the integrate worktree: " + e.getMessage());
    }
  }

  private void removeWorktree(Path originPath, Path worktree) {
    try {
      git.execAllowNonZero(
          originPath.toFile(), "git", "worktree", "remove", "--force", worktree.toString());
    } catch (Exception e) {
      LOG.warnf(e, "failed to remove the integrate worktree at %s", worktree);
    }
    try {
      if (Files.exists(worktree)) {
        deleteRecursively(worktree);
      }
      git.execAllowNonZero(originPath.toFile(), "git", "worktree", "prune");
    } catch (Exception e) {
      LOG.warnf(e, "failed to clean up the integrate worktree at %s", worktree);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [3] merge, [5] bump, [6] commit
  // ---------------------------------------------------------------------------------------------

  private void mergeNoCommit(Path worktree, String source) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(gitIdentity.inlineArgs());
    argv.addAll(List.of("merge", "--no-ff", "--no-commit", "--no-edit", "--end-of-options", source));
    GitExecutor.ExecResult result;
    try {
      result =
          git.execAllowNonZero(
              worktree.toFile(), gitIdentity.envMap(), null, argv.toArray(String[]::new));
    } catch (Exception e) {
      throw new InternalServerErrorException("Git merge failed: " + e.getMessage());
    }
    if (result.exitCode() == 0) {
      return;
    }
    // The backstop for whatever merge-tree did not see. Abort so the worktree is removable, then
    // report the same 409 the preflight would have — never the 500 today's merge produces, where
    // git's non-zero exit becomes an exception and the conflict flag is dead code.
    List<String> conflicts = unmergedPaths(worktree);
    try {
      git.execAllowNonZero(worktree.toFile(), "git", "merge", "--abort");
    } catch (Exception ignored) {
      // the worktree is removed in the caller's finally either way
    }
    throw new IntegrateConflictException(
        Reason.MERGE_CONFLICT,
        "Merging '" + source + "' conflicts. Nothing was released and the target branch is"
            + " unchanged.",
        conflicts);
  }

  /** The conflicted paths of a stopped merge, straight out of the index. */
  private List<String> unmergedPaths(Path worktree) {
    try {
      String out =
          git.exec(worktree.toFile(), "git", "diff", "--name-only", "--diff-filter=U");
      return out.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  /**
   * Writes the version into the worktree and stages it into the <b>same</b> index the merge left
   * open. A repository with no version files is still a release: the bump reports nothing changed,
   * nothing is added, and the commit below is identical in every other way. The commit is the
   * release; the files are one stack's rendering of it.
   */
  private void stageBump(Path worktree, String version) {
    VersionBumper.BumpResult bump = bumper.bump(worktree, version);
    if (bump.changedFiles().isEmpty()) {
      return;
    }
    List<String> argv = new ArrayList<>(List.of("git", "add", "--"));
    bump.changedFiles().forEach(p -> argv.add(p.toString()));
    try {
      git.exec(worktree.toFile(), argv.toArray(String[]::new));
    } catch (Exception e) {
      throw new InternalServerErrorException("Failed to stage the version bump: " + e.getMessage());
    }
  }

  private String commitRelease(Path worktree, String version, String summary, String source) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(gitIdentity.inlineArgs());
    argv.addAll(
        List.of(
            "commit",
            "-m",
            "release(" + version + "): " + summary,
            // The merge's parents record the graph, but the branch NAME does not survive the merge
            // otherwise, and it is what a human reads.
            "-m",
            "Integrates workspace branch `" + source + "`."));
    try {
      git.exec(worktree.toFile(), gitIdentity.envMap(), argv.toArray(String[]::new));
      return git.exec(worktree.toFile(), "git", "rev-parse", "HEAD").trim();
    } catch (Exception e) {
      throw new InternalServerErrorException("Failed to commit the release: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------------------------
  // [7] the push
  // ---------------------------------------------------------------------------------------------

  private Instant push(Path worktree, String repoId, String target) {
    String remote = gitHost.pushUrl(repoId);
    GitExecutor.ExecResult result;
    try {
      result =
          git.execAllowNonZero(
              worktree.toFile(),
              Duration.ofMillis(pushTimeoutMs),
              Map.of(),
              null,
              "git",
              "push",
              "--porcelain",
              "--push-option=" + RELEASE_PUSH_OPTION,
              remote,
              "HEAD:refs/heads/" + target);
    } catch (TimeoutException e) {
      throw new InternalServerErrorException(
          "The release push to " + remote + " timed out; nothing was released.");
    } catch (Exception e) {
      throw new InternalServerErrorException("The release push failed: " + e.getMessage());
    }
    if (result.exitCode() == 0) {
      return Instant.now();
    }
    throw classifyPushFailure(result.output(), target);
  }

  /**
   * Reads a rejected push for what it was.
   *
   * <p>Order matters: a hook refusal and a non-fast-forward are both "[…rejected]" lines and only
   * the {@code remote} marker tells them apart, so the remote one is matched first. A push the git
   * host's protection hook declined must surface as a <b>4xx carrying the hook's own message</b> —
   * never a 500 — because that message is the only thing on screen that says what to do instead.
   */
  private RuntimeException classifyPushFailure(String output, String target) {
    String remoteRefusal = remoteRejection(output);
    if (remoteRefusal != null) {
      return new IntegrateConflictException(
          Reason.PUSH_REJECTED, "The git host refused the release push: " + remoteRefusal);
    }
    String lower = output == null ? "" : output.toLowerCase();
    if (lower.contains("non-fast-forward")
        || lower.contains("fetch first")
        || lower.contains("[rejected]")) {
      return new IntegrateConflictException(
          Reason.NOT_FAST_FORWARD,
          "'"
              + target
              + "' moved while this release was being built, so the push was not a fast-forward."
              + " Nothing was released — integrate again.");
    }
    return new InternalServerErrorException(
        "The release push failed: " + (output == null || output.isBlank() ? "no output" : output));
  }

  /**
   * The git host's own words out of a {@code [remote rejected]} line. {@code git push} renders the
   * reason in trailing parentheses; the whole line is the fallback, because a refusal a human cannot
   * read is worse than a verbose one.
   */
  private static String remoteRejection(String output) {
    if (output == null) {
      return null;
    }
    for (String line : output.split("\n")) {
      if (!line.contains("[remote rejected]") && !line.contains("[remote failure]")) {
        continue;
      }
      int open = line.indexOf('(');
      int close = line.lastIndexOf(')');
      if (open >= 0 && close > open) {
        return line.substring(open + 1, close).trim();
      }
      return line.trim();
    }
    return null;
  }
}
