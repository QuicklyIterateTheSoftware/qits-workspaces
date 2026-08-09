package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeGitHostAddress;
import eu.wohlben.qits.workspaces.control.FakeReleaseAnnouncer;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.VersionStamp;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/workspaces/{id}/release} — the one door into a repository's default
 * branch.
 *
 * <p>Every case here runs against a <b>real bare origin</b> and performs a <b>real {@code git
 * push}</b> into it ({@link FakeGitHostAddress} replaces the transport and nothing else), because
 * the three claims worth making are all claims about git: that the release is one commit with two
 * parents carrying both the merge and the bump, that a failure releases nothing and leaves the
 * default branch byte-identical, and that the push is a genuine compare-and-swap. None of those
 * survives a mocked git.
 *
 * <p>The sibling {@code IntegrateControllerTest} covers the other door — a plain merge into a
 * parent branch — and asserts the two differences that matter: no version anywhere, and no
 * announcement.
 *
 * <p>What is deliberately <em>not</em> proven here is the git host's protection hook, which lives in
 * qits-artifacts and is proven there and on the live platform. This suite pushes with the production
 * argv — {@code --push-option=qits.release} — so the fixture has to advertise push options the way
 * JGit does; see {@code TestOrigin}.
 */
@QuarkusTest
public class ReleaseControllerTest {

