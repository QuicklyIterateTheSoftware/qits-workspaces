package eu.wohlben.qits.domain.repository.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.stream.Collectors;

@ApplicationScoped
public class GitExecutor {

  public String exec(java.io.File cwd, String... command) throws Exception {
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
    if (exitCode != 0) {
      throw new RuntimeException(
          "Command failed [" + exitCode + "]: " + String.join(" ", command) + "\n" + output);
    }
    return output;
  }

  public String getCurrentBranch(Path worktreePath) {
    try {
      return exec(worktreePath.toFile(), "git", "branch", "--show-current").trim();
    } catch (Exception e) {
      throw new RuntimeException("Failed to get current branch", e);
    }
  }
}
