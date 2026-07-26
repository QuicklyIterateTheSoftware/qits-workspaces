package eu.wohlben.qits.workspaces.control;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Builds a bare origin for a synthetic repository, in the layout this context expects: {@code
 * <dataDir>/<repoId>/origin}.
 *
 * <p>Replaces the monorepo's {@code RepositoryService.cloneRepository(...)} against the {@code
 * /fixtures/testing-repo.git} bare, which is derived from git submodules by an antrun step at build
 * time. Neither the service nor the submodules exist here, and reproducing them would drag the
 * repositories context and a fixture-derivation build step into a repo whose whole point is not to
 * need them. Two commits on {@code master} plus a diverging {@code feature} branch is everything the
 * workspace tests actually assert against.
 *
 * <p>Pair it with {@link FakeRepositoryLookup#register}, which is what makes the id resolvable.
 */
public final class TestOrigin {

  private TestOrigin() {}

  /**
   * Create a bare origin under {@code dataDir} and return its generated repository id.
   *
   * <p>The result has: {@code master} with two commits; {@code feature}, forked from the first
   * commit and carrying a commit {@code master} does not have, so the two tips are distinguishable
   * and a merge between them is a real merge.
   */
  public static String create(String dataDir) throws Exception {
    String repoId = UUID.randomUUID().toString();
    // Absolute: dataDir is relative in tests (target/...), and `git remote add`
    // would otherwise resolve it against the throwaway work dir, not the module.
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    Files.createDirectories(origin.getParent());
    Path work = Files.createTempDirectory("qits-test-origin-");
    try {
      run(origin.getParent().toFile(), "git", "init", "--bare", "-b", "master", origin.toString());

      run(work.toFile(), "git", "init", "-b", "master");
      run(work.toFile(), "git", "remote", "add", "origin", origin.toString());
      commit(work, "README.md", "# test repo\n", "initial commit");
      run(work.toFile(), "git", "push", "-q", "origin", "master");

      // feature forks HERE, so it lacks master's second commit and vice versa.
      run(work.toFile(), "git", "switch", "-q", "-c", "feature");
      commit(work, "feature.txt", "feature side\n", "feature commit");
      run(work.toFile(), "git", "push", "-q", "origin", "feature");

      run(work.toFile(), "git", "switch", "-q", "master");
      commit(work, "master.txt", "master side\n", "second master commit");
      run(work.toFile(), "git", "push", "-q", "origin", "master");
      return repoId;
    } finally {
      deleteRecursively(work);
    }
  }

  private static void commit(Path work, String file, String content, String message)
      throws Exception {
    Files.writeString(work.resolve(file), content);
    run(work.toFile(), "git", "add", file);
    run(work.toFile(), "git", "commit", "-q", "-m", message);
  }

  /** Run a git command, failing the test with its combined output when it exits non-zero. */
  private static void run(File cwd, String... argv) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd).redirectErrorStream(true);
    // Deterministic identity and no user/system config, so a developer's ~/.gitconfig (hooks,
    // signing, a default branch name, templates) cannot change what these fixtures look like.
    pb.environment().put("GIT_AUTHOR_NAME", "qits-test");
    pb.environment().put("GIT_AUTHOR_EMAIL", "qits-test@local");
    pb.environment().put("GIT_COMMITTER_NAME", "qits-test");
    pb.environment().put("GIT_COMMITTER_EMAIL", "qits-test@local");
    pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
    pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    if (p.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", argv) + " failed:\n" + out);
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best effort — it is a temp dir
                }
              });
    }
  }
}
