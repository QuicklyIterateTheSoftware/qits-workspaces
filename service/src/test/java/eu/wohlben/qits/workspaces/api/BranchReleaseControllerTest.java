package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeGitHostAddress;
import eu.wohlben.qits.workspaces.control.FakeReleaseAnnouncer;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/branches/release} — the same release, keyed by branch name.
 *
 * <p>The caller this door exists for is a build container: a maintenance hop force-pushes {@code
 * maintenance/<upstream>} and then asks for it to be released, and no workspace row exists or should
 * exist for a ref a pipeline overwrites at will. So the fixture branches here carry that name, slash
 * and all — which is also what proves the flow's worktree slug survives a branch name that cannot be
 * a directory name.
 *
 * <p>What is <b>not</b> re-proven here is the flow: the detached worktree, the compare-and-swap
 * push, the lease, the whole 409 family are {@code ReleaseControllerTest}'s, and they hold for this
 * door by construction rather than by two suites agreeing — {@code ReleaseIntegrator} is keyed by
 * (repository, source branch), so this endpoint calls the same method with the same arguments. What
 * is proven here is only what a branch name adds: that a plain branch releases at all, that a branch
 * a workspace claims resolves that workspace, that the source branch is gone afterwards, and the
 * three refusals a name can produce (unknown, the default branch itself, already in).
 */
@QuarkusTest
public class BranchReleaseControllerTest {

  /** {@code YYYY.MMDD.HHMMSS}, and no identifier may carry a leading zero. */
  private static final Pattern VERSION =
      Pattern.compile("(?:[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)");

  /** The train's own ref shape: one branch per upstream, force-pushed by the bump step. */
  private static final String MAINTENANCE = "maintenance/qits-spa-ui-components";

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeGitHostAddress gitHost;
  @Inject FakeReleaseAnnouncer announcer;
  @Inject WorkspaceService workspaceService;

  @BeforeEach
  void resetDoubles() {
    announcer.reset();
    gitHost.reset();
  }

  // -----------------------------------------------------------------------------------------
  // fixtures
  // -----------------------------------------------------------------------------------------

  private String seedRepository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  private void createWorkspace(String repoId, String label, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, label, "master", branch, null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private io.restassured.response.Response release(String repoId, String branch, String summary) {
    return given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest(branch, summary))
        .when()
        .post("/workspaces/api/branches/release?repositoryId=" + repoId);
  }

