package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.gitmirror.GitCli;
import java.io.File;

/**
 * Running git in a fixture directory — the bare origins {@link TestOrigin} builds, and the throwaway
 * clones a test stages a commit in.
 *
 * <p>It replaces {@code GitExecutor}, a production bean that only the suite still called: every
 * repository operation goes through {@code gitmirror} now, and the two argv left on that class had
 * no caller at all. A test helper over the module's {@link GitCli} says that plainly, where keeping
 * a bean in {@code src/main} for the sake of its {@code src/test} callers would not.
 *
 * <p>Duplicated between {@code domain/src/test} and {@code service/src/test}, the convention here:
 * the two modules do not share a test classpath.
 */
public final class TestGit {

  private static final GitCli CLI = new GitCli();

  private TestGit() {}

  /**
   * Run {@code argv} in {@code cwd} (null inherits this process's) and return its combined output. A
   * non-zero exit throws with that output, so a fixture that failed to build says why.
   */
  public static String exec(File cwd, String... argv) throws Exception {
    GitCli.Result result = CLI.run(cwd, null, null, null, argv);
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          "Command failed [" + result.exitCode() + "]: " + String.join(" ", argv) + "\n"
              + result.output());
    }
    return result.output();
  }
}
