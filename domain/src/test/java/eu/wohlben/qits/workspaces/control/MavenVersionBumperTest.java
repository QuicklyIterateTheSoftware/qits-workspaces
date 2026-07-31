package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The maven bumper against qits-ci's real five-module reactor, copied verbatim into the fixtures.
 *
 * <p>The load-bearing assertion in this file is not "the version changed" — it is that <b>replacing
 * the new version back with the old one reproduces the original file byte for byte</b>. That single
 * check covers formatting, indentation, comments, attribute order, the XML declaration and every
 * version element the bump was supposed to leave alone, and it is the reason the splice exists
 * instead of a DOM round-trip.
 */
public class MavenVersionBumperTest {

  private static final String OLD = "1.0.0-SNAPSHOT";
  private static final String NEW = "2026.731.193059";

  @TempDir Path work;

  @Test
  public void aFiveModuleReactorMovesExactlySixVersionElements() {
    Path repo = VersionFixtures.copy("maven-reactor", work);
    Map<String, String> before = VersionFixtures.snapshot(repo);

    List<Path> changed = MavenVersionBumper.bump(repo, NEW);

    // Root plus five modules: every pom of the reactor carries exactly one version element, so the
    // file count and the element count are the same six.
    assertEquals(
        List.of(
            Path.of("pom.xml"),
            Path.of("ci-daemon-protocol/pom.xml"),
            Path.of("eventstream/pom.xml"),
            Path.of("ci-events/pom.xml"),
            Path.of("ci/pom.xml"),
            Path.of("service/pom.xml")),
        changed,
        "the reactor is walked by <module>, root first, in declaration order");

    Map<String, String> after = VersionFixtures.snapshot(repo);
    int elements = 0;
    for (Map.Entry<String, String> file : after.entrySet()) {
      String original = before.get(file.getKey());
      elements += VersionFixtures.count(file.getValue(), NEW);
      assertEquals(
          original,
          file.getValue().replace(NEW, OLD),
          file.getKey() + " changed somewhere other than its version element");
    }
    assertEquals(6, elements, "exactly six version elements for a five-module reactor");
  }

  @Test
  public void theVendoredParentlessModuleComesAlongBecauseTheRootPinsItAtProjectVersion() {
    // qits-ci's eventstream/ has no <parent>; it declares its own groupId and version, and the
    // root's dependencyManagement pins it at ${project.version}. A bumper that only rewrote
    // /project/version of the root and /project/parent/version of the children would leave the
    // reactor unable to resolve it.
    Path repo = VersionFixtures.copy("maven-reactor", work);
    MavenVersionBumper.bump(repo, NEW);

    String eventstream = VersionFixtures.read(repo.resolve("eventstream/pom.xml"));
    assertTrue(
        eventstream.contains("<artifactId>qits-eventstream</artifactId>\n    <version>" + NEW),
        "the parentless module's own <version> must move with the root");
    assertEquals(0, VersionFixtures.count(eventstream, OLD));
  }

  @Test
  public void everyChildsParentVersionIsRewrittenAndItsOtherVersionsAreNot() {
    Path repo = VersionFixtures.copy("maven-reactor", work);
    MavenVersionBumper.bump(repo, NEW);

    for (String module : List.of("ci", "service", "ci-events", "ci-daemon-protocol")) {
      String pom = VersionFixtures.read(repo.resolve(module + "/pom.xml"));
      assertEquals(1, VersionFixtures.count(pom, NEW), module + " should hold one new version");
      assertEquals(0, VersionFixtures.count(pom, OLD), module + " should hold no old version");
      assertTrue(
          pom.contains("<artifactId>qits-ci</artifactId>\n        <version>" + NEW + "</version>"),
          module + "'s <parent><version> should be the element that moved");
    }
    // The platform, plugin and library versions in the root pom are expressions and third-party
    // pins; none of them is the reactor's coordinate and none of them moved.
    String root = VersionFixtures.read(repo.resolve("pom.xml"));
    assertTrue(root.contains("<quarkus.platform.version>3.34.6</quarkus.platform.version>"));
    assertTrue(root.contains("<version>${quarkus.platform.version}</version>"));
    assertTrue(root.contains("<version>${lombok.version}</version>"));
    assertTrue(root.contains("<version>${project.version}</version>"));
  }

