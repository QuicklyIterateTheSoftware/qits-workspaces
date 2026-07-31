package eu.wohlben.qits.workspaces.control;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which build stacks a repository checkout renders its version into, decided by what sits at the
 * repository root and nothing else.
 *
 * <ul>
 *   <li>{@code pom.xml} at the root ⇒ {@link BuildStack#MAVEN}
 *   <li>{@code package.json} at the root ⇒ {@link BuildStack#NPM}
 *   <li>both ⇒ both, bumped with the same version string
 *   <li>neither ⇒ neither, and still a release
 * </ul>
 *
 * <p><b>Root only, deliberately.</b> A nested {@code pom.xml} that the root reactor does not list as
 * a {@code <module>} is not part of the build, and a nested {@code package.json} is almost always
 * inside {@code node_modules} or a {@code dist/}. Walking the tree for manifests would find those,
 * and would also find the {@code .claude/worktrees/} checkouts that are present in this tree today
 * and belong to no tracked build. The maven bumper follows {@code <module>} declarations from the
 * root pom, so the reactor's own shape — not a directory scan — decides the scope.
 *
 * <p><b>Both is measured to be unreachable today</b> and is implemented anyway: {@code git ls-files}
 * finds zero tracked {@code package.json} across all 13 maven repositories here, because every
 * service's web UI is a gitlink to a separate SPA repository. Defining the behaviour costs one
 * {@code if}; leaving it emergent would cost a debugging session the first time a repository grows
 * a second stack.
 */
public final class StackDetector {

  private StackDetector() {}

  public static Set<BuildStack> detect(Path repoRoot) {
    Set<BuildStack> stacks = EnumSet.noneOf(BuildStack.class);
    if (Files.isRegularFile(repoRoot.resolve("pom.xml"))) {
      stacks.add(BuildStack.MAVEN);
    }
    if (Files.isRegularFile(repoRoot.resolve("package.json"))) {
      stacks.add(BuildStack.NPM);
    }
    return stacks;
  }
}