  /** {@code YYYY.MMDD.HHMMSS}, and no identifier may carry a leading zero. */
  private static final Pattern VERSION =
      Pattern.compile("(?:[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)");

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** This service's own tree — where the mirrors and their worktrees live now. */
  @ConfigProperty(name = "qits.workspaces.data-dir")
  String ownDataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeGitHostAddress gitHost;
  @Inject FakeReleaseAnnouncer announcer;
  @Inject WorkspaceIds workspaceIds;
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
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, label, "master", branch, null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private io.restassured.response.Response release(String repoId, String label, String summary) {
    return given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.ReleaseRequest(summary))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, label) + "/release");
  }

  private String inOrigin(String repoId, String... argv) throws Exception {
    return TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), argv).trim();
  }

  /** Every tag the origin holds, one per line and empty when there are none. */
  private String tagsInOrigin(String repoId) throws Exception {
    return inOrigin(repoId, "git", "tag", "-l");
  }

  /**
   * Pre-create the tag for every version a release starting now could stamp, so "this version is
   * already released" is a fact of the fixture rather than a race the test hopes to win.
   *
   * <p>The stamp is taken from the clock inside the flow and no seam hands a test that clock, so the
   * same-second collision is simulated by covering the whole window the run can land in. The refs
   * are written as loose ref files rather than through {@code git tag}, which would be one process
   * per second of window; a loose ref is exactly what git would have written.
   */
  private void tagEveryVersionForTheNextTwoMinutes(String repoId) throws Exception {
    String sha = inOrigin(repoId, "git", "rev-parse", "master");
    Path tags = Path.of(dataDir, repoId, "origin", "refs", "tags");
    java.nio.file.Files.createDirectories(tags);
    Instant from = Instant.now();
    for (int second = 0; second < 120; second++) {
      java.nio.file.Files.writeString(
          tags.resolve(VersionStamp.of(from.plusSeconds(second))), sha + "\n");
    }
  }

  /**
   * Where a release lands — <b>one</b> branch, read from the same key a deployment sets, because the
   * platform has one answer and no repository states it.
   *
   * <p>The fixtures used to declare two refs each ({@code environment/prod} and {@code
   * platform/main}) in their own {@code deploy_branches}, and the pair carried two claims: that a
   * repository chose its refs, and that one refusing while the other landed could be told apart.
   * Neither survives. A release pushed onto <em>every</em> declared ref, which is a fan-out and not
   * a ladder, so the list went and the per-branch reporting has one branch to report on. The
   * response is still a list and {@code promote} is still called in a loop — the ladder will make
   * that plural again, for a reason the old list never had — but nothing here can exercise a second
   * entry, and a test pretending otherwise would be testing its own fixture.
   */
  @ConfigProperty(name = "qits.workspaces.release.entry-branch")
  String entryBranch;

  /**
   * Make this repository one that deploys. It says so by carrying the file at all; what is inside is
   * the deployer's and this service reads none of it.
   */
  private void declareDeployable(String repoId) throws Exception {
    writeSpec(repoId, "deployment_target: environment\n");
  }

  private void writeSpec(String repoId, String yaml) throws Exception {
    TestOrigin.commitOnBranch(
        dataDir, repoId, "master", ".config/qits/deployments.yml", yaml, "declare how this deploys");
  }

  /**
   * A deploy branch's commit in the origin, or {@code ""} when the ref is absent. {@code
   * for-each-ref} rather than {@code rev-parse} because "not there" is an answer here, and a
   * non-zero exit would be an exception instead.
   */
  private String branchInOrigin(String repoId, String branch) throws Exception {
    return inOrigin(repoId, "git", "for-each-ref", "--format=%(objectname)", "refs/heads/" + branch);
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

  // -----------------------------------------------------------------------------------------
  // the happy path
  // -----------------------------------------------------------------------------------------

  /**
   * The claim the whole feature rests on: <b>one</b> commit, with <b>two</b> parents, carrying the
   * merge <i>and</i> the version bump. Not a merge then an amend, not a merge then a bump commit —
   * {@code merge --no-ff --no-commit} leaves the index open and the bump writes into it, so the
   * single commit that follows is the release.
   */
  @Test
  public void aReleaseIsOneMergeCommitCarryingBothTheMergeAndTheBump() throws Exception {
    String repoId = seedRepository();
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "pom.xml", POM, "add a pom");
    createWorkspace(repoId, "work", "work-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "work-b", "feature.md", "shipped\n", "the work");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    String sourceTip = inOrigin(repoId, "git", "rev-parse", "work-b");

    var response =
        release(repoId, "work", "teach the explorer to group runs by repository")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", matchesRegex(VERSION.pattern()))
            .body("commitSha", not(emptyOrNullString()))
            .body("branch", equalTo("work-b"))
            .extract();
    String version = response.path("version");
    String commitSha = response.path("commitSha");

    // The push moved the ref, and it moved it to exactly what the caller was told.
    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", "master"));

    // ONE commit, TWO parents, and they are the two tips that went in.
    String parents = inOrigin(repoId, "git", "rev-list", "--parents", "-n", "1", "master");
    assertEquals(
        List.of(commitSha, masterBefore, sourceTip),
        List.of(parents.split(" ")),
        "the release must be a single merge commit of the old default branch and the source");

    assertEquals(
        "release(" + version + "): teach the explorer to group runs by repository",
        inOrigin(repoId, "git", "log", "-1", "--format=%s", "master"));
    assertTrue(
        inOrigin(repoId, "git", "log", "-1", "--format=%b", "master")
            .contains("Integrates workspace branch `work-b`."),
        "the body names the source branch, which the merge's parents record only as a sha");

    // The bump is IN that commit: the tree carries the stamped version and the merged content.
    assertTrue(
        inOrigin(repoId, "git", "show", "master:pom.xml").contains("<version>" + version + "</version>"),
        "the release commit's tree must carry the bumped version");
    assertEquals("shipped", inOrigin(repoId, "git", "show", "master:feature.md"));

    // And it is one commit rather than two: the version change is part of the merge commit itself.
    assertTrue(
        inOrigin(repoId, "git", "diff", "--name-only", masterBefore, commitSha)
            .lines()
            .anyMatch("pom.xml"::equals),
        "the bump must be reachable from the merge commit, not from a follow-up one");

    // The tag is on the origin, and it is ANNOTATED — a tag object of its own, peeling to the
    // release commit. Annotated because it carries a message and a tagger; the release is a fact
    // with an author, and a lightweight tag is only a second name for a sha.
    assertEquals(version, tagsInOrigin(repoId), "a release tags the version and nothing else");
    String tagSha = inOrigin(repoId, "git", "rev-parse", version);
    assertEquals("tag", inOrigin(repoId, "git", "cat-file", "-t", tagSha));
    assertEquals(
        commitSha,
        inOrigin(repoId, "git", "rev-parse", version + "^{commit}"),
        "the tag peels to the release commit, which is what a release pipeline checks out");
    assertEquals(
        "release(" + version + "): teach the explorer to group runs by repository",
        inOrigin(repoId, "git", "tag", "-l", "--format=%(contents:subject)", version),
        "the tag message is the release commit's subject");

    // The workspace resolved, so the ACTIVE-only listing no longer answers it.
    assertTrue(!activeLabels(repoId).contains("work"), "a released workspace leaves the listing");

    // The SCMRelease seam: exactly one statement, with everything the publisher needs.
    assertEquals(1, announcer.announced().size());
    FakeReleaseAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(FakeRepositoryLookup.PROJECT_ID, announced.projectId());
    assertEquals(repoId, announced.repoId());
    assertEquals(
        FakeRepositoryLookup.nameOf(repoId),
        announced.repoName(),
        "the name too: a row id is per-platform, and a committed CI selection can only name a name");
    assertEquals("work-b", announced.branch(), "the SOURCE branch — there is no target field");
    assertEquals(version, announced.version());
    assertEquals(commitSha, announced.commitSha());
    assertNotNull(announced.publishedAt(), "an event with no occurredAt is a 400 on the wire");
  }

  /**
   * A repository with no manifests is still a release. The version comes from the clock regardless
   * of stack; detection only decides which files render it. This is what keeps "release is the
   * only flow into the default branch" universal instead of carving out the stub repositories.
   */
  @Test
  public void aRepositoryWithNoVersionFilesIsStillARelease() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "stackless", "stackless-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "stackless-b", "notes.txt", "hi\n", "a note");

    String version =
        release(repoId, "stackless", "no manifests here")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("version");

    assertEquals(
        "release(" + version + "): no manifests here",
        inOrigin(repoId, "git", "log", "-1", "--format=%s", "master"));
    assertEquals(1, announcer.announced().size());
  }

  // -----------------------------------------------------------------------------------------
  // the promotions — the further pushes, which are what deploy
  // -----------------------------------------------------------------------------------------

  /**
   * The entry branch is created when it is absent, at the released commit exactly. A repository that
   * has never deployed is the ordinary first case, not an error, and a create is what receive-pack
   * does with a push to a ref that is not there.
   */
  @Test
  public void aReleaseCreatesTheEntryBranchAtTheReleasedCommit() throws Exception {
    String repoId = seedRepository();
    declareDeployable(repoId);
    createWorkspace(repoId, "deploy", "deploy-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "deploy-b", "shipped.md", "shipped\n", "the work");

    assertEquals("", branchInOrigin(repoId, entryBranch), "the fixture has no entry branch yet");

    String commitSha =
        release(repoId, "deploy", "the first deploy of this repository")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("promotions.branch", equalTo(List.of(entryBranch)))
            .body("promotions.error", everyItem(nullValue()))
            .extract()
            .path("commitSha");

    assertEquals(
        commitSha,
        inOrigin(repoId, "git", "rev-parse", entryBranch),
        "the entry branch holds the released commit, which is what deploys it");
    assertEquals(
        inOrigin(repoId, "git", "rev-parse", "master"),
        inOrigin(repoId, "git", "rev-parse", entryBranch),
        "the same commit on the trunk and on the entry branch: main builds, this one ships");
  }

  /**
   * A branch that already exists is <b>fast-forwarded</b>, and that is asserted as history rather
   * than as an absence of errors: what was deployed before is still reachable from the branch, which
   * a force push would not have left true.
   *
   * <p>The starting point is written as a ref rather than produced by a first release, deliberately.
   * Two releases a fraction of a second apart stamp one version and the second is refused whole
   * (see the concurrency test below) — a real property of the flow, and the wrong thing for this
   * test to depend on.
   */
  @Test
  public void thePromotionFastForwardsAnEntryBranchThatAlreadyExists() throws Exception {
    String repoId = seedRepository();
    declareDeployable(repoId);
    createWorkspace(repoId, "next", "next-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "next-b", "two.txt", "two\n", "the work");
    // Where an earlier release left it: on the default branch, which this one builds on.
    String deployedBefore = inOrigin(repoId, "git", "rev-parse", "master");
    inOrigin(repoId, "git", "branch", entryBranch, "master");

    String commitSha =
        release(repoId, "next", "the next deploy")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("promotions.branch", equalTo(List.of(entryBranch)))
            .body("promotions.error", everyItem(nullValue()))
            .extract()
            .path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", entryBranch));
    assertTrue(
        inOrigin(repoId, "git", "rev-list", entryBranch).lines().anyMatch(deployedBefore::equals),
        "what the entry branch deployed before is still in its history — a fast-forward");
  }

  /**
   * The entry branch holds something the release is not built on — a hand-pushed hotfix, a rollback,
   * a wrong ref — and the promotion is refused rather than forced.
   *
   * <p>What this asserts is the <b>partial success</b>: a 200 with the version and the sha, because
   * the release really did land and no exception could take it back, plus an {@code error} saying
   * nothing deployed and which commit to push once the branch is sorted out. The alternative —
   * throwing — would answer a caller whose work is already on the default branch with a failure,
   * skip the {@code SCMRelease} event and leave the workspace ACTIVE on a merged branch.
   */
  @Test
  public void aRefusedPromotionIsReportedAndTheReleaseStillLands() throws Exception {
    String repoId = seedRepository();
    declareDeployable(repoId);
    createWorkspace(repoId, "stuck", "stuck-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "stuck-b", "mine.txt", "mine\n", "my work");
    // feature forked before master's second commit, so no descendant of master can fast-forward
    // over it — a real divergence, not a stale copy.
    String divergent = inOrigin(repoId, "git", "rev-parse", "feature");
    inOrigin(repoId, "git", "branch", entryBranch, "feature");

    var response =
        release(repoId, "stuck", "released but not deployed")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", matchesRegex(VERSION.pattern()))
            .body("promotions.branch", equalTo(List.of(entryBranch)))
            .body("promotions.error", everyItem(containsString("fast-forward")))
            .extract();
    String commitSha = response.path("commitSha");

    assertEquals(
        commitSha,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the release is in: a failed promotion never unwinds the push that already happened");
    assertEquals(
        divergent,
        inOrigin(repoId, "git", "rev-parse", entryBranch),
        "the entry branch is untouched — the promotion is never a force push");
    assertTrue(
        errorFor(response, entryBranch).contains(commitSha),
        "the message names the commit to push, which is the only thing left to do by hand");
    assertTrue(
        errorFor(response, entryBranch).contains(entryBranch),
        "and it names the branch, which is where that push goes");
    assertEquals(
        1, announcer.announced().size(), "the release happened, so SCMRelease is published");
    assertTrue(!activeLabels(repoId).contains("stuck"), "and the workspace resolved");
  }

  /** One branch's {@code error} out of the promotions array, or null when that promotion landed. */
  private static String errorFor(ExtractableResponse<?> response, String branch) {
    List<String> branches = response.path("promotions.branch");
    List<String> errors = response.path("promotions.error");
    return errors.get(branches.indexOf(branch));
  }

  // -----------------------------------------------------------------------------------------
  // whether a repository deploys at all: the spec's presence, and nothing inside it
  // -----------------------------------------------------------------------------------------

  /**
   * A repository that carries a spec is promoted to the configured entry branch — the platform's
   * answer, not the repository's, and read from the same key a deployment sets.
   *
   * <p>The spec here declares nothing but a target the deployer reads. That used to be the
   * "compatibility path until every repository declares its refs"; it is now the only path, because
   * every repository named the same ref and a per-repository list turned out to be a fan-out rather
   * than a ladder.
   */
  @Test
  public void aSpecMeansTheReleasePromotesToTheConfiguredEntryBranch() throws Exception {
    String repoId = seedRepository();
    writeSpec(repoId, "deployment_target: environment\n");
    createWorkspace(repoId, "configured", "configured-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "configured-b", "two.txt", "two\n", "the work");

    String commitSha =
        release(repoId, "configured", "the platform says where")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("promotions.branch", equalTo(List.of(entryBranch)))
            .body("promotions.error", everyItem(nullValue()))
            .extract()
            .path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", entryBranch));
  }

  /**
   * A spec whose keys are all the deployer's — or none at all — still says "this deploys". The file
   * is read for its <b>presence</b>, and a repository taking every default has nothing to write in
   * it but a comment, which is exactly the shape the specs took when {@code deploy_branches} went.
   */
  @Test
  public void aSpecOfNothingButCommentsStillDeploys() throws Exception {
    String repoId = seedRepository();
    writeSpec(repoId, "# every default: deployed wherever a tier listens to the built branch.\n");
    createWorkspace(repoId, "comments", "comments-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "comments-b", "three.txt", "three\n", "the work");

    String commitSha =
        release(repoId, "comments", "an empty spec is still a spec")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("promotions.branch", equalTo(List.of(entryBranch)))
            .body("promotions.error", everyItem(nullValue()))
            .extract()
            .path("commitSha");

    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", entryBranch));
  }

  /**
   * A repository with no spec at all deploys from nowhere, and that is the one thing the repository
   * still decides: a library or a component bundle has no deploy ref, and pushing one for it buys a
   * CI build and a branch nobody reads, on every release.
   *
   * <p>The release itself is untouched by it. The trunk push is the whole release here, which is
   * also why it stays CI-hot below.
   */
  @Test
  public void aRepositoryWithNoSpecPromotesNothing() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "library", "library-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "library-b", "lib.txt", "lib\n", "the work");

    String commitSha =
        release(repoId, "library", "a library releases and deploys nothing")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", not(emptyOrNullString()))
            .body("promotions", empty())
            .extract()
            .path("commitSha");

    assertEquals(
        commitSha,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the release is exactly what it was — only the further push is gone");
    assertEquals(
        "",
        inOrigin(
            repoId,
            "git",
            "for-each-ref",
            "--format=%(refname)",
            "refs/heads/environment",
            "refs/heads/platform"),
        "no deploy branch was created");
    assertEquals(1, announcer.announced().size(), "and the release was announced");
  }

  // -----------------------------------------------------------------------------------------
  // the quiet trunk push
  // -----------------------------------------------------------------------------------------

  /**
   * One sha must not build twice. A release with somewhere to promote to pushes the trunk with
   * {@code -o qits.no-ci}, because the entry branch's build is the one that signals anything; a
   * release with nowhere to deploy leaves the trunk push CI-hot, because there that build is the
   * release's only proof.
   *
   * <p>Read off a real {@code pre-receive} hook on the fixture origin rather than off a field, so
   * what is asserted is the argv that ships — the same environment variables the git host's own hook
   * reads.
   */
  @Test
  public void theTrunkPushIsQuietOnlyWhenTheReleaseHasSomewhereToDeploy() throws Exception {
    String deploying = seedRepository();
    declareDeployable(deploying);
    createWorkspace(deploying, "ships", "ships-b");
    TestOrigin.commitOnBranch(dataDir, deploying, "ships-b", "a.txt", "a\n", "the work");

    String library = seedRepository();
    createWorkspace(library, "ships-nowhere", "nowhere-b");
    TestOrigin.commitOnBranch(dataDir, library, "nowhere-b", "b.txt", "b\n", "the work");

    // After every fixture push, so the first recorded line for a ref is the release's own.
    TestOrigin.recordPushOptions(dataDir, deploying);
    TestOrigin.recordPushOptions(dataDir, library);

    release(deploying, "ships", "one sha, one build")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    release(library, "ships-nowhere", "the trunk build is the proof")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    assertEquals(
        List.of("qits.release", "qits.no-ci"),
        TestOrigin.pushOptionsFor(dataDir, deploying, "refs/heads/master"),
        "the deploy branch is about to build this sha, so the trunk push does not");
    assertEquals(
        List.of("qits.release"),
        TestOrigin.pushOptionsFor(dataDir, deploying, "refs/heads/" + entryBranch),
        "the promotion is never quiet — its build is the release's signal");
    assertEquals(
        List.of("qits.release"),
        TestOrigin.pushOptionsFor(dataDir, library, "refs/heads/master"),
        "with nowhere to deploy, the trunk build is the only proof the release is sound");
  }

  // -----------------------------------------------------------------------------------------
  // the refusals
  // -----------------------------------------------------------------------------------------

  /**
   * A conflict releases nothing, and "nothing" is asserted as the default branch being
   * <b>byte-identical</b> rather than as an absence of errors. That is a property of the flow's
   * shape: the merge happens in a detached worktree and the only thing that ever moves a ref is the
   * push, so there is nothing to unwind.
   */
  @Test
  public void aConflictIsA409WithTheFileListAndLeavesTheDefaultBranchByteIdentical()
      throws Exception {
    String repoId = seedRepository();
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "shared.txt", "base\n", "base");
    createWorkspace(repoId, "clash", "clash-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "clash-b", "shared.txt", "theirs\n", "their edit");
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "shared.txt", "ours\n", "our edit");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    release(repoId, "clash", "this will not apply")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("conflict"))
        .body("reason", equalTo("CONFLICT"))
        .body("conflicts", hasItem("shared.txt"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(activeLabels(repoId).contains("clash"), "a refused release resolves nothing");
    assertEquals(List.of(), announcer.announced(), "nothing was released, so nothing is announced");
  }

  /**
   * What a lost 200 looks like on the retry. Release is not idempotent by design — each call
   * stamps a new version, because two releases are two releases — so retry safety comes from the
   * preflight instead: a source that is already an ancestor of the default branch is refused rather
   * than turned into an empty second release.
   */
  @Test
  public void anAlreadyIntegratedBranchIsRefusedRatherThanReleasedAgain() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "twice", "twice-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "twice-b", "done.txt", "done\n", "the work");
    // The branch reaches the default branch by some other route — which is exactly the state a
    // successful release whose response never arrived leaves behind.
    inOrigin(repoId, "git", "branch", "-f", "master", "twice-b");
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    release(repoId, "twice", "already in")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("ALREADY_INTEGRATED"))
        .body("message", containsString("already integrated"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * The push is the compare-and-swap, and this is the case that proves it: the default branch moves
   * at the one instant a race is about, and the loser is <b>rejected</b> rather than silently
   * overwriting. {@code qits.release} is deliberately not granted force, which is what makes that
   * true at the git host too.
   *
   * <p>The two assertions that matter are the 409 and the state of the branch afterwards: it holds
   * the other writer's value exactly, never a mix of the two.
   */
  @Test
  public void aRaceLostAtThePushIsReportedAsNotFastForwardAndReleasesNothing() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "loser", "loser-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "loser-b", "mine.txt", "mine\n", "my work");

    // "feature" diverged from master before master's second commit, so pointing master at it is a
    // move no descendant of the old master can fast-forward over — a real second writer's ref.
    String otherWriter = inOrigin(repoId, "git", "rev-parse", "feature");
    gitHost.beforeNextPush(
        () -> {
          try {
            inOrigin(repoId, "git", "branch", "-f", "master", "feature");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });

    release(repoId, "loser", "the second one")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("NOT_FAST_FORWARD"))
        .body("message", containsString("fast-forward"));

    assertEquals(
        otherWriter,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the default branch holds the winner's commit exactly, never a mix of the two");
    assertEquals(
        "",
        tagsInOrigin(repoId),
        "the push was atomic and the tag lived only in this service's mirror, so a refused branch"
            + " leaves no tag naming a release that did not happen");
    assertTrue(activeLabels(repoId).contains("loser"), "the loser resolved nothing");
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * The version-uniqueness guarantee, at the point it actually fires: the mirror is refreshed from
   * the git host at the top of the flow, so its tags are the host's tags and an existing {@code
   * refs/tags/<version>} refuses the release before the flow has pushed anything at all.
   *
   * <p>Nothing enforced version uniqueness before the tag existed, and the flow's own comment said
   * the fast-forward push did — it does not, because the repository lease makes two releases
   * sequential and the second's push is a clean fast-forward. This is the guarantee that replaced
   * that assumption.
   */
  @Test
  public void aVersionAlreadyTaggedIsRefusedAndReleasesNothing() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "dup", "dup-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "dup-b", "again.txt", "again\n", "the work");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    tagEveryVersionForTheNextTwoMinutes(repoId);

    release(repoId, "dup", "the same second, twice")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("VERSION_ALREADY_RELEASED"))
        .body("message", containsString("already tagged"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(activeLabels(repoId).contains("dup"), "a refused release resolves nothing");
    assertEquals(List.of(), announcer.announced());
    assertEquals(
        masterBefore,
        inOrigin(repoId, "git", "rev-parse", VersionStamp.of(Instant.now()) + "^{commit}"),
        "the host's tags are untouched — a refused release pushes nothing, in either direction");
  }

  /**
   * The same refusal from the other end: the tag appears on the git host after the flow made its
   * own, so receive-pack is what says no. What the assertion is really about is {@code --atomic} —
   * one receive-pack, both commands, and a refused tag takes the branch update down with it.
   *
   * <p>Without it the branch would advance and the release would be untagged: a version on the
   * default branch that source control cannot name, and no refusal anyone would see.
   */
  @Test
  public void aTagThatAppearsBeforeThePushRefusesTheWholeAtomicPush() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "raced", "raced-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "raced-b", "mine.txt", "mine\n", "my work");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    String otherWriter = inOrigin(repoId, "git", "rev-parse", "feature");
    // A second writer tags the very version this run stamped, at the one instant that can still
    // matter: after the flow's own tag exists in its mirror and before the push carries it. The
    // version is read off the commit the flow has already built, in the worktree on that mirror.
    gitHost.beforeNextPush(
        () -> {
          try {
            Path worktree = Path.of(ownDataDir, "worktrees", repoId, "raced-b");
            String subject = TestGit.exec(worktree.toFile(), "git", "log", "-1", "--format=%s").trim();
            String version = subject.substring(subject.indexOf('(') + 1, subject.indexOf(')'));
            inOrigin(repoId, "git", "tag", version, otherWriter);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });

    release(repoId, "raced", "the loser of a tag race")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("VERSION_ALREADY_RELEASED"));

    assertEquals(
        masterBefore,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the tag was refused, so under --atomic the branch did not move either");
    assertEquals(
        otherWriter,
        inOrigin(repoId, "git", "rev-parse", tagsInOrigin(repoId)),
        "the other writer's tag is untouched — this run's finally cleans up only its own");
    assertTrue(activeLabels(repoId).contains("raced"), "a refused release resolves nothing");
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * Two releases of one repository at once, and the two answers a serialized pair may give.
   *
   * <p>The lease is what turns "one of them fails as busy" into "one of them waits" — without it the
   * second would find the first's worktree still checked out — and <b>that</b> is what this asserts:
   * neither answer is a busy 409. What it can no longer assert is that both land. The lease runs
   * them back to back and a release of a small repository is well under a second, so the two often
   * stamp <em>one</em> version, and the tag refuses the second whole. That is the version-uniqueness
   * guarantee doing its job, in the direction the design chose: a retryable refusal rather than two
   * commits claiming one version, which is what happened before the tag existed.
   *
   * <p>So the shape of the assertion is: at least one lands, anything refused is refused as {@code
   * VERSION_ALREADY_RELEASED}, and the tags, the announcements and the release commits all count the
   * same as the releases that landed.
   */
  @Test
  public void twoConcurrentReleasesAreSerializedAndOnlyTheClockCanRefuseOne() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "first", "first-b");
    createWorkspace(repoId, "second", "second-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "first-b", "one.txt", "one\n", "first work");
    TestOrigin.commitOnBranch(dataDir, repoId, "second-b", "two.txt", "two\n", "second work");

    List<io.restassured.response.Response> answers;
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<io.restassured.response.Response>> calls =
          List.of(
              () -> release(repoId, "first", "one of two"),
              () -> release(repoId, "second", "two of two"));
      answers = new java.util.ArrayList<>();
      for (Future<io.restassured.response.Response> result : pool.invokeAll(calls)) {
        answers.add(result.get());
      }
    } finally {
      pool.shutdownNow();
    }

    long landed = answers.stream().filter(answer -> answer.statusCode() == 200).count();
    assertTrue(landed >= 1, "the two are serialized, so the first of them always lands");
    for (var answer : answers) {
      if (answer.statusCode() == 200) {
        continue;
      }
      assertEquals(
          Response.Status.CONFLICT.getStatusCode(),
          answer.statusCode(),
          "the only refusal a serialized pair can produce is the version one");
      assertEquals(
          "VERSION_ALREADY_RELEASED",
          answer.path("reason"),
          "never 'repository is busy' — that is the lease's whole purpose");
    }

    // Whatever landed is fully in: its file, its release commit, its tag, its announcement.
    for (var answer : answers) {
      if (answer.statusCode() != 200) {
        continue;
      }
      String file = "first-b".equals(answer.path("branch")) ? "one.txt" : "two.txt";
      assertEquals(
          file.startsWith("one") ? "one" : "two", inOrigin(repoId, "git", "show", "master:" + file));
    }
    assertEquals(
        landed,
        inOrigin(repoId, "git", "log", "--format=%s", "master").lines()
            .filter(subject -> subject.startsWith("release("))
            .count());
    assertEquals(landed, announcer.announced().size());
    assertEquals(landed, tagsInOrigin(repoId).lines().filter(tag -> !tag.isBlank()).count());
    assertEquals(
        2 - landed,
        activeLabels(repoId).stream().filter(List.of("first", "second")::contains).count(),
        "a released workspace resolves and a refused one does not");
  }

  // -----------------------------------------------------------------------------------------
  // the request
  // -----------------------------------------------------------------------------------------

  @Test
  public void aSummaryIsRequired() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "blank", "blank-b");

    release(repoId, "blank", "  ")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  /** 100 characters, because {@code release(2026.731.193059): } already costs ~24 of a 72 budget. */
  @Test
  public void anOversizedSummaryIsRefused() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "long", "long-b");

    release(repoId, "long", "x".repeat(101))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void anUnknownWorkspaceIs404() {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.ReleaseRequest("nobody home"))
        .when()
        .post("/workspaces/api/workspaces/999999/release")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /**
   * The workspace that sits <em>on</em> the default branch has nothing to release into it. A 400
   * rather than a 409: the request is malformed, not losing a race.
   */
  @Test
  public void theDefaultBranchCannotReleaseIntoItself() throws Exception {
    String repoId = seedRepository();

    release(repoId, "master", "nowhere to go")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