  private String inOrigin(String repoId, String... argv) throws Exception {
    return TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), argv).trim();
  }

  private List<String> originBranches(String repoId) throws Exception {
    return inOrigin(repoId, "git", "for-each-ref", "--format=%(refname:short)", "refs/heads")
        .lines()
        .toList();
  }

  private List<String> activeLabels(String repoId) {
    return given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("entries.workspace.workspaceId");
  }

  /** A minimal but real maven reactor root, so the bump has something to render the version into. */
  private static final String POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>fixture</artifactId>
          <version>1.0.0-SNAPSHOT</version>
      </project>
      """;

  /** A branch off {@code feature}, so it diverges from master and the merge is a real merge. */
  private void seedMaintenanceBranch(String repoId) throws Exception {
    inOrigin(repoId, "git", "branch", MAINTENANCE, "feature");
    TestOrigin.commitOnBranch(
        dataDir,
        repoId,
        MAINTENANCE,
        "consumed.txt",
        "2026.731.193059\n",
        "bump(@qits/ui-components@2026.731.193059): follow the release");
  }

  // -----------------------------------------------------------------------------------------
  // the happy path
  // -----------------------------------------------------------------------------------------

  /**
   * The case the maintenance hop is: a branch with <b>no workspace at all</b> becomes a release —
   * one commit, two parents, the bumped manifest inside it, the source branch gone, and a {@code
   * SCMRelease} announced. A full release by every measure, reached through a name.
   */
  @Test
  public void aPlainBranchWithNoWorkspaceIsReleasedLikeAnyOther() throws Exception {
    String repoId = seedRepository();
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "pom.xml", POM, "add a pom");
    seedMaintenanceBranch(repoId);

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    String sourceTip = inOrigin(repoId, "git", "rev-parse", MAINTENANCE);
    String summary = "bump(@qits/ui-components@2026.731.193059): follow the release";

    var response =
        release(repoId, MAINTENANCE, summary)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", matchesRegex(VERSION.pattern()))
            .body("commitSha", not(emptyOrNullString()))
            .body("branch", equalTo(MAINTENANCE))
            .extract();
    String version = response.path("version");
    String commitSha = response.path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", "master"));
    assertEquals(
        List.of(commitSha, masterBefore, sourceTip),
        List.of(inOrigin(repoId, "git", "rev-list", "--parents", "-n", "1", "master").split(" ")),
        "the release must be a single merge commit of the old default branch and the source");
    assertEquals(
        "release(" + version + "): " + summary,
        inOrigin(repoId, "git", "log", "-1", "--format=%s", "master"));
    assertTrue(
        inOrigin(repoId, "git", "show", "master:pom.xml")
            .contains("<version>" + version + "</version>"),
        "the release commit's tree must carry the bumped version");
    assertEquals("2026.731.193059", inOrigin(repoId, "git", "show", "master:consumed.txt"));

    // The source is spent, so it is deleted — the next force-push of the same ref is a create.
    assertFalse(
        originBranches(repoId).contains(MAINTENANCE),
        "a released branch is deleted, leaving no ref claiming work is still pending");

    // Tagged like any other release: same flow, so the same annotated tag on the same commit.
    assertEquals(version, inOrigin(repoId, "git", "tag", "-l"));
    assertEquals(
        "tag", inOrigin(repoId, "git", "cat-file", "-t", inOrigin(repoId, "git", "rev-parse", version)));
    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", version + "^{commit}"));

    assertEquals(1, announcer.announced().size(), "a release announces, whichever door it came in");
    FakeReleaseAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(FakeRepositoryLookup.PROJECT_ID, announced.projectId());
    assertEquals(repoId, announced.repoId());
    assertEquals(MAINTENANCE, announced.branch(), "the SOURCE branch — there is no target field");
    assertEquals(version, announced.version());
    assertEquals(commitSha, announced.commitSha());
    assertNotNull(announced.publishedAt());
  }

  /**
   * The rule that keeps this door from being a hole: a branch an ACTIVE workspace claims is that
   * workspace's release. The row resolves exactly as the workspace-keyed door leaves it, because
   * this call <em>is</em> that door — anything else would strand a workspace on a branch that just
   * merged and no longer exists.
   */
  @Test
  public void aBranchAnActiveWorkspaceClaimsResolvesThatWorkspace() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "work", "work-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "work-b", "feature.md", "shipped\n", "the work");

    String commitSha =
        release(repoId, "work-b", "land the claimed branch")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("branch", equalTo("work-b"))
            .extract()
            .path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", "master"));
    assertFalse(activeLabels(repoId).contains("work"), "the claiming workspace resolved");
    assertFalse(originBranches(repoId).contains("work-b"), "and its branch went with it");
    assertEquals(1, announcer.announced().size());
  }

  // -----------------------------------------------------------------------------------------
  // the refusals
  // -----------------------------------------------------------------------------------------

  /**
   * What a lost 200 looks like on the retry, and the step script's own success case: the hop is
   * already in, so the 409 says {@code ALREADY_INTEGRATED} rather than stamping a second, empty
   * release. The reason is a structural field precisely so a caller can key on it.
   */
  @Test
  public void anAlreadyIntegratedBranchIsRefusedRatherThanReleasedAgain() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);
    inOrigin(repoId, "git", "branch", "-f", "master", MAINTENANCE);
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    release(repoId, MAINTENANCE, "already in")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("ALREADY_INTEGRATED"))
        .body("message", containsString("already integrated"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(
        originBranches(repoId).contains(MAINTENANCE), "a refused release deletes nothing either");
    assertEquals(List.of(), announcer.announced());
  }

  /** A branch the origin does not have is a 404: the name is the identity, and there is no row. */
  @Test
  public void anUnknownBranchIs404() throws Exception {
    String repoId = seedRepository();

    release(repoId, "maintenance/never-pushed", "nothing to land")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    assertEquals(List.of(), announcer.announced());
  }

  /** The default branch is what a release lands <em>on</em>; releasing it into itself is malformed. */
  @Test
  public void theDefaultBranchCannotBeReleasedIntoItself() throws Exception {
    String repoId = seedRepository();

    release(repoId, "master", "nowhere to go")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void anUnknownRepositoryIs404() {
    release("does-not-exist", "feature", "no such repository")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  // -----------------------------------------------------------------------------------------
  // the request
  // -----------------------------------------------------------------------------------------

  /** The same {@code @NotBlank}/{@code @Size(max = 100)} the workspace-keyed door enforces. */
  @Test
  public void aSummaryIsRequiredAndCappedAsItIsOnTheOtherDoor() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);

    release(repoId, MAINTENANCE, "  ")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    release(repoId, MAINTENANCE, "x".repeat(101))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void aBranchIsRequired() throws Exception {
    String repoId = seedRepository();

    release(repoId, "", "no branch named")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
