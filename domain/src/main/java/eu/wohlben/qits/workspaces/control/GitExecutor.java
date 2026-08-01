package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.gitmirror.GitCli;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Running git <b>inside a container's checkout, or in a throwaway directory a test built</b> — the
 * calls that are about a working tree this service happens to hold.
 *
 * <p>It no longer runs anything against the repositories volume. Every operation on a repository —
 * a ref read, a merge, a branch create, a release — goes through {@code qits-workspaces-gitmirror},
 * which mirrors the repository locally and reaches the served one only by pushing. What is left here
 * is the process spawning those two need in common, and this class now delegates it to the module's
 * {@link GitCli} rather than owning a second copy of it.
 */
@ApplicationScoped
public class GitExecutor {

  private final GitCli cli = new GitCli();

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

  /**
   * The one spawn point. Delegates to {@link GitCli}, which the gitmirror module owns: two
   * implementations of "run git with a deadline and drain its output" would be two chances to fix a
   * bug once, and the mirror's every wire call needs the same machinery this one does.
   */
  private ExecResult run(
      java.io.File cwd,
      Map<String, String> env,
      Consumer<String> onLine,
      Duration timeout,
      String... command)
      throws Exception {
    GitCli.Result result = cli.run(cwd, env, onLine, timeout, command);
    return new ExecResult(result.exitCode(), result.output());
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
