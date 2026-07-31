package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the release version into every version element of a maven reactor.
 *
 * <h2>What "the version of a multi-module repo" is here</h2>
 *
 * One coordinate, spelled out in full in every pom. The platform's poms <b>duplicate versions on
 * purpose</b> — a clone of any one repository has to build green with no monorepo above it and no
 * parent to inherit from — so there is no {@code <revision>} property to move and no single place to
 * edit. Measured across all 13 maven repositories in this platform:
 *
 * <pre>
 *   root pom       /project/version
 *   each module    /project/parent/version        (or its own /project/version, see below)
 *   inter-module   nothing literal — every one of the 20 inter-module dependency versions in the
 *                  tree is the expression ${project.version}, which follows the root for free
 *
 *   qits-ci, 5 modules: exactly 6 elements.
 * </pre>
 *
 * <p>The plan's model was "modules declare no version of their own". Measured, one does:
 * qits-ci vendors {@code eventstream/} as a <b>parentless</b> module carrying its own {@code
 * <groupId>}/{@code <version>}, and the root's {@code dependencyManagement} pins it at {@code
 * ${project.version}} — so its own version <i>must</i> move with the root or the reactor stops
 * resolving. Hence the rule implemented here: <b>every pom in the reactor gets its own {@code
 * <version>} and its in-reactor {@code <parent><version>} rewritten</b>, whichever it declares. The
 * count is unchanged (one element per pom, 6 for a 5-module reactor); the rule is what makes the
 * vendored module come along.
 *
 * <h2>Scope is the reactor, walked by {@code <module>}</h2>
 *
 * Never a directory scan. That is what keeps {@code .claude/worktrees/} — real, present in this
 * tree, and part of no tracked build — out of the bump without needing an exclusion list, and it is
 * also the only definition of "the reactor" that agrees with what maven itself would build. A module
 * that resolves outside the repository root, or to a missing pom, is a loud failure.
 *
 * <h2>Literal inter-module dependency versions</h2>
 *
 * Zero exist today, and one would break the build the moment the root moved, so they are rewritten
 * too — but only when the dependency names a module of this same reactor <i>and</i> its version is a
 * literal rather than a {@code ${…}} expression. Property expressions are left exactly alone: they
 * are the mechanism that already works.
 */
public final class MavenVersionBumper {

  private MavenVersionBumper() {}

  /**
   * Rewrite the reactor rooted at {@code repoRoot/pom.xml}.
   *
   * @return the changed files, relative to {@code repoRoot}, in reactor order
   */
  public static List<Path> bump(Path repositoryRoot, String version) {
    Path repoRoot = normalize(repositoryRoot);
    Map<Path, String> texts = new LinkedHashMap<>();
    Map<Path, PomVersions.Scan> reactor = collect(repoRoot, texts);

    Set<String> reactorArtifacts = new HashSet<>();
    Set<String> reactorCoordinates = new HashSet<>();
    for (PomVersions.Scan scan : reactor.values()) {
      reactorArtifacts.add(scan.artifactId());
      reactorCoordinates.add(scan.groupId() + ":" + scan.artifactId());
    }

    List<Path> changed = new ArrayList<>();
    int elements = 0;
    for (Map.Entry<Path, PomVersions.Scan> entry : reactor.entrySet()) {
      Path pom = entry.getKey();
      PomVersions.Scan scan = entry.getValue();
      List<TextSplice.Span> spans = new ArrayList<>();

      if (scan.version() != null) {
        spans.add(scan.version().span());
      }
      if (scan.parentVersion() != null
          && reactorCoordinates.contains(scan.parentGroupId() + ":" + scan.parentArtifactId())) {
        spans.add(scan.parentVersion().span());
      }
      for (PomVersions.Dependency dependency : scan.dependencies()) {
        if (dependency.version() == null) {
          continue;
        }
        String literal = dependency.version().value().trim();
        if (literal.contains("${")) {
          continue;
        }
        if (namesAReactorModule(dependency, scan, reactorArtifacts)) {
          spans.add(dependency.version().span());
        }
      }

      if (spans.isEmpty()) {
        continue;
      }
      String original = texts.get(pom);
      String bumped = TextSplice.replaceAll(original, spans, version);
      elements += spans.size();
      if (!bumped.equals(original)) {
        write(pom, bumped);
        changed.add(repoRoot.relativize(pom));
      }
    }

    if (elements == 0) {
      throw new VersionBumpException(
          "no version element found in the maven reactor at " + repoRoot + "; refusing to report a"
              + " release whose poms still carry the previous version");
    }
    return List.copyOf(changed);
  }

  /**
   * A dependency belongs to this reactor when its artifactId names a module of it and its groupId
   * either matches, is absent, or is an expression — {@code ${project.groupId}} is how every
   * inter-module dependency in this platform spells it, and resolving properties is not this
   * engine's job.
   */
  private static boolean namesAReactorModule(
      PomVersions.Dependency dependency, PomVersions.Scan owner, Set<String> reactorArtifacts) {
    if (!reactorArtifacts.contains(dependency.artifactId())) {
      return false;
    }
    String groupId = dependency.groupId();
    return groupId == null
        || groupId.contains("${")
        || groupId.equals(owner.groupId())
        || groupId.equals(owner.parentGroupId());
  }

  /** Every pom of the reactor, in breadth-first {@code <module>} order, root first. */
  private static Map<Path, PomVersions.Scan> collect(Path repoRoot, Map<Path, String> texts) {
    Path rootPom = normalize(repoRoot.resolve("pom.xml"));
    if (!Files.isRegularFile(rootPom)) {
      throw new VersionBumpException("no pom.xml at " + repoRoot);
    }
    Path root = normalize(repoRoot);

    Map<Path, PomVersions.Scan> reactor = new LinkedHashMap<>();
    Deque<Path> queue = new ArrayDeque<>();
    queue.add(rootPom);
    while (!queue.isEmpty()) {
      Path pom = queue.removeFirst();
      if (reactor.containsKey(pom)) {
        continue;
      }
      String text = read(pom);
      PomVersions.Scan scan = PomVersions.scan(text, root.relativize(pom).toString());
      texts.put(pom, text);
      reactor.put(pom, scan);

      for (String module : scan.modules()) {
        queue.add(resolveModule(root, pom, module));
      }
    }
    return reactor;
  }

  private static Path resolveModule(Path root, Path pom, String module) {
    if (module.isBlank()) {
      throw new VersionBumpException("blank <module> in " + root.relativize(pom));
    }
    Path candidate = normalize(pom.getParent().resolve(module));
    if (!candidate.startsWith(root)) {
      throw new VersionBumpException(
          "<module>" + module + "</module> in " + root.relativize(pom) + " escapes the repository");
    }
    Path modulePom = Files.isDirectory(candidate) ? candidate.resolve("pom.xml") : candidate;
    if (!Files.isRegularFile(modulePom)) {
      throw new VersionBumpException(
          "<module>" + module + "</module> in " + root.relativize(pom) + " has no pom.xml");
    }
    return normalize(modulePom);
  }

  private static Path normalize(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static String read(Path pom) {
    try {
      return Files.readString(pom);
    } catch (IOException e) {
      throw new VersionBumpException("cannot read " + pom + ": " + e.getMessage(), e);
    }
  }

  private static void write(Path pom, String text) {
    try {
      Files.writeString(pom, text);
    } catch (IOException e) {
      throw new VersionBumpException("cannot write " + pom + ": " + e.getMessage(), e);
    }
  }
}
