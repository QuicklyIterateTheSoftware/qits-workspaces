package eu.wohlben.qits.workspaces.gitmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The spawn point's own properties: the per-line tap fires as the command runs and returns the same
 * blob the untapped call does, the final unterminated line is not dropped, and every spawn disables
 * git's terminal prompting.
 *
 * <p>These assertions used to live on {@code domain}'s {@code GitExecutorTest} and needed a whole
 * {@code @QuarkusTest} to reach a CDI bean that only delegated here. The behaviour was always this
 * class's; the test is here now, and runs offline in milliseconds like the rest of this module's.
 */
class GitCliTest {

  private final GitCli cli = new GitCli();

  @Test
  void theTapSeesEveryLineAndTheReturnedBlobIsTheSame() throws Exception {
    List<String> tapped = new ArrayList<>();
    GitCli.Result streamed = cli.run(null, null, tapped::add, null, "sh", "-c", "printf 'a\\nb\\nc\\n'");

    // Every line reached the tap, in order — the live per-line delivery the segment stream relies on.
    assertEquals(List.of("a", "b", "c"), tapped);
    // …and the returned blob is identical to the same command run without a tap.
    GitCli.Result blocking = cli.run(null, null, null, null, "sh", "-c", "printf 'a\\nb\\nc\\n'");
    assertEquals(blocking.output(), streamed.output());
    assertEquals("a\nb\nc", streamed.output());
  }

  @Test
  void theFinalUnterminatedLineIsStillTapped() throws Exception {
    List<String> tapped = new ArrayList<>();
    // No trailing newline — readLine must still yield "z" so the last line isn't dropped.
    cli.run(null, null, tapped::add, null, "sh", "-c", "printf 'x\\ny\\nz'");
    assertEquals(List.of("x", "y", "z"), tapped);
  }

  @Test
  void everySpawnDisablesGitTerminalPrompting() throws Exception {
    // GIT_TERMINAL_PROMPT=0 rides every spawned process: a transport that would prompt for
    // credentials fails immediately (classifiable exit 128) instead of blocking waitFor() forever.
    GitCli.Result result =
        cli.run(null, null, null, null, "sh", "-c", "printf '%s' \"$GIT_TERMINAL_PROMPT\"");
    assertEquals("0", result.output());
  }

  @Test
  void aNonZeroExitIsReportedWithTheCapturedOutput() throws Exception {
    // The exit code is an answer here, not a failure — `git merge-tree` exits 1 to report conflicts.
    // Turning it into an exception is the caller's choice, so this class never makes it.
    GitCli.Result result = cli.run(null, null, line -> {}, null, "sh", "-c", "echo boom; exit 3");
    assertEquals(3, result.exitCode());
    assertEquals("boom", result.output());
  }
}
