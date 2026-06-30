package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.stream.Collectors;

@ApplicationScoped
public class GitExecutor {

  /** The exit code and combined stdout/stderr of a finished git invocation. */
  public record ExecResult(int exitCode, String output) {}

  public String exec(java.io.File cwd, String... command) throws Exception {
    ExecResult result = execAllowNonZero(cwd, command);
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
    ProcessBuilder pb = new ProcessBuilder(command);
    if (cwd != null) {
      pb.directory(cwd);
    }
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String output;
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
      output = reader.lines().collect(Collectors.joining("\n"));
    }
    int exitCode = p.waitFor();
    return new ExecResult(exitCode, output);
  }

  public String getCurrentBranch(Path worktreePath) {
    try {
      return exec(worktreePath.toFile(), "git", "branch", "--show-current").trim();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get current branch", e);
    }
  }

  /**
   * The full commit SHA currently checked out in {@code worktreePath} ({@code git rev-parse HEAD}).
   */
  public String getCurrentCommit(Path worktreePath) {
    try {
      return exec(worktreePath.toFile(), "git", "rev-parse", "HEAD").trim();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get current commit", e);
    }
  }
}
