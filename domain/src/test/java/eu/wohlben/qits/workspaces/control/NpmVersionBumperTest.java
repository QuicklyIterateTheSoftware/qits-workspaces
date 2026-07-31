package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The npm bumper against real manifests: an SPA with its lock, and a pnpm library repository whose
 * publishable manifest lives under {@code projects/}.
 *
 * <p>Same load-bearing assertion as the maven side — replacing the new version back with the old one
 * must reproduce the file byte for byte — plus the two things a regeneration would destroy and a
 * splice cannot: the committed {@code resolved} URLs and every other {@code "version"} in the lock.
 */
public class NpmVersionBumperTest {

  private static final String OLD = "0.0.0";
  private static final String NEW = "2026.731.193059";

  @TempDir Path work;

  @Test
  public void anSpaMovesExactlyTheThreeFieldsNpmCiCompares() throws Exception {
    Path repo = VersionFixtures.copy("npm-spa", work);
    Map<String, String> before = VersionFixtures.snapshot(repo);

    List<Path> changed = NpmVersionBumper.bump(repo, NEW);

    assertEquals(List.of(Path.of("package.json"), Path.of("package-lock.json")), changed);

    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(repo.resolve("package.json").toFile());
    JsonNode lock = mapper.readTree(repo.resolve("package-lock.json").toFile());
    assertEquals(NEW, manifest.get("version").asText());
    assertEquals(NEW, lock.get("version").asText());
    assertEquals(NEW, lock.get("packages").get("").get("version").asText());

    // npm ci's agreement rule, stated as the test rather than as a comment: those three and nothing
    // else, and if they disagree `npm ci` fails with EUSAGE before installing anything.
    assertEquals(
        1, VersionFixtures.count(VersionFixtures.read(repo.resolve("package.json")), NEW));
    assertEquals(
        2, VersionFixtures.count(VersionFixtures.read(repo.resolve("package-lock.json")), NEW));

    for (Map.Entry<String, String> file : VersionFixtures.snapshot(repo).entrySet()) {
      assertEquals(
          before.get(file.getKey()),
          file.getValue().replace("\"" + NEW + "\"", "\"" + OLD + "\""),
          file.getKey() + " changed somewhere other than its version field");
    }
  }

  @Test
  public void theResolvedUrlsAndTheNestedVersionsOfTheLockAreUntouched() {
    // The trap a lockfile regeneration walks into: the committed `resolved` URLs point at this
    // platform's own registry and the pipelines rewrite roughly 700 of them between
    // localhost:8081 and the qits-net origin. A regenerated lock would not resolve on a
    // developer's host.
    Path repo = VersionFixtures.copy("npm-spa", work);
    NpmVersionBumper.bump(repo, NEW);

    String lock = VersionFixtures.read(repo.resolve("package-lock.json"));
    assertTrue(
        lock.contains("\"resolved\": \"http://localhost:8081/artifacts/npm/npmjs/@acemir/cssom/"),
        "a resolved URL moved");
    assertTrue(lock.contains("\"version\": \"0.9.31\""), "a dependency's version moved");
    assertTrue(lock.contains("\"version\": \"1.14.1\""), "a dependency's version moved");
    assertTrue(lock.contains("\"@algolia/client-common\": \"5.48.1\""), "a range moved");
    assertTrue(lock.contains("\"lockfileVersion\": 3"), "the lockfile version is not a version");
    assertTrue(lock.contains("\"@qits/ui-components\": \"^0.0.4\""), "a caret range moved");
  }

  @Test
  public void aPnpmLibraryRepositoryBumpsItsPublishedManifestAndLeavesItsLockAlone() {
    // Settled decision 2: the two published packages switch to CalVer on their first integrate.
    // One scheme, no exceptions — excluding projects/*/package.json would make integrate a no-op
    // for the only two repositories where a version currently means something.
    Path repo = VersionFixtures.copy("npm-pnpm-library", work);
    String lockBefore = VersionFixtures.read(repo.resolve("pnpm-lock.yaml"));

    List<Path> changed = NpmVersionBumper.bump(repo, NEW);

    assertEquals(
        List.of(
            Path.of("package.json"),
            Path.of("projects/qits-spa-ui-components/package.json")),
        changed);
    assertTrue(
        VersionFixtures.read(repo.resolve("projects/qits-spa-ui-components/package.json"))
            .contains("\"version\": \"" + NEW + "\""),
        "@qits/ui-components is the published manifest and the real release gate");
    assertEquals(
        lockBefore,
        VersionFixtures.read(repo.resolve("pnpm-lock.yaml")),
        "pnpm-lock.yaml has no version field to mirror and is left byte-identical");
  }

  @Test
  public void bumpingTwiceWithTheSameVersionIsANoOpAndReportsNothingChanged() {
    Path repo = VersionFixtures.copy("npm-spa", work);
    NpmVersionBumper.bump(repo, NEW);
    Map<String, String> once = VersionFixtures.snapshot(repo);

    assertEquals(List.of(), NpmVersionBumper.bump(repo, NEW));
    assertEquals(once, VersionFixtures.snapshot(repo));
  }

  @Test
  public void anAlreadyBumpedRepositoryCanBeBumpedAgainToANewVersion() {
    Path repo = VersionFixtures.copy("npm-spa", work);
    NpmVersionBumper.bump(repo, NEW);

    assertEquals(2, NpmVersionBumper.bump(repo, "2026.801.93059").size());
    assertEquals(
        0, VersionFixtures.count(VersionFixtures.read(repo.resolve("package-lock.json")), NEW));
    assertEquals(
        2,
        VersionFixtures.count(
            VersionFixtures.read(repo.resolve("package-lock.json")), "2026.801.93059"));
  }

