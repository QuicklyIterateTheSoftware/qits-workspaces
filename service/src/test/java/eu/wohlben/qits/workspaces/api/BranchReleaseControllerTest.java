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
  @Inject @org.eclipse.microprofile.rest.client.inject.RestClient FakeProjectsReleaseRequests releaseRequests;

  @BeforeEach
  void resetDoubles() {
    announcer.reset();
    gitHost.reset();
    repositories.nameResolutionOutage(false);
    releaseRequests.reset();
  }

  // -----------------------------------------------------------------------------------------
  // the public door, which creates a request now
  // -----------------------------------------------------------------------------------------

  /**
   * The door split's public half: {@code /branches/release} merges nothing any more — it creates
   * (or converges) the release request in qits-projects, armed with the branch's head on the git
   * host and carrying the caller's own name, and the gates decide when the execution arm lands it.
   */
  @Test
  public void theReleaseDoorCreatesARequestAndMergesNothing() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);
    String head = inOrigin(repoId, "git", "rev-parse", MAINTENANCE);
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest(MAINTENANCE, "a gated release", null))
        .post("/workspaces/api/branches/release?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("state", equalTo("PENDING"))
        .body("branch", equalTo(MAINTENANCE))
        .body("commitSha", equalTo(head))
        .body("requestId", not(emptyOrNullString()));

    assertEquals(1, releaseRequests.asked().size());
    FakeProjectsReleaseRequests.Asked asked = releaseRequests.asked().get(0);
    assertEquals(repoId, asked.repoId());
    assertEquals(head, asked.body().commitSha(), "the request arms with the wire head, not a cache");
    assertEquals("qits:system", asked.roles());
    assertNotNull(asked.user(), "the requester is the caller, forwarded by name");

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"), "nothing merged");
    assertTrue(originBranches(repoId).contains(MAINTENANCE), "nothing deleted");
    assertEquals(List.of(), announcer.announced(), "nothing released, so nothing announced");
  }

  /** The guards a caller can act on stay at this door, and a refused ask reaches no gate. */
  @Test
  public void theReleaseDoorStillRefusesWhatItCanAnswerItself() throws Exception {
    String repoId = seedRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest("master", "the default branch", null))
        .post("/workspaces/api/branches/release?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(
            new BranchController.ReleaseBranchRequest(
                "maintenance/never-pushed", "nothing to arm", null))
        .post("/workspaces/api/branches/release?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());

    assertEquals(List.of(), releaseRequests.asked(), "a refused ask reaches no gate");
  }

  /** An expectedSha in the ask arms the request with exactly that commit, resolved nowhere. */
  @Test
  public void theReleaseDoorArmsWithTheCallersShaWhenGiven() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);
    String pinned = inOrigin(repoId, "git", "rev-parse", MAINTENANCE + "~1");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest(MAINTENANCE, "pin what was reviewed", pinned))
        .post("/workspaces/api/branches/release?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitSha", equalTo(pinned));

    assertEquals(pinned, releaseRequests.asked().get(0).body().commitSha());
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
    return releaseAddressedBy("repositoryId=" + repoId, branch, summary);
  }

  /** The public identity form: {@code (projectId, repositoryName)} instead of the row id. */
  private io.restassured.response.Response releaseByName(
      String projectId, String repositoryName, String branch, String summary) {
    return releaseAddressedBy(
        "projectId=" + projectId + "&repositoryName=" + repositoryName, branch, summary);
  }

  private io.restassured.response.Response releaseAddressedBy(
      String query, String branch, String summary) {
    return given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest(branch, summary, null))
        .when()
        // RestAssured refuses a URI ending in "?", so the no-address case is the bare path — which
        // is the same request a caller who named nothing makes anyway.
        .post("/workspaces/api/branches/execute-release" + (query.isEmpty() ? "" : "?" + query));
  }

  /** The pinned form the release-quality-gates execution sends: land exactly this head or refuse. */
  private io.restassured.response.Response releasePinned(
      String repoId, String branch, String summary, String expectedSha) {
    return given()
        .contentType(ContentType.JSON)
        .body(new BranchController.ReleaseBranchRequest(branch, summary, expectedSha))
        .when()
        .post("/workspaces/api/branches/execute-release?repositoryId=" + repoId);
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
    assertEquals(FakeRepositoryLookup.nameOf(repoId), announced.repoName());
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

  /**
   * The pin the release-quality-gates execution sends: its gates evaluated one sha, and a branch
   * whose head moved past it must refuse — {@code HEAD_MOVED}, nothing landed, nothing deleted —
   * while a pin that still names the head releases exactly as an unpinned call does.
   */
  @Test
  public void aReleasePinnedToAStaleShaIsRefusedAndAFreshPinLands() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);
    String gated = inOrigin(repoId, "git", "rev-parse", MAINTENANCE);
    TestOrigin.commitOnBranch(
        dataDir, repoId, MAINTENANCE, "later.txt", "landed after the gate\n", "one more push");
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    releasePinned(repoId, MAINTENANCE, "gated at " + gated, gated)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("HEAD_MOVED"))
        .body("message", containsString(gated));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(originBranches(repoId).contains(MAINTENANCE), "a refused pin deletes nothing");
    assertEquals(List.of(), announcer.announced());

    String head = inOrigin(repoId, "git", "rev-parse", MAINTENANCE);
    releasePinned(repoId, MAINTENANCE, "gated at the head", head)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo(MAINTENANCE));
    assertEquals(1, announcer.announced().size(), "a fresh pin is an ordinary release");
  }

  /**
   * The claimed arm honours the pin: a stale pin refuses without touching the workspace, and a
   * fresh one releases through the workspace flow — row resolved, branch gone — exactly as an
   * unpinned call does.
   */
  @Test
  public void aPinnedReleaseOfAWorkspaceClaimedBranchHonoursThePin() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "claimed", "claimed-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "claimed-b", "work.md", "shipped\n", "the work");
    String stale = inOrigin(repoId, "git", "rev-parse", "claimed-b~1");

    releasePinned(repoId, "claimed-b", "pinned workspace release", stale)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("HEAD_MOVED"));
    assertEquals(List.of(), announcer.announced());
    assertTrue(activeLabels(repoId).contains("claimed"), "a refused pin resolves no workspace");

    String head = inOrigin(repoId, "git", "rev-parse", "claimed-b");
    releasePinned(repoId, "claimed-b", "pinned workspace release", head)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    assertEquals(1, announcer.announced().size());
    assertFalse(activeLabels(repoId).contains("claimed"), "a fresh pin resolves the workspace");
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

  // -----------------------------------------------------------------------------------------
  // addressing the repository: the row id, or the public identity
  // -----------------------------------------------------------------------------------------

  /**
   * The form pipelines move to. A row id is minted per platform instance and is addressable only
   * through the registry that minted it; {@code (projectId, repoName)} is what a clone url, a
   * committed pipeline and a person all spell — so the door takes it and resolves it, and
   * <b>everything after the resolution is the id path unchanged</b>. That is what this asserts: the
   * same merge commit, the same tag, the same announcement, reached through a name.
   */
  @Test
  public void aRepositoryAddressedByProjectAndNameReleasesExactlyAsItsIdDoes() throws Exception {
    String repoId = seedRepository();
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "pom.xml", POM, "add a pom");
    seedMaintenanceBranch(repoId);

    String summary = "bump(@qits/ui-components@2026.731.193059): follow the release";
    var response =
        releaseByName(
                FakeRepositoryLookup.PROJECT_ID,
                FakeRepositoryLookup.nameOf(repoId),
                MAINTENANCE,
                summary)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", matchesRegex(VERSION.pattern()))
            .body("branch", equalTo(MAINTENANCE))
            .extract();
    String version = response.path("version");
    String commitSha = response.path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", "master"));
    assertEquals(
        "release(" + version + "): " + summary,
        inOrigin(repoId, "git", "log", "-1", "--format=%s", "master"));
    assertEquals(version, inOrigin(repoId, "git", "tag", "-l"));
    assertFalse(originBranches(repoId).contains(MAINTENANCE), "the source is spent either way");

    // The event names the repository by the id the name resolved to — the door resolves addressing
    // and nothing else, so the announcement cannot differ between the two forms.
    assertEquals(1, announcer.announced().size());
    assertEquals(repoId, announcer.announced().get(0).repoId());
    assertEquals(FakeRepositoryLookup.nameOf(repoId), announcer.announced().get(0).repoName());
  }

  /**
   * A name that resolves to nothing is a <b>404</b> naming the pair the caller asked about. Not a
   * 400: the request is well-formed and the answer is that no such repository is registered, which
   * is the same answer an unknown row id gets.
   */
  @Test
  public void aNameNoRepositoryCarriesIs404NamingTheProjectAndTheName() throws Exception {
    seedRepository();

    releaseByName(FakeRepositoryLookup.PROJECT_ID, "never-registered", "feature", "no such name")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode())
        .body("message", containsString("never-registered"))
        .body("message", containsString(FakeRepositoryLookup.PROJECT_ID));

    assertEquals(List.of(), announcer.announced());
  }

  /**
   * The distinction the whole {@code RepositoryLookup} port exists to keep. A registry that cannot
   * be asked is a 5xx; folded into the 404 above it would tell a pipeline step its repository had
   * been deleted, and the step would stop retrying something a minute would have fixed.
   */
  @Test
  public void aRegistryOutageIs5xxRatherThanANameThatDoesNotResolve() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    repositories.nameResolutionOutage(true);
    try {
      releaseByName(
              FakeRepositoryLookup.PROJECT_ID,
              FakeRepositoryLookup.nameOf(repoId),
              MAINTENANCE,
              "the registry is down")
          .then()
          .statusCode(greaterThanOrEqualTo(500));
    } finally {
      repositories.nameResolutionOutage(false);
    }

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * Both forms at once is a 400 rather than a precedence order. A caller that sent both meant one
   * of them, and picking silently would be a release landing in a repository nobody named twice.
   */
  @Test
  public void addressingTheRepositoryBothWaysIsRefusedAndNamesTheRule() throws Exception {
    String repoId = seedRepository();
    seedMaintenanceBranch(repoId);

    releaseAddressedBy(
            "repositoryId="
                + repoId
                + "&projectId="
                + FakeRepositoryLookup.PROJECT_ID
                + "&repositoryName="
                + FakeRepositoryLookup.nameOf(repoId),
            MAINTENANCE,
            "two addresses")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("message", containsString("repositoryId"))
        .body("message", containsString("repositoryName"));

    assertTrue(originBranches(repoId).contains(MAINTENANCE), "a refused release deletes nothing");
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * Neither form, and half of the name form, are the same refusal — a half address is not an
   * address. Before this door took names, no repository at all resolved to a 404 out of the
   * registry; it is a 400 now, because the request never named one.
   */
  @Test
  public void addressingTheRepositoryNoWayAtAllIsRefusedAndNamesTheRule() throws Exception {
    String repoId = seedRepository();

    for (String query :
        List.of(
            "",
            "projectId=" + FakeRepositoryLookup.PROJECT_ID,
            "repositoryName=" + FakeRepositoryLookup.nameOf(repoId))) {
      releaseAddressedBy(query, "feature", "no repository named")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
          .body("message", containsString("projectId"));
    }

    assertEquals(List.of(), announcer.announced());
  }
}
