package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
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
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd);
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      if (onLine == null) {
        output = reader.lines().collect(Collectors.joining("\n"));
      } else {
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
        output = collected.toString();
      }
    }
    int exitCode = p.waitFor();
    return new ExecResult(exitCode, output);
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