  @Test
  public void decoysThatMerelySpellTheSameStringAreLeftAlone() {
    // A comment, a property, a third-party dependency, a plugin and an ${expression} all holding
    // the literal 1.0.0-SNAPSHOT. Only the reactor's own coordinates move — including one LITERAL
    // inter-module dependency version, of which the platform has zero today and which would break
    // the build the moment the root moved.
    Path repo = VersionFixtures.copy("decoys", work);
    Map<String, String> before = VersionFixtures.snapshot(repo);

    MavenVersionBumper.bump(repo, NEW);

    String root = VersionFixtures.read(repo.resolve("pom.xml"));
    assertEquals(2, VersionFixtures.count(root, NEW), "the root's own version and its literal"
        + " dependency on the child");
    assertEquals(
        5,
        VersionFixtures.count(root, OLD),
        "the two mentions in the comment, the property, the third-party dependency and the plugin"
            + " all stay");
    assertTrue(root.contains("<legacy.tool.version>" + OLD + "</legacy.tool.version>"));
    assertTrue(root.contains("<artifactId>vendor-widgets</artifactId>\n                <version>" + OLD));
    assertTrue(root.contains("<artifactId>vendor-maven-plugin</artifactId>\n                    <version>" + OLD));
    assertTrue(root.contains("<version>${project.version}</version>"), "expressions are untouched");

    String child = VersionFixtures.read(repo.resolve("child/pom.xml"));
    assertEquals(1, VersionFixtures.count(child, NEW), "only <parent><version>");
    assertEquals(
        0, VersionFixtures.count(child, OLD), "the exclusion naming a module carries no version");

    for (Map.Entry<String, String> file : VersionFixtures.snapshot(repo).entrySet()) {
      assertEquals(
          before.get(file.getKey()),
          file.getValue().replace(NEW, OLD),
          file.getKey() + " changed somewhere other than its version elements");
    }
  }

  @Test
  public void bumpingTwiceWithTheSameVersionIsANoOpAndReportsNothingChanged() {
    // Re-entry matters: an integrate that failed after the bump and before the commit leaves a
    // worktree already carrying the version, and the retry must neither double-write nor claim a
    // change that is not there.
    Path repo = VersionFixtures.copy("maven-reactor", work);
    MavenVersionBumper.bump(repo, NEW);
    Map<String, String> once = VersionFixtures.snapshot(repo);

    List<Path> changed = MavenVersionBumper.bump(repo, NEW);

    assertEquals(List.of(), changed, "nothing to write the second time");
    assertEquals(once, VersionFixtures.snapshot(repo));
  }

  @Test
  public void anAlreadyBumpedReactorCanBeBumpedAgainToANewVersion() {
    Path repo = VersionFixtures.copy("maven-reactor", work);
    MavenVersionBumper.bump(repo, NEW);
    List<Path> changed = MavenVersionBumper.bump(repo, "2026.801.93059");

    assertEquals(6, changed.size());
    for (Map.Entry<String, String> file : VersionFixtures.snapshot(repo).entrySet()) {
      assertEquals(0, VersionFixtures.count(file.getValue(), NEW), file.getKey());
      assertEquals(1, VersionFixtures.count(file.getValue(), "2026.801.93059"), file.getKey());
    }
  }

  @Test
  public void aPomLargerThanTheParsersBufferIsStillSplicedInTheRightPlace() throws Exception {
    // The JDK's StAX reports a character offset that is exact only inside its first 8192-character
    // buffer; past the first refill it is wrong, which is why the offsets here come from
    // line/column. qits-ci's real service pom is well past that boundary, and so is this one, with
    // the version element deliberately at the very end.
    StringBuilder padding = new StringBuilder();
    while (padding.length() < 40_000) {
      padding.append("    <!-- ").append(padding.length()).append(" bytes of comment -->\n");
    }
    String pom =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "    <modelVersion>4.0.0</modelVersion>\n"
            + "    <groupId>eu.wohlben.qits</groupId>\n"
            + "    <artifactId>big</artifactId>\n"
            + padding
            + "    <version>" + OLD + "</version>\n"
            + "</project>\n";
    Path repo = Files.createDirectory(work.resolve("big"));
    VersionFixtures.write(repo.resolve("pom.xml"), pom);

    MavenVersionBumper.bump(repo, NEW);

    assertEquals(pom.replace(OLD, NEW), VersionFixtures.read(repo.resolve("pom.xml")));
  }

