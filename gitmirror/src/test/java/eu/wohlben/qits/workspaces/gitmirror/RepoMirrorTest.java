package eu.wohlben.qits.workspaces.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mirror's lifecycle and the pushes that replaced every ref write this service used to perform
 * on a shared volume.
 *
 * <p>Every case runs against a real bare and a real {@code git}, with no Quarkus, no database and no
 * network. What it is proving is not "git works" but that the substitution holds: that a fetch
 * really does bring the host's refs, that {@code ls-remote} answers a question a stale mirror would
 * answer wrongly, and that a branch created here shows up in the served repository as a
 * <b>received</b> ref rather than as a file somebody wrote.
 */
class RepoMirrorTest {

  private static final CommitIdentity QITS = new CommitIdentity("qits", "qits@local");

  @TempDir Path tmp;

  private LocalBares remotes;
  private GitMirrors mirrors;
  private Path bare;
  private final String repoId = "repo-under-test";

  @BeforeEach
  void setUp() throws Exception {
    bare = TestBare.create(tmp, "origin");
    remotes = new LocalBares();
    remotes.register(repoId, bare);
    mirrors =
        new GitMirrors(
            new GitCli(),
            remotes,
            tmp.resolve("workspaces-data"),
            Duration.ofSeconds(60),
            Duration.ZERO);
  }

  // -----------------------------------------------------------------------------------------
  // the lifecycle
  // -----------------------------------------------------------------------------------------

  @Test
  void aFirstRefreshClonesTheMirrorAndASecondOneOnlyFetches() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    assertFalse(Files.exists(mirror.gitDir()), "nothing is cloned until something needs objects");

    mirror.refreshNow();

    assertTrue(Files.isDirectory(mirror.gitDir()));
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
    assertEquals(
        TestBare.refIn(bare, "feature"), mirror.resolve("refs/heads/feature").orElseThrow());

