package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Writes the release version into an npm repository's manifests — and into nothing else.
 *
 * <h2>Exactly three fields, measured</h2>
 *
 * <pre>
 *   package.json         .version
 *   package-lock.json    .version
 *   package-lock.json    .packages[""].version
 * </pre>
 *
 * That is the whole edit surface. {@code npm ci} fails hard with {@code EUSAGE} when those three
 * disagree and compares nothing else about the version, so keeping them in step is both necessary
 * and sufficient.
 *
 * <h2>Never a lockfile regeneration</h2>
 *
 * {@code npm install --package-lock-only} is not merely unnecessary here, it is actively harmful:
 * every SPA's committed lock pins {@code resolved} URLs against this platform's own registry, and
 * the pipelines rewrite roughly 700 of them between {@code localhost:8081} and the qits-net origin.
 * Regenerating a lock inside the platform would commit one that no longer resolves on a developer's
 * host. The workspaces runtime image carries no node anyway. Three spliced spans; nothing else in
 * the file moves.
 *
 * <h2>{@code projects/*&#47;package.json}</h2>
 *
 * The Angular library convention, and for the two publishable library repositories here that inner
 * manifest is the <i>published</i> one — the real release gate. It is bumped like everything else:
 * one scheme, no exceptions (settled, superproject docs/release-flow-notes.md). {@code pnpm-lock.yaml}
 * carries no version field to mirror and is left byte-identical.
 *
 * <h2>Absent fields are loud</h2>
 *
 * A manifest with no {@code version} is not stamped silently. Detection already said this repository
 * renders its version through npm; a manifest that then cannot hold one is a fact the integrate
 * caller has to see, not one to discover later in a published artifact.
 */
public final class NpmVersionBumper {

  private static final String ROOT_VERSION = "/version";
  private static final String LOCK_ROOT_PACKAGE_VERSION = "/packages//version";
  private static final String ROOT_PACKAGE = "/packages/";

  private NpmVersionBumper() {}

  /**
   * Rewrite the npm manifests under {@code repoRoot}.
   *
   * @return the changed files, relative to {@code repoRoot}
   */
  public static List<Path> bump(Path repoRoot, String version) {
    List<Path> changed = new ArrayList<>();

    bumpManifest(repoRoot, repoRoot.resolve("package.json"), version, changed);

    Path lock = repoRoot.resolve("package-lock.json");
    if (Files.isRegularFile(lock)) {
      bumpLock(repoRoot, lock, version, changed);
    }

    for (Path manifest : libraryManifests(repoRoot)) {
      bumpManifest(repoRoot, manifest, version, changed);
    }
    return List.copyOf(changed);
  }

  /** {@code projects/<lib>/package.json}, one level down, in a stable order. */
  private static List<Path> libraryManifests(Path repoRoot) {
    Path projects = repoRoot.resolve("projects");
    if (!Files.isDirectory(projects)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(projects)) {
      return entries
          .filter(Files::isDirectory)
          .map(directory -> directory.resolve("package.json"))
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    } catch (IOException e) {
      throw new VersionBumpException("cannot list " + projects + ": " + e.getMessage(), e);
    }
  }

  private static void bumpManifest(
      Path repoRoot, Path manifest, String version, List<Path> changed) {
    String name = repoRoot.relativize(manifest).toString();
    String json = read(manifest);
    Map<String, TextSplice.Span> spans =
        PackageJsonVersions.locate(json, Set.of(ROOT_VERSION), name);
    TextSplice.Span span = spans.get(ROOT_VERSION);
    if (span == null) {
      throw new VersionBumpException(name + " declares no \"version\"");
    }
    replace(repoRoot, manifest, json, List.of(span), version, changed);
  }

  private static void bumpLock(Path repoRoot, Path lock, String version, List<Path> changed) {
    String name = repoRoot.relativize(lock).toString();
    String json = read(lock);
    Map<String, TextSplice.Span> spans =
        PackageJsonVersions.locate(json, Set.of(ROOT_VERSION, LOCK_ROOT_PACKAGE_VERSION), name);

    TextSplice.Span root = spans.get(ROOT_VERSION);
    if (root == null) {
      throw new VersionBumpException(name + " declares no \"version\"");
    }
    TextSplice.Span rootPackage = spans.get(LOCK_ROOT_PACKAGE_VERSION);
    if (rootPackage == null && hasRootPackageEntry(json, name)) {
      throw new VersionBumpException(
          name
              + " has a \"packages\" entry for the root package but no version in it; npm ci"
              + " compares that field against package.json and fails with EUSAGE when they"
              + " disagree");
    }
    List<TextSplice.Span> all = new ArrayList<>();
    all.add(root);
    if (rootPackage != null) {
      all.add(rootPackage);
    }
    replace(repoRoot, lock, json, all, version, changed);
  }

  /**
   * Whether the lock is one of the modern formats that carries {@code packages[""]}. A
   * {@code lockfileVersion 1} lock has no {@code packages} map at all and its single {@code
   * .version} is the whole surface — that is an absence to accept, not to fail on.
   */
  private static boolean hasRootPackageEntry(String json, String origin) {
    return !PackageJsonVersions.fieldsPresent(json, Set.of(ROOT_PACKAGE), origin).isEmpty();
  }

  private static void replace(
      Path repoRoot,
      Path file,
      String json,
      List<TextSplice.Span> spans,
      String version,
      List<Path> changed) {
    String bumped = TextSplice.replaceAll(json, spans, '"' + version + '"');
    if (!bumped.equals(json)) {
      write(file, bumped);
      changed.add(repoRoot.relativize(file));
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new VersionBumpException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  private static void write(Path file, String text) {
    try {
      Files.writeString(file, text);
    } catch (IOException e) {
      throw new VersionBumpException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }
}