  @Test
  public void multiByteCharactersBeforeTheVersionDoNotShiftTheSplice() throws Exception {
    // Every SPA manifest in this platform carries a prose description with an em dash in it, and
    // several sit before the version field. A byte-oriented offset would land short and corrupt the
    // JSON in a way that still looks like JSON at a glance.
    String manifest =
        "{\n"
            + "  \"name\": \"unicode\",\n"
            + "  \"description\": \"em dashes — arrows → ellipses … and an emoji 🚀\",\n"
            + "  \"version\": \"0.0.0\"\n"
            + "}\n";
    Path repo = Files.createDirectory(work.resolve("unicode"));
    VersionFixtures.write(repo.resolve("package.json"), manifest);

    NpmVersionBumper.bump(repo, NEW);

    assertEquals(
        manifest.replace("\"0.0.0\"", "\"" + NEW + "\""),
        VersionFixtures.read(repo.resolve("package.json")));
  }

  @Test
  public void aLockWhoseRootEntryComesAfterThousandsOfOthersIsStillSplicedCorrectly()
      throws Exception {
    // The real locks here run to 8,600 lines. Jackson tracks its offsets across buffer refills — the
    // XML side does not, which is why that one is located by line and column — and this is where
    // that difference is held rather than assumed.
    StringBuilder lock = new StringBuilder("{\n  \"name\": \"big\",\n  \"version\": \"0.0.0\",\n");
    lock.append("  \"lockfileVersion\": 3,\n  \"packages\": {\n");
    for (int i = 0; i < 4000; i++) {
      lock.append("    \"node_modules/pkg-").append(i).append("\": {\n")
          .append("      \"version\": \"1.2.3\",\n")
          .append("      \"resolved\": \"http://localhost:8081/artifacts/npm/npmjs/pkg-")
          .append(i)
          .append("/-/pkg-")
          .append(i)
          .append("-1.2.3.tgz\"\n    },\n");
    }
    lock.append("    \"\": {\n      \"name\": \"big\",\n      \"version\": \"0.0.0\"\n    }\n  }\n}\n");
    String original = lock.toString();
    Path repo = Files.createDirectory(work.resolve("big"));
    VersionFixtures.write(repo.resolve("package.json"), "{\"name\":\"big\",\"version\":\"0.0.0\"}\n");
    VersionFixtures.write(repo.resolve("package-lock.json"), original);

    NpmVersionBumper.bump(repo, NEW);

    String bumped = VersionFixtures.read(repo.resolve("package-lock.json"));
    assertEquals(2, VersionFixtures.count(bumped, NEW));
    assertEquals(4000, VersionFixtures.count(bumped, "\"version\": \"1.2.3\""));
    assertEquals(original, bumped.replace("\"" + NEW + "\"", "\"0.0.0\""));
  }

  @Test
  public void aManifestWithNoVersionFieldFailsLoudly() throws Exception {
    Path repo = Files.createDirectory(work.resolve("versionless"));
    VersionFixtures.write(repo.resolve("package.json"), "{\n  \"name\": \"x\"\n}\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> NpmVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("declares no"), thrown.getMessage());
  }

  @Test
  public void aLockWithARootPackageEntryThatCarriesNoVersionFailsLoudly() throws Exception {
    Path repo = Files.createDirectory(work.resolve("half-lock"));
    VersionFixtures.write(repo.resolve("package.json"), "{\"name\":\"x\",\"version\":\"0.0.0\"}\n");
    VersionFixtures.write(
        repo.resolve("package-lock.json"),
        "{\"name\":\"x\",\"version\":\"0.0.0\",\"packages\":{\"\":{\"name\":\"x\"}}}\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> NpmVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("EUSAGE"), thrown.getMessage());
  }

  @Test
  public void aLockWithNoPackagesMapAtAllIsAcceptedWithItsSingleVersionBumped() throws Exception {
    // lockfileVersion 1 is long obsolete and none exists here, but "no packages map" is a real
    // absence rather than a broken one; only a HALF-present one is a failure.
    Path repo = Files.createDirectory(work.resolve("ancient"));
    VersionFixtures.write(repo.resolve("package.json"), "{\"name\":\"x\",\"version\":\"0.0.0\"}\n");
    VersionFixtures.write(
        repo.resolve("package-lock.json"),
        "{\"name\":\"x\",\"version\":\"0.0.0\",\"lockfileVersion\":1,\"dependencies\":{}}\n");

    NpmVersionBumper.bump(repo, NEW);

    assertTrue(
        VersionFixtures.read(repo.resolve("package-lock.json"))
            .contains("\"version\":\"" + NEW + "\""));
  }

  @Test
  public void aMalformedManifestFailsLoudly() throws Exception {
    Path repo = Files.createDirectory(work.resolve("broken"));
    VersionFixtures.write(repo.resolve("package.json"), "{\"name\": \"x\", \"version\": ");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> NpmVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("cannot parse"), thrown.getMessage());
  }

  @Test
  public void aNonStringVersionFailsRatherThanBeingQuotedIntoPlace() throws Exception {
    Path repo = Files.createDirectory(work.resolve("numeric"));
    VersionFixtures.write(repo.resolve("package.json"), "{\"name\":\"x\",\"version\":7}\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> NpmVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("non-string"), thrown.getMessage());
  }
}
