package eu.wohlben.qits.workspaces.gitmirror;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Spawning a git process, and nothing else.
 *
 * <p>Carved out of {@code GitExecutor} rather than copied: that class is a CDI bean in {@code
 * domain} and this module may not depend on it, but two implementations of "run git with a deadline
 * and drain its output" would be two chances to fix a bug once. {@code GitExecutor} now delegates
 * here and keeps its own convenience overloads, so its behaviour and its tests are unchanged.
 *
 * <p><b>The timeout is for network calls only.</b> A {@code null} timeout runs {@code p.waitFor()}
 * unbounded, which is survivable for a local repository operation — git either finishes or the disk
 * is gone — and is not survivable for a fetch, a push or an {@code ls-remote}, where a wedged host
 * would pin a request thread forever. Every wire call this module makes passes one; every local one
 * does not.
 *
 * <p>The bound covers the <b>whole</b> invocation and not only the exit status. A transport that
 * accepts the connection and then says nothing blocks in {@code readLine()}, never in {@code
 * waitFor()}, so the output is drained on its own thread and the deadline is enforced against the
 * process — {@code destroyForcibly} closes the pipe, which is what unblocks the drain.
 */
public final class GitCli {

  /** The exit code and combined stdout/stderr of a finished git invocation. */
  public record Result(int exitCode, String output) {}

  /** How long to wait for the drain thread to finish after the process has settled. */
  private static final Duration DRAIN_JOIN = Duration.ofSeconds(5);

  /**
   * @param cwd the working directory, or null to inherit this process's
   * @param env an overlay applied last, so a supplied {@code GIT_AUTHOR_*}/{@code GIT_COMMITTER_*}
   *     replaces any ambient identity env inherited from the host (env outranks {@code -c} config)
   * @param onLine an optional per-line tap, invoked as each merged stdout/stderr line arrives
   * @param timeout a wall-clock bound, or null for the unbounded local-operation behaviour
   * @throws TimeoutException when the deadline passes; the process is killed first, so nothing is
   *     left running behind the failure
   */
  public Result run(
      File cwd, Map<String, String> env, Consumer<String> onLine, Duration timeout, String... argv)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv);
    if (cwd != null) {
      pb.directory(cwd);
    }
    // Never let a transport prompt: there is no TTY here and the unbounded overload has no timeout,
    // so a git that decides to ask for credentials would block forever. With the flag a missing
    // credential is an immediate, classifiable exit 128 ("could not read Username ...") instead.
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    // Force English output regardless of the host locale: callers classify failures (e.g.
    // non-fast-forward rejection, a tag that already exists) by matching substrings in git's
    // message, which would silently miss a localized translation.
    pb.environment().put("LC_ALL", "C");
    pb.environment().put("LANG", "C");
    if (env != null) {
      env.forEach(pb.environment()::put);
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    if (timeout == null) {
      String output = drain(p, onLine);
      return new Result(p.waitFor(), output);
    }
    // Bounded: the drain has to run somewhere other than the thread holding the deadline, or a
    // silent-but-open transport parks in readLine() and the deadline is never consulted.
    StringBuilder collected = new StringBuilder();
    Thread drain =
        new Thread(
            () -> {
              try {
                collected.append(drain(p, onLine));
              } catch (Exception ignored) {
                // the pipe closing under a destroyForcibly is the expected end of this thread
              }
            },
            "git-exec-drain");
    drain.setDaemon(true);
    drain.start();
    boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      p.destroyForcibly();
      drain.join(DRAIN_JOIN.toMillis());
      throw new TimeoutException("git timed out after " + timeout + ": " + String.join(" ", argv));
    }
    drain.join(DRAIN_JOIN.toMillis());
    return new Result(p.exitValue(), collected.toString());
  }

  private static String drain(Process p, Consumer<String> onLine) throws Exception {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      if (onLine == null) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
      // Read line by line so the tap sees each line as it arrives (readLine also splits on a bare
      // `\r`, so git's in-place progress updates stream through too, and it yields the final
      // unterminated line so nothing is dropped). Still accumulate the full joined output.
      StringBuilder collected = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        if (collected.length() > 0) {
          collected.append('\n');
        }
        collected.append(line);
        try {
          onLine.accept(line);
        } catch (RuntimeException ignored) {
          // the tap is observational only — a failing sink must not abort the git command
        }
      }
      return collected.toString();
    }
  }
}