  @Test
  public void aPomWithWindowsLineEndingsIsSplicedInTheRightPlace() throws Exception {
    String pom =
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                + "    <modelVersion>4.0.0</modelVersion>\n"
                + "    <groupId>eu.wohlben.qits</groupId>\n"
                + "    <artifactId>crlf</artifactId>\n"
                + "    <version>" + OLD + "</version>\n"
                + "</project>\n")
            .replace("\n", "\r\n");
    Path repo = Files.createDirectory(work.resolve("crlf"));
    VersionFixtures.write(repo.resolve("pom.xml"), pom);

    MavenVersionBumper.bump(repo, NEW);

    assertEquals(pom.replace(OLD, NEW), VersionFixtures.read(repo.resolve("pom.xml")));
  }

  @Test
  public void multiByteCharactersBeforeTheVersionDoNotShiftTheSplice() throws Exception {
    // Offsets here are character indices into a String, and the poms in this tree are full of em
    // dashes and arrows. A byte-oriented offset would land one place short per multi-byte character
    // and quietly corrupt exactly the files with the most prose in them.
    String pom =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
            + "    <!-- em dashes — arrows → ellipses … and an emoji 🚀, all before the version -->\n"
            + "    <groupId>eu.wohlben.qits</groupId>\n"
            + "    <artifactId>unicode</artifactId>\n"
            + "    <version>" + OLD + "</version>\n"
            + "</project>\n";
    Path repo = Files.createDirectory(work.resolve("unicode"));
    VersionFixtures.write(repo.resolve("pom.xml"), pom);

    MavenVersionBumper.bump(repo, NEW);

    assertEquals(pom.replace(OLD, NEW), VersionFixtures.read(repo.resolve("pom.xml")));
  }

  @Test
  public void aMalformedPomFailsLoudly() throws Exception {
    Path repo = Files.createDirectory(work.resolve("broken"));
    VersionFixtures.write(
        repo.resolve("pom.xml"),
        "<project><artifactId>broken</artifactId><version>1.0</version>\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> MavenVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("pom.xml"), thrown.getMessage());
  }

  @Test
  public void aDeclaredModuleWithNoPomFailsLoudly() throws Exception {
    Path repo = Files.createDirectory(work.resolve("missing-module"));
    VersionFixtures.write(
        repo.resolve("pom.xml"),
        "<project><artifactId>r</artifactId><version>1.0</version>"
            + "<modules><module>nowhere</module></modules></project>\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> MavenVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("nowhere"), thrown.getMessage());
  }

  @Test
  public void aModuleOutsideTheRepositoryIsRefused() throws Exception {
    Path repo = Files.createDirectory(work.resolve("escaping"));
    VersionFixtures.write(
        repo.resolve("pom.xml"),
        "<project><artifactId>r</artifactId><version>1.0</version>"
            + "<modules><module>../elsewhere</module></modules></project>\n");
    VersionFixtures.write(
        work.resolve("elsewhere/pom.xml"),
        "<project><artifactId>e</artifactId><version>1.0</version></project>\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> MavenVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("escapes the repository"), thrown.getMessage());
  }

  @Test
  public void aVersionElementThatIsNotPlainTextFailsRatherThanGuessing() throws Exception {
    Path repo = Files.createDirectory(work.resolve("self-closing"));
    VersionFixtures.write(
        repo.resolve("pom.xml"), "<project><artifactId>r</artifactId><version/></project>\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> MavenVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("plain text value"), thrown.getMessage());
  }

  @Test
  public void aReactorWithNoVersionElementAtAllFailsRatherThanSilentlyDoingNothing() throws Exception {
    // The failure this rules out is the quiet one: a green integrate whose poms still carry the
    // previous version, discovered much later in a published artifact.
    Path repo = Files.createDirectory(work.resolve("versionless"));
    VersionFixtures.write(
        repo.resolve("pom.xml"),
        "<project><parent><groupId>com.example</groupId><artifactId>outside</artifactId>"
            + "<version>9</version></parent><artifactId>r</artifactId></project>\n");

    VersionBumpException thrown =
        assertThrows(VersionBumpException.class, () -> MavenVersionBumper.bump(repo, NEW));
    assertTrue(thrown.getMessage().contains("no version element"), thrown.getMessage());
  }
}
