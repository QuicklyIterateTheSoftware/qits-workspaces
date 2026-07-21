package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The streaming {@code exec(cwd, onLine, command...)} overload: it invokes {@code onLine} per line
 * as the command runs while returning the same full output as the blocking {@link
 * GitExecutor#exec}, and a non-zero exit still throws with the captured output.
 */
@QuarkusTest
class GitExecutorTest {

  @Inject GitExecutor git;

  @Test
  void theStreamingOverloadTapsEachLineAndReturnsTheSameOutputAsTheBlockingExec() throws Exception {
    List<String> tapped = new ArrayList<>();
    String streamed = git.exec(null, line -> tapped.add(line), "sh", "-c", "printf 'a\\nb\\nc\\n'");

    // Every line reached the tap, in order — the live per-line delivery the segment stream relies
    // on.
    assertEquals(List.of("a", "b", "c"), tapped);
    // …and the returned blob is identical to the plain blocking exec of the same command.
    String blocking = git.exec(null, "sh", "-c", "printf 'a\\nb\\nc\\n'");
    assertEquals(blocking, streamed);
    assertEquals("a\nb\nc", streamed);
  }

  @Test
  void theFinalUnterminatedLineIsStillTapped() throws Exception {
    List<String> tapped = new ArrayList<>();
    // No trailing newline — readLine must still yield "z" so the last line isn't dropped.
    git.exec(null, tapped::add, "sh", "-c", "printf 'x\\ny\\nz'");
    assertEquals(List.of("x", "y", "z"), tapped);
  }

  @Test
  void everySpawnDisablesGitTerminalPrompting() throws Exception {
    // GIT_TERMINAL_PROMPT=0 rides every spawned process: a transport that would prompt for
    // credentials fails immediately (classifiable exit 128) instead of blocking waitFor() forever.
    String value = git.exec(null, "sh", "-c", "printf '%s' \"$GIT_TERMINAL_PROMPT\"");
    assertEquals("0", value);
  }

  @Test
  void aNonZeroExitStillThrowsWithTheCapturedOutput() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> git.exec(null, line -> {}, "sh", "-c", "echo boom; exit 3"));
    assertTrue(thrown.getMessage().contains("boom"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("[3]"), thrown.getMessage());
  }
}
