package eu.wohlben.qits.workspaces.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a push carries so the git host can publish its SCM events under the cause that produced them.
 *
 * <p>Two claims, and they are deliberately different in kind. The first is the exact {@code -c}
 * value and every way an id can be refused — a pure function, so it is driven directly. The second
 * is that a push made with a cause in scope still <b>works</b>: the extra tokens go in front of
 * {@code push} where git expects them, and every ref this service moves is moved by a push, so that
 * is either right or it is every release, integrate, branch create and cleanup broken at once.
 *
 * <p>What is <em>not</em> here is the header arriving at the far end. These fixtures are local
 * bares, and {@code http.extraHeader} is by definition inert over a file transport. That claim
 * belongs to qits-githost, which reads the header off a real receive-pack request.
 */
class PushCausationHeaderTest {

  @TempDir Path tmp;

  @Test
  void aCauseBecomesTheExtraHeaderGitSendsWithTheRequest() {
    UUID cause = UUID.randomUUID();

    assertEquals(
        Optional.of("http.extraHeader=X-Qits-Causation-Id: " + cause),
        RepoMirror.causeHeaderFor(cause.toString()));
  }

  @Test
  void noCauseIsNoHeaderRatherThanAnEmptyOne() {
    assertEquals(Optional.empty(), RepoMirror.causeHeaderFor(null));
    assertEquals(Optional.empty(), RepoMirror.causeHeaderFor(""));
    assertEquals(Optional.empty(), RepoMirror.causeHeaderFor("   "));
  }

  /**
   * The value is interpolated into an HTTP header, so anything that is not an id is dropped rather
   * than sent. A cause is advisory — a release must never fail for want of one — and the newline
   * case is the one that would be header injection rather than an oddity.
   */
  @Test
  void anIdThatIsNotAUuidIsDroppedRatherThanInterpolated() {
    assertEquals(Optional.empty(), RepoMirror.causeHeaderFor("not-an-id"));
    assertEquals(
        Optional.empty(),
        RepoMirror.causeHeaderFor("00000000-0000-0000-0000-000000000000\r\nX: y"));
  }

  @Test
  void aPushUnderACauseStillReachesTheHost() throws Exception {
    Path bare = TestBare.create(tmp, "origin");
    LocalBares remotes = new LocalBares();
    String repoId = "caused-repo";
    remotes.register(repoId, bare);
    GitMirrors mirrors =
        new GitMirrors(
            new GitCli(),
            remotes,
            tmp.resolve("workspaces-data"),
            Duration.ofSeconds(60),
            Duration.ZERO,
            () -> UUID.randomUUID().toString());

    RepoMirror mirror = mirrors.of(repoId);
    mirror.refreshNow();
    PushOutcome outcome = mirror.createBranch("caused", "main");

    assertTrue(outcome.accepted(), outcome.output());
    assertEquals(TestBare.refIn(bare, "main"), TestBare.refIn(bare, "caused"));
  }
}
