package eu.wohlben.qits.workspaces.control;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copies a fixture repository out of {@code src/test/resources/version-fixtures} into a temporary
 * directory, because the bumpers write files.
 *
 * <p>The maven fixture is qits-ci's five-module reactor <b>copied verbatim</b>, comments and all:
 * the root pom, four modules that declare only {@code <parent><version>}, and the vendored
 * parentless {@code eventstream} module that carries its own. Six poms, six version elements, and
 * the awkward shape is exactly why it is a copy of something real rather than something written to
 * be convenient. The npm fixtures are likewise real files, with the lock trimmed to a handful of
 * entries so it stays reviewable while keeping its shape — including the {@code localhost:8081}
 * {@code resolved} URLs a regeneration would destroy, and a nested {@code "version"} that must not
 * move.
 */
final class VersionFixtures {

  private VersionFixtures() {}

  /** Copy the named fixture into {@code into}, returning the copy's root. */
  static Path copy(String name, Path into) {
    Path source = onDisk("version-fixtures/" + name);
    Path target = into.resolve(name);
    try (Stream<Path> tree = Files.walk(source)) {
      tree.forEach(
          path -> {
            Path destination = target.resolve(source.relativize(path).toString());
            try {
              if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
              } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
              }
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return target;
  }

  /** Every file under {@code root}, keyed by its path relative to {@code root}. */
  static Map<String, String> snapshot(Path root) {
    Map<String, String> files = new LinkedHashMap<>();
    try (Stream<Path> tree = Files.walk(root)) {
      tree.filter(Files::isRegularFile)
          .sorted()
          .forEach(
              path -> {
                try {
                  files.put(root.relativize(path).toString(), Files.readString(path));
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files;
  }

  static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void write(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** How many times {@code needle} occurs in {@code haystack}. */
  static int count(String haystack, String needle) {
    int found = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      found++;
    }
    return found;
  }

  private static Path onDisk(String resource) {
    URL url = VersionFixtures.class.getClassLoader().getResource(resource);
    if (url == null) {
      throw new IllegalStateException("missing test fixture " + resource);
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
