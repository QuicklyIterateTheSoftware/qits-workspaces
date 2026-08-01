package eu.wohlben.qits.workspaces.gitmirror;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * A throwaway bare repository, standing in for one the git host serves.
 *
 * <p>Real git all the way down, deliberately: the whole module is argv and exit codes, so a fake git
 * would test the fake. It costs a handful of processes per test and needs no network, no docker and
 * no Quarkus — which is what keeps this module's suite runnable from a bare clone.
 */
final class TestBare {

  private TestBare() {}

  /** A bare with {@code main} (two commits) and {@code feature}, forked before the second. */
  static Path create(Path parent, String name) throws Exception {
    Path bare = parent.resolve(name + ".git");
    run(parent.toFile(), "git", "init", "--bare", "-q", "-b", "main", bare.toString());
    // Push options are advertised by JGit in production and default to off for a local
    // receive-pack; `git push --push-option` fails outright against a server that did not
    // advertise them, so without this the fixture would refuse the argv that ships.
    run(bare.toFile(), "git", "config", "receive.advertisePushOptions", "true");

    Path work = Files.createTempDirectory("qits-gitmirror-seed-");
    try {
      run(work.toFile(), "git", "init", "-q", "-b", "main");
      run(work.toFile(), "git", "remote", "add", "origin", bare.toString());
      commit(work, "README.md", "# fixture\n", "initial commit");
      run(work.toFile(), "git", "push", "-q", "origin", "main");
      run(work.toFile(), "git", "switch", "-q", "-c", "feature");
      commit(work, "feature.txt", "feature side\n", "feature commit");
      run(work.toFile(), "git", "push", "-q", "origin", "feature");
      run(work.toFile(), "git", "switch", "-q", "main");
      commit(work, "main.txt", "main side\n", "second main commit");
      run(work.toFile(), "git", "push", "-q", "origin", "main");
    } finally {
      deleteRecursively(work);
    }
    return bare;
  }

  /** Commit a file onto an existing branch of a bare, through a throwaway clone. */
  static void commitOnBranch(Path bare, String branch, String file, String content, String message)
      throws Exception {
    Path work = Files.createTempDirectory("qits-gitmirror-commit-");
    try {
      run(work.getParent().toFile(), "git", "clone", "-q", bare.toString(), work.toString());
      run(work.toFile(), "git", "switch", "-q", branch);
      commit(work, file, content, message);
      run(work.toFile(), "git", "push", "-q", "origin", branch);
    } finally {
      deleteRecursively(work);
    }
  }

  static String refIn(Path repo, String rev) throws Exception {
    return output(repo.toFile(), "git", "rev-parse", rev).trim();
  }

  static String refs(Path repo) throws Exception {
    return output(repo.toFile(), "git", "for-each-ref", "--format=%(refname)").trim();
  }

  private static void commit(Path work, String file, String content, String message)
      throws Exception {
    Files.writeString(work.resolve(file), content);
    run(work.toFile(), "git", "add", file);
    run(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  static String output(File cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    identity(pb);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", argv) + " failed:\n" + out);
    }
    return out;
  }

  private static void run(File cwd, String... argv) throws Exception {
    output(cwd, argv);
  }

  /** Deterministic identity and no user or system config, so a developer's ~/.gitconfig cannot
   *  change what these fixtures look like. */
  private static void identity(ProcessBuilder pb) {
    pb.environment().put("GIT_AUTHOR_NAME", "qits-test");
    pb.environment().put("GIT_AUTHOR_EMAIL", "qits-test@local");
    pb.environment().put("GIT_COMMITTER_NAME", "qits-test");
    pb.environment().put("GIT_COMMITTER_EMAIL", "qits-test@local");
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
  }

  static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