    // A commit lands on the host afterwards; the mirror learns it on the next refresh and not
    // before. That "and not before" is the freshness contract every read here depends on.
    TestBare.commitOnBranch(bare, "main", "later.txt", "later\n", "a later commit");
    assertNotEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());

    mirror.refreshNow();
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
  }

  @Test
  void aFetchPrunesBranchesTheHostNoLongerHas() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    assertTrue(mirror.resolve("refs/heads/feature").isPresent());

    TestBare.output(bare.toFile(), "git", "branch", "-D", "feature");
    mirror.refreshNow();

    assertTrue(
        mirror.resolve("refs/heads/feature").isEmpty(),
        "a mirror that kept deleted branches would report ahead/behind against a ghost");
  }

  @Test
  void theFreshnessWindowIsWhatSkipsAFetchAndAPushClearsIt() throws Exception {
    GitMirrors windowed =
        new GitMirrors(
            new GitCli(),
            remotes,
            tmp.resolve("windowed-data"),
            Duration.ofSeconds(60),
            Duration.ofMinutes(10));
    RepoMirror mirror = windowed.of(repoId);
    mirror.refreshNow();
    int afterClone = remotes.fetchUrlCalls.get();

    mirror.refresh();
    assertEquals(afterClone, remotes.fetchUrlCalls.get(), "inside the window nothing is fetched");

    // A push is the one thing that certainly invalidated the mirror, so it clears the window.
    mirror.createBranch("windowed", "main");
    mirror.refresh();
    assertTrue(remotes.fetchUrlCalls.get() > afterClone, "an accepted push makes the mirror stale");
  }

  @Test
  void twoThreadsRefreshingOneMirrorProduceOneUsableMirror() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Void>> calls =
          List.of(
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              },
              () -> {
                mirror.refreshNow();
                return null;
              });
      for (Future<Void> result : pool.invokeAll(calls)) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }
    assertEquals(TestBare.refIn(bare, "main"), mirror.resolve("refs/heads/main").orElseThrow());
  }

  // -----------------------------------------------------------------------------------------
  // wire reads
  // -----------------------------------------------------------------------------------------

  @Test
  void lsRemoteAnswersFromTheHostEvenWhenTheMirrorIsStale() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    TestBare.output(bare.toFile(), "git", "branch", "made-outside", "main");

    assertTrue(
        mirror.remoteHasBranch("made-outside"),
        "existence is decided by the repository of record — a cache saying 'gone' abandons a"
            + " workspace");
    assertTrue(mirror.resolve("refs/heads/made-outside").isEmpty(), "and the mirror is still stale");
    assertEquals(
        TestBare.refIn(bare, "main"), mirror.remoteBranchSha("main").orElseThrow());
    assertTrue(mirror.remoteBranchSha("no-such-branch").isEmpty());
    assertTrue(mirror.remoteBranches().containsAll(List.of("main", "feature", "made-outside")));
  }

  @Test
  void aReadNeverConsumesThePushUrl() {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    mirror.remoteBranches();
    mirror.remoteBranchSha("main");
    assertEquals(
        0,
        remotes.pushUrlCalls.get(),
        "pushUrl is asked once per push, which is the instant a staged race is about");
  }

  // -----------------------------------------------------------------------------------------
  // local reads
  // -----------------------------------------------------------------------------------------

  @Test
  void theLocalReadsAnswerAheadBehindAncestryAndConflicts() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();

    AheadBehind ab = mirror.aheadBehind("refs/heads/main", "refs/heads/feature");
    assertEquals(1, ab.ahead());
    assertEquals(1, ab.behind());
    assertEquals(AheadBehind.UNKNOWN, mirror.aheadBehind("refs/heads/main", "refs/heads/nope"));

    assertFalse(mirror.isAncestor("refs/heads/feature", "refs/heads/main"));
    assertTrue(mirror.isAncestor("refs/heads/main", "refs/heads/main"));

    assertTrue(mirror.previewMerge("refs/heads/main", "refs/heads/feature").clean());

    // A real conflict, reported as an answer with its file list rather than as a failure.
    TestBare.commitOnBranch(bare, "main", "shared.txt", "ours\n", "our edit");
    TestBare.commitOnBranch(bare, "feature", "shared.txt", "theirs\n", "their edit");
    mirror.refreshNow();
    MergeOutcome preview = mirror.previewMerge("refs/heads/main", "refs/heads/feature");
    assertFalse(preview.clean());
    assertEquals(List.of("shared.txt"), preview.conflictedPaths());
  }

  // -----------------------------------------------------------------------------------------
  // writes
  // -----------------------------------------------------------------------------------------

  @Test
  void creatingAndDeletingABranchAreBothPushes() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();

    assertTrue(mirror.createBranch("task/one", "main").accepted());
    assertEquals(
        TestBare.refIn(bare, "main"),
        TestBare.refIn(bare, "task/one"),
        "the new branch is in the SERVED repository, put there by receive-pack");

    assertTrue(mirror.deleteBranch("task/one").accepted());
    assertFalse(TestBare.refs(bare).contains("refs/heads/task/one"));
  }

  @Test
  void aReleaseIsAWorktreeMergeACommitATagAndOneAtomicPush() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String mainBefore = TestBare.refIn(bare, "main");
    String sourceTip = TestBare.refIn(bare, "feature");

    String sha;
    try (MirrorWorktree worktree = mirror.worktree("feature", "refs/heads/main")) {
      assertTrue(worktree.mergeNoCommit("refs/heads/feature", QITS).clean());
      Files.writeString(worktree.path().resolve("VERSION"), "2026.801.120000\n");
      worktree.stage(List.of(Path.of("VERSION")));
      sha = worktree.commit("release(2026.801.120000): a fixture release", "body", QITS);
      TagOutcome tagged = worktree.tag("2026.801.120000", "release(2026.801.120000): a fixture release", QITS);
      assertTrue(tagged.created());

      PushOutcome pushed =
          worktree.push(
              PushSpec.of(
                      PushSpec.Ref.branch("HEAD", "main"),
                      PushSpec.Ref.tag("refs/tags/2026.801.120000", "2026.801.120000"))
                  .withOption("qits.release")
                  .asAtomic());
      assertTrue(pushed.accepted(), pushed.output());
    }

    assertEquals(sha, TestBare.refIn(bare, "main"));
    assertEquals(
        List.of(sha, mainBefore, sourceTip),
        List.of(
            TestBare.output(bare.toFile(), "git", "rev-list", "--parents", "-n", "1", "main")
                .trim()
                .split(" ")),
        "one commit, two parents, carrying the merge and the bump");
    assertEquals("tag", TestBare.output(bare.toFile(), "git", "cat-file", "-t",
        TestBare.refIn(bare, "2026.801.120000")).trim());
    assertEquals(sha, TestBare.refIn(bare, "2026.801.120000^{commit}"));
    assertFalse(Files.exists(mirrors.root().resolve("worktrees").resolve(repoId).resolve("feature")),
        "the worktree is gone, on every path, because close() is what removes it");
  }

  /**
   * The version-uniqueness guarantee at its cheapest point. The tag exists on the host, the mirror
   * fetched it, and {@code git tag -a} refuses the name here — before this run has pushed anything.
   */
  @Test
  void aTagNameTheHostAlreadyHasIsRefusedInTheMirror() throws Exception {
    TestBare.output(bare.toFile(), "git", "tag", "2026.801.120000", "main");
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();

    try (MirrorWorktree worktree = mirror.worktree("dup", "refs/heads/main")) {
      assertTrue(worktree.mergeNoCommit("refs/heads/feature", QITS).clean());
      worktree.commit("release(2026.801.120000): twice", "body", QITS);
      TagOutcome tagged = worktree.tag("2026.801.120000", "subject", QITS);
      assertFalse(tagged.created());
      assertTrue(tagged.alreadyExists());
    }
    assertEquals(
        TestBare.refIn(bare, "main^{commit}"),
        TestBare.refIn(bare, "2026.801.120000^{commit}"),
        "the host's tag is untouched — the flow refused before it pushed anything");
  }

  /** A refused branch takes the tag down with it, which is the whole reason the push is atomic. */
  @Test
  void anAtomicPushRefusedOnTheBranchLeavesNoTagOnTheHost() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    // The source forks off main, so the release commit descends from main and from it — and from
    // `feature`, which diverged earlier, it descends from neither. That is what makes the move below
    // a real second writer rather than something this push could fast-forward over.
    assertTrue(mirror.createBranch("work", "main").accepted());
    TestBare.commitOnBranch(bare, "work", "mine.txt", "mine\n", "my work");
    mirror.refreshNow();

    try (MirrorWorktree worktree = mirror.worktree("loser", "refs/heads/main")) {
      assertTrue(worktree.mergeNoCommit("refs/heads/work", QITS).clean());
      worktree.commit("release(2026.801.130000): the loser", "body", QITS);
      assertTrue(worktree.tag("2026.801.130000", "subject", QITS).created());

      // A second writer moves main somewhere this commit cannot fast-forward over.
      TestBare.output(bare.toFile(), "git", "branch", "-f", "main", "feature");

      PushOutcome pushed =
          worktree.push(
              PushSpec.of(
                      PushSpec.Ref.branch("HEAD", "main"),
                      PushSpec.Ref.tag("refs/tags/2026.801.130000", "2026.801.130000"))
                  .withOption("qits.release")
                  .asAtomic());
      assertFalse(pushed.accepted());
      assertTrue(pushed.saysNotFastForward());
    }
    assertFalse(
        TestBare.refs(bare).contains("refs/tags/"),
        "without --atomic the tag lands even when the branch is refused — measured");
  }

  @Test
  void aPlainMergeCommitsInTheWorktreeAndReachesTheHostByPush() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    assertTrue(mirror.createBranch("epic", "main").accepted());
    TestBare.commitOnBranch(bare, "epic", "epic.txt", "epic\n", "epic work");
    mirror.refreshNow();

    String sha;
    try (MirrorWorktree worktree = mirror.worktree("feature", "refs/heads/epic")) {
      MergeOutcome merged =
          worktree.mergeAndCommit("refs/heads/feature", "Merge feature into epic", QITS);
      assertTrue(merged.clean(), merged.output());
      sha = worktree.headSha();
      assertTrue(worktree.push(PushSpec.of(PushSpec.Ref.branch("HEAD", "epic"))).accepted());
    }
    assertEquals(sha, TestBare.refIn(bare, "epic"));
  }

  @Test
  void aConflictedWorktreeMergeIsAnAnswerAndLeavesTheWorktreeRemovable() throws Exception {
    TestBare.commitOnBranch(bare, "main", "shared.txt", "ours\n", "our edit");
    TestBare.commitOnBranch(bare, "feature", "shared.txt", "theirs\n", "their edit");
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    String mainBefore = TestBare.refIn(bare, "main");

    try (MirrorWorktree worktree = mirror.worktree("clash", "refs/heads/main")) {
      MergeOutcome merged = worktree.mergeNoCommit("refs/heads/feature", QITS);
      assertFalse(merged.clean());
      assertEquals(List.of("shared.txt"), merged.conflictedPaths());
    }
    assertEquals(mainBefore, TestBare.refIn(bare, "main"), "a conflict pushes nothing");
  }

  /**
   * A crashed run leaves its worktree registered, and the next one used to fail forever with
   * "already checked out". The prune is what makes a second run of the same branch possible.
   */
  @Test
  void aLeftoverWorktreeIsPrunedRatherThanInherited() throws Exception {
    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    MirrorWorktree abandoned = mirror.worktree("task/one", "refs/heads/main");
    assertTrue(Files.isDirectory(abandoned.path()));

    // No close(): exactly what a crash leaves behind.
    try (MirrorWorktree next = mirror.worktree("task/one", "refs/heads/main")) {
      assertTrue(Files.isDirectory(next.path()));
      assertEquals(TestBare.refIn(bare, "main"), next.headSha());
    }
  }
}
