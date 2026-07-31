package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class GitExecutor {

  /** The exit code and combined stdout/stderr of a finished git invocation. */
  public record ExecResult(int exitCode, String output) {}

  public String exec(java.io.File cwd, String... command) throws Exception {
    return exec(cwd, (Consumer<String>) null, command);
  }

  /**
   * {@link #exec(java.io.File, String...)} with an environment overlay applied to the git process —
   * used for the synthetic-commit call sites, which set the {@code GIT_AUTHOR_*}/{@code
   * GIT_COMMITTER_*} identity here so it beats any ambient identity env inherited from the host
   * (env outranks {@code -c} config, so the inline args alone can't guarantee attribution).
   */
  public String exec(java.io.File cwd, Map<String, String> env, String... command)
      throws Exception {
    ExecResult result = execAllowNonZero(cwd, env, (Consumer<String>) null, command);
    if (result.exitCode() != 0) {
      throw new RuntimeException(
          "Command failed ["
              + result.exitCode()
              + "]: "
              + String.join(" ", command)
              + "\n"
              + result.output());
    }
    return result.output();
  }

  /**
   * {@link #exec(java.io.File, String...)} with a per-line tap for the technical-process log
   * stream: {@code onLine} is invoked as each merged stdout/stderr line arrives (so a slow {@code
   * git fetch} streams progress live and keeps the process's idle reaper at bay), while the full
   * output is still accumulated and returned. Mirrors {@code ContainerRuntime.exec(..., onLine,
   * ...)}; passing a null {@code onLine} is the plain blocking behaviour.
   */
  public String exec(java.io.File cwd, Consumer<String> onLine, String... command)
      throws Exception {
    ExecResult result = execAllowNonZero(cwd, onLine, command);
    if (result.exitCode() != 0) {
      throw new RuntimeException(
          "Command failed ["
              + result.exitCode()
              + "]: "
              + String.join(" ", command)
              + "\n"
              + result.output());
    }
    return result.output();
  }

  /**
   * Runs git and returns the exit code alongside the output instead of throwing on a non-zero exit.
   * Use this for commands whose non-zero exit is a meaningful answer rather than a failure — e.g.
   * {@code git merge-tree}, which exits 1 to report merge conflicts.
   */
  public ExecResult execAllowNonZero(java.io.File cwd, String... command) throws Exception {
    return execAllowNonZero(cwd, (Consumer<String>) null, command);
  }

  /** {@link #execAllowNonZero(java.io.File, String...)} with the per-line tap of {@link #exec}. */
  public ExecResult execAllowNonZero(java.io.File cwd, Consumer<String> onLine, String... command)
      throws Exception {
    return execAllowNonZero(cwd, Map.of(), onLine, command);
  }

  /** {@link #execAllowNonZero(java.io.File, Consumer, String...)} with an environment overlay. */
  public ExecResult execAllowNonZero(
      java.io.File cwd, Map<String, String> env, Consumer<String> onLine, String... command)
      throws Exception {
    return run(cwd, env, onLine, null, command);
  }

  /**
   * {@link #execAllowNonZero(java.io.File, Map, Consumer, String...)} with a wall-clock <b>bound</b>
   * — the overload the integrate flow's push uses, and the only one that has one.
   *
   * <p>Every other call here runs {@code p.waitFor()} with no timeout at all, which is survivable
   * for a local filesystem operation (git either finishes or the disk is gone) and is <b>not</b>
   * survivable for a network push: a wedged git host would pin a request thread forever, and this
   * service answers integrate synchronously. So the push gets a deadline and nothing else changes;
   * widening the bound to the local calls would turn a slow clone into a spurious failure for no
   * gain.
   *
   * <p>The bound covers the <b>whole</b> invocation rather than only the exit status. A transport
   * that accepts the connection and then says nothing blocks in {@code readLine()}, never in {@code
   * waitFor()}, so the output is drained on its own thread and the deadline is enforced against the
   * process — {@code destroyForcibly} closes the pipe, which is what unblocks the drain.
   *
   * @throws java.util.concurrent.TimeoutException when the deadline passes; the process is killed
   *     first, so nothing is left running behind the failure
   */
  public ExecResult execAllowNonZero(
      java.io.File cwd,
      Duration timeout,
      Map<String, String> env,
      Consumer<String> onLine,
      String... command)
      throws Exception {
    return run(cwd, env, onLine, timeout, command);
  }

  private ExecResult run(
      java.io.File cwd,
      Map<String, String> env,
      Consumer<String> onLine,
      Duration timeout,
      String... command)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd);
    }
    // Never let a transport prompt: there is no TTY here and waitFor() has no timeout, so a git
    // that decides to ask for credentials would block forever. With the flag a missing credential
    // is an immediate, classifiable exit 128 ("could not read Username ...") instead.
    pb.environment().put("GIT_TERMINAL_PROMPT", "0");
    // Force English output regardless of the host locale: callers classify failures (e.g.
    // non-fast-forward rejection, auth failure) by matching substrings in git's message, which
    // would silently miss a localized translation.
    pb.environment().put("LC_ALL", "C");
    pb.environment().put("LANG", "C");
    // The caller's overlay last, so a supplied GIT_AUTHOR_*/GIT_COMMITTER_* replaces any ambient
    // identity env inherited from the host (env outranks the -c config the caller also passes).
    env.forEach(pb.environment()::put);
    pb.redirectErrorStream(true);
    Process p = pb.start();
    if (timeout == null) {
      String output = drain(p, onLine);
      return new ExecResult(p.waitFor(), output);
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
      throw new TimeoutException(
          "git timed out after " + timeout + ": " + String.join(" ", command));
    }
    drain.join(DRAIN_JOIN.toMillis());
    return new ExecResult(p.exitValue(), collected.toString());
  }

  /** How long to wait for the drain thread to finish after the process has settled. */
  private static final Duration DRAIN_JOIN = Duration.ofSeconds(5);

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

  /**
   * Reads a file's contents out of a bare repository at a given revision ({@code git show
   * <rev>:<path>}), returning the exit code alongside the output rather than throwing. A non-zero
   * exit means the file is absent at that revision (e.g. no {@code .gitmodules}) — a meaningful
   * answer, not a failure — so callers treat it as "empty" rather than an error.
   */
  public ExecResult showFile(java.io.File bareRepo, String rev, String path) throws Exception {
    return execAllowNonZero(bareRepo, "git", "show", "--end-of-options", rev + ":" + path);
  }

  /**
   * The conflicting paths out of a conflicted {@code merge-tree --write-tree --name-only} output:
   * the lines between the written tree OID and the blank separator before the informational
   * messages.
   */
  public static List<String> conflictedFiles(String mergeTreeOutput) {
    List<String> files = new ArrayList<>();
    String[] lines = mergeTreeOutput.split("\n", -1);
    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        break;
      }
      files.add(lines[i].trim());
    }
    return files;
  }

  public String getCurrentBranch(Path workspacePath) {
    try {
      return exec(workspacePath.toFile(), "git", "branch", "--show-current").trim();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get current branch", e);
    }
  }

  /**
   * The full commit SHA currently checked out in {@code workspacePath} ({@code git rev-parse
   * HEAD}).
   */
  public String getCurrentCommit(Path workspacePath) {
    try {
      return exec(workspacePath.toFile(), "git", "rev-parse", "HEAD").trim();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get current commit", e);
    }
  }
}
