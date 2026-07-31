package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stack detection and the engine's one door, including the two cases the platform does not have
 * today and would otherwise discover the hard way: a repository with both stacks, and a repository
 * with neither.
 */
public class VersionBumperTest {

  private static final String NEW = "2026.731.193059";

  @TempDir Path work;

  private final VersionBumper bumper = new VersionBumper();

  @Test
  public void aMavenRepositoryIsDetectedAndBumped() {
    Path repo = VersionFixtures.copy("maven-reactor", work);

    VersionBumper.BumpResult result = bumper.bump(repo, NEW);

    assertEquals(Set.of(BuildStack.MAVEN), result.stacks());
    assertEquals(NEW, result.version());
    assertEquals(6, result.changedFiles().size());
  }

  @Test
  public void anNpmRepositoryIsDetectedAndBumped() {
    Path repo = VersionFixtures.copy("npm-spa", work);

    VersionBumper.BumpResult result = bumper.bump(repo, NEW);

    assertEquals(Set.of(BuildStack.NPM), result.stacks());
    assertEquals(
        List.of(Path.of("package.json"), Path.of("package-lock.json")), result.changedFiles());
  }

  @Test
  public void bothStacksInOneRepositoryGetTheSameVersionString() {
    // Machine-checked to be unreachable today — zero tracked package.json across all 13 maven
    // repositories, because every service's web UI is a gitlink to a separate SPA repo. Defined
    // here anyway, because it costs one `if` and leaving it emergent costs a debugging session.
    Path repo = VersionFixtures.copy("both-stacks", work);

    VersionBumper.BumpResult result = bumper.bump(repo, NEW);

    assertEquals(Set.of(BuildStack.MAVEN, BuildStack.NPM), result.stacks());
    assertEquals(
        List.of(Path.of("pom.xml"), Path.of("package.json")), result.changedFiles(),
        "maven first, then npm — a stable order, because it is reported to the caller");
    assertTrue(VersionFixtures.read(repo.resolve("pom.xml")).contains("<version>" + NEW));
    assertTrue(
        VersionFixtures.read(repo.resolve("package.json")).contains("\"version\": \"" + NEW));
  }

  @Test
  public void aRepositoryWithNoStackIsStillAReleaseAndChangesNothing() {
    // The version is computed from the clock regardless of stack; detection only decides which
    // files render it. Carving the stub repositories out of integrate would break "integrate is
    // the only flow into main" for exactly the repositories nobody is watching.
    Path repo = VersionFixtures.copy("no-stack", work);
    Map<String, String> before = VersionFixtures.snapshot(repo);

    VersionBumper.BumpResult result = bumper.bump(repo, NEW);

    assertEquals(Set.of(), result.stacks());
    assertEquals(List.of(), result.changedFiles());
    assertEquals(NEW, result.version());
    assertEquals(before, VersionFixtures.snapshot(repo));
  }

  @Test
  public void aNestedPomThatTheReactorDoesNotListIsNotPartOfTheBump() throws Exception {
    // .claude/worktrees/ is real and present in this tree, and belongs to no tracked build. The
    // reactor is walked by <module>, never by directory scan, so no exclusion list is needed —
    // and this is the test that says so.
    Path repo = VersionFixtures.copy("maven-reactor", work);
    Path stray = repo.resolve(".claude/worktrees/scratch/pom.xml");
    String strayPom =
        "<project><artifactId>scratch</artifactId><version>1.0.0-SNAPSHOT</version></project>\n";
    VersionFixtures.write(stray, strayPom);

    bumper.bump(repo, NEW);

    assertEquals(strayPom, VersionFixtures.read(stray));
  }

  @Test
  public void aStampFromTheClockGoesStraightIntoTheManifests() {
    // The seam the integrate flow uses: one stamp, taken once, threaded through — never recomputed
    // per file, or a slow bump would write two versions into one commit.
    Path repo = VersionFixtures.copy("npm-spa", work);
    String version = VersionStamp.of(Instant.parse("2026-07-31T09:30:59Z"));

    VersionBumper.BumpResult result = bumper.bump(repo, version);

    assertEquals("2026.731.93059", result.version());
    assertTrue(
        VersionFixtures.read(repo.resolve("package.json")).contains("\"version\": \"2026.731.93059\""));
  }

  @Test
  public void anUnusableVersionStringIsRefusedBeforeAnyFileIsOpened() {
    Path repo = VersionFixtures.copy("npm-spa", work);
    Map<String, String> before = VersionFixtures.snapshot(repo);

    for (String bad : List.of("", " ", "1.0 \"; drop", "a<b", "\"", "-leading-dash")) {
      assertThrows(VersionBumpException.class, () -> bumper.bump(repo, bad), bad);
    }
    assertThrows(VersionBumpException.class, () -> bumper.bump(repo, null));
    assertEquals(before, VersionFixtures.snapshot(repo));
  }

  @Test
  public void aMissingCheckoutFailsLoudly() {
    assertThrows(
        VersionBumpException.class, () -> bumper.bump(work.resolve("not-there"), NEW));
  }

  @Test
  public void bumpingTheSameCheckoutTwiceWithTheSameVersionReportsNoChangeTheSecondTime()
      throws Exception {
    Path repo = VersionFixtures.copy("both-stacks", work);
    bumper.bump(repo, NEW);
    Map<String, String> once = VersionFixtures.snapshot(repo);

    VersionBumper.BumpResult again = bumper.bump(repo, NEW);

    assertEquals(List.of(), again.changedFiles());
    assertEquals(Set.of(BuildStack.MAVEN, BuildStack.NPM), again.stacks());
    assertEquals(once, VersionFixtures.snapshot(repo));
    assertTrue(Files.exists(repo.resolve("pom.xml")));
  }
}
