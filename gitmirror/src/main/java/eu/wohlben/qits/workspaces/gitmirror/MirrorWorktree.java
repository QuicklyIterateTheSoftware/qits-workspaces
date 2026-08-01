package eu.wohlben.qits.workspaces.gitmirror;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A detached checkout on the mirror: where a merge is built, a version is written into it, and the
 * one commit that carries both is made.
 *
 * <p>The verbs are typed rather than a raw "run git here" hole, so the caller keeps the <i>order</i>
 * — which is the release flow's actual logic — while this module keeps the argv, the identity
 * plumbing and the reading of git's answers.
 *
 * <p>{@link AutoCloseable} because the removal has to happen on every path, and a {@code finally}
 * that a future edit can forget is how stale worktrees became a platform-wide sharp edge in the
 * first place.
 */
public final class MirrorWorktree implements AutoCloseable {

  private final RepoMirror mirror;
  private final GitCli cli;
  private final Path path;

  MirrorWorktree(RepoMirror mirror, GitCli cli, Path path) {
    this.mirror = mirror;
    this.cli = cli;
    this.path = path;
  }

  /** The checkout's directory — what a version bumper writes into. */
  public Path path() {
    return path;
  }

  /** The commit currently checked out. */
  public String headSha() {
    GitCli.Result result = run(Map.of(), "git", "rev-parse", "HEAD");
    if (result.exitCode() != 0) {
      throw new GitMirrorException("Could not read the worktree's HEAD: " + result.output());
    }
    return result.output().trim();
  }

  /**
   * {@code git merge --no-ff --no-commit}: the merge is staged and <b>not</b> committed, so {@code
   * MERGE_HEAD} stays set and the index stays open. That is what lets a version bump write into the
   * same index and produce ONE commit rather than a merge followed by a bump.
   *
   * <p>A conflict aborts the merge before returning, so the worktree is removable, and comes back as
   * a {@link MergeOutcome} rather than an exception.
   */
  public MergeOutcome mergeNoCommit(String source, CommitIdentity identity) {
    return merge(identity, List.of("merge", "--no-ff", "--no-commit", "--no-edit"), source, null);
  }

  /**
   * {@code git merge -m <message>}: a plain merge that commits itself, fast-forwarding when it can.
   * The shape the branch-merge surface has always had, now landing in a mirror and reaching the
   * repository through {@link #push} instead of by writing the ref.
   */
  public MergeOutcome mergeAndCommit(String source, String message, CommitIdentity identity) {
    return merge(identity, List.of("merge", "--no-edit"), source, message);
  }

  private MergeOutcome merge(
      CommitIdentity identity, List<String> verb, String source, String message) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(identity.inlineArgs());
    argv.addAll(verb);
    if (message != null) {
      argv.add("-m");
      argv.add(message);
    }
    argv.add("--end-of-options");
    argv.add(source);
    GitCli.Result result = run(identity.env(), argv.toArray(String[]::new));
    if (result.exitCode() == 0) {
      return MergeOutcome.clean(result.output());
    }
    List<String> conflicts = unmergedPaths();
    // Abort so the worktree is removable. The caller's failure path only removes the directory.
    run(Map.of(), "git", "merge", "--abort");
    return MergeOutcome.conflicted(conflicts, result.output());
  }

  /** The conflicted paths of a stopped merge, straight out of the index. */
  private List<String> unmergedPaths() {
    GitCli.Result result = run(Map.of(), "git", "diff", "--name-only", "--diff-filter=U");
    if (result.exitCode() != 0) {
      return List.of();
    }
    return result.output().lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
  }

  /** Stage paths into the open index — the bump's changed files, and nothing else. */
  public void stage(List<Path> files) {
    if (files.isEmpty()) {
      return;
    }
    List<String> argv = new ArrayList<>(List.of("git", "add", "--"));
    files.forEach(file -> argv.add(file.toString()));
    GitCli.Result result = run(Map.of(), argv.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new GitMirrorException("Could not stage the version bump: " + result.output());
    }
  }

  /** The one commit, and its sha. */
  public String commit(String subject, String body, CommitIdentity identity) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(identity.inlineArgs());
    argv.addAll(List.of("commit", "-m", subject, "-m", body));
    GitCli.Result result = run(identity.env(), argv.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new GitMirrorException("Could not commit the merge: " + result.output());
    }
    return headSha();
  }

  /**
   * An annotated tag on {@code HEAD}, named exactly {@code name}.
   *
   * <p><b>Plain {@code git tag -a}, and that is the change.</b> This used to be a three-step dance —
   * tag, read the tag object's sha, delete the ref again — for one reason only: the worktree shared
   * the <i>served</i> bare's ref store, so creating the tag published it with no push at all, the
   * push then reported {@code [up to date]} with zero receive commands, and a failed run left a tag
   * behind in a repository other people read. On a mirror none of that is true. The tag is created
   * here, it is pushed by name, and nothing outside this cache has seen it until receive-pack
   * accepts it.
   *
   * <p>A name that is already taken is still the version-uniqueness guarantee firing at its cheapest
   * point, because the mirror was refreshed from the host at the top of the flow: {@link
   * TagOutcome#alreadyExists()} is that refusal, before this run has pushed anything.
   */
  public TagOutcome tag(String name, String message, CommitIdentity identity) {
    List<String> argv = new ArrayList<>(List.of("git"));
    argv.addAll(identity.inlineArgs());
    argv.addAll(List.of("tag", "-a", name, "-m", message, "HEAD"));
    GitCli.Result result = run(identity.env(), argv.toArray(String[]::new));
    if (result.exitCode() == 0) {
      return new TagOutcome(true, false, result.output());
    }
    boolean exists = result.output() != null && result.output().toLowerCase().contains("already exists");
    if (exists) {
      return new TagOutcome(false, true, result.output());
    }
    throw new GitMirrorException("Could not tag the release: " + result.output());
  }

  /** Push from this worktree, so {@code HEAD} in a refspec means the commit just built here. */
  public PushOutcome push(PushSpec spec) {
    return mirror.push(path, spec);
  }

  /** Remove the worktree and re-prune. Every path, which is what {@code AutoCloseable} buys. */
  @Override
  public void close() {
    mirror.removeWorktree(path);
  }

  private GitCli.Result run(Map<String, String> env, String... argv) {
    try {
      return cli.run(path.toFile(), env, null, null, argv);
    } catch (Exception e) {
      throw new GitMirrorException("git " + String.join(" ", argv) + " failed in " + path, e);
    }
  }
}
