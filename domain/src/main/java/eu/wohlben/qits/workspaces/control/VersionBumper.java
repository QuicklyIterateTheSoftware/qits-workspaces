package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The bump engine's one door: given a checkout and a version, write that version into whatever
 * manifests the checkout renders versions through.
 *
 * <p><b>Where it runs: in this JVM, in-process.</b> Not a step container, not {@code ./mvnw
 * versions:set}, not {@code npm}. The workspaces runtime image carries {@code git-core} and {@code
 * docker-ce-cli} and neither maven nor node, so a toolchain-driven bump would download Maven into
 * the container on every integrate; and {@code npm install --package-lock-only} would rewrite the
 * {@code resolved} URLs this platform deliberately pins. The edit surface is tiny and exactly known
 * — six XML elements for a five-module reactor, three JSON fields for an SPA — so the toolchain
 * buys nothing and costs the two traps above.
 *
 * <p><b>Pure.</b> No git, no network, no configuration: it reads and writes files under one
 * directory and returns what it changed. That is what lets its tests be the exhaustive ones, with no
 * service in the way, and it is what leaves the integrate flow free to call it inside a detached
 * worktree where nothing has moved a ref yet.
 *
 * <p><b>A repository with no stack is still a release.</b> The version is computed from the clock
 * regardless of stack; detection only decides which files render it. A stackless repository gets the
 * same {@code release($version): …} merge commit and an empty {@link BumpResult#changedFiles()}.
 * That keeps "integrate is the only flow into main" universal instead of carving out the stub
 * repositories.
 */
@ApplicationScoped
public class VersionBumper {

  /**
   * What a version string may contain for both splices to be safe without escaping. {@link
   * VersionStamp} produces digits and dots; the guard exists so that a caller passing something else
   * fails here rather than writing an unparseable pom or an invalid JSON string.
   */
  private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

  /**
   * @param version the version written into every manifest, unchanged
   * @param stacks the stacks detected at the repository root; empty is a supported answer
   * @param changedFiles every file whose bytes changed, relative to the repository root, ready to be
   *     handed to {@code git add}
   */
  public record BumpResult(String version, Set<BuildStack> stacks, List<Path> changedFiles) {}

  /**
   * Stamp {@code version} into {@code repoRoot}.
   *
   * @throws VersionBumpException if a detected stack's manifests cannot be read, parsed, or
   *     addressed
   */
  public BumpResult bump(Path repoRoot, String version) {
    if (version == null || !SAFE_VERSION.matcher(version).matches()) {
      throw new VersionBumpException("refusing to stamp an unusable version string: " + version);
    }
    if (!Files.isDirectory(repoRoot)) {
      throw new VersionBumpException("no checkout at " + repoRoot);
    }

    Set<BuildStack> stacks = StackDetector.detect(repoRoot);
    List<Path> changed = new ArrayList<>();
    // Both stacks in one repository is unreachable in this platform today and handled anyway, with
    // the same version string in both — one `if`, so that the behaviour is defined rather than
    // emergent the first time a repository grows a second stack.
    if (stacks.contains(BuildStack.MAVEN)) {
      changed.addAll(MavenVersionBumper.bump(repoRoot, version));
    }
    if (stacks.contains(BuildStack.NPM)) {
      changed.addAll(NpmVersionBumper.bump(repoRoot, version));
    }
    return new BumpResult(version, Set.copyOf(stacks), List.copyOf(changed));
  }
}
