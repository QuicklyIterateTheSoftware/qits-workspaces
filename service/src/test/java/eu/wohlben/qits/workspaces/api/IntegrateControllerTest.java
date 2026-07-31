package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeGitHostAddress;
import eu.wohlben.qits.workspaces.control.FakeReleaseAnnouncer;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.GitExecutor;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
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
 * {@code POST /workspaces/api/workspaces/{id}/integrate} — the one door into a repository's default
 * branch.
 *
 * <p>Every case here runs against a <b>real bare origin</b> and performs a <b>real {@code git
 * push}</b> into it ({@link FakeGitHostAddress} replaces the transport and nothing else), because
 * the three claims worth making are all claims about git: that the release is one commit with two
 * parents carrying both the merge and the bump, that a failure releases nothing and leaves the
 * default branch byte-identical, and that the push is a genuine compare-and-swap. None of those
 * survives a mocked git.
 *
 * <p>What is deliberately <em>not</em> proven here is the git host's protection hook, which lives in
 * qits-artifacts and is proven there and on the live platform. This suite pushes with the production
 * argv — {@code --push-option=qits.release} — so the fixture has to advertise push options the way
 * JGit does; see {@code TestOrigin}.
 */
@QuarkusTest
public class IntegrateControllerTest {

  /** {@code YYYY.MMDD.HHMMSS}, and no identifier may carry a leading zero. */
  private static final Pattern VERSION =
      Pattern.compile("(?:[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)");

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeGitHostAddress gitHost;
  @Inject FakeReleaseAnnouncer announcer;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject GitExecutor git;

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

  private io.restassured.response.Response integrate(String repoId, String label, String summary) {
    return given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.IntegrateRequest(summary))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, label) + "/integrate");
  }

  private String inOrigin(String repoId, String... argv) throws Exception {
    return git.exec(Path.of(dataDir, repoId, "origin").toFile(), argv).trim();
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
  public void anIntegrateIsOneMergeCommitCarryingBothTheMergeAndTheBump() throws Exception {
    String repoId = seedRepository();
    TestOrigin.commitOnBranch(dataDir, repoId, "master", "pom.xml", POM, "add a pom");
    createWorkspace(repoId, "work", "work-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "work-b", "feature.md", "shipped\n", "the work");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    String sourceTip = inOrigin(repoId, "git", "rev-parse", "work-b");

    var response =
        integrate(repoId, "work", "teach the explorer to group runs by repository")
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

    // The workspace resolved, so the ACTIVE-only listing no longer answers it.
    assertTrue(!activeLabels(repoId).contains("work"), "an integrated workspace leaves the listing");

    // The SoftwareRelease seam: exactly one statement, with everything the future publisher needs.
    assertEquals(1, announcer.announced().size());
    FakeReleaseAnnouncer.Announced announced = announcer.announced().get(0);
    assertEquals(repoId, announced.repoId());
    assertEquals("work-b", announced.branch(), "the SOURCE branch — there is no target field");
    assertEquals(version, announced.version());
    assertEquals(commitSha, announced.commitSha());
    assertNotNull(announced.publishedAt(), "an event with no occurredAt is a 400 on the wire");
  }

  /**
   * A repository with no manifests is still a release. The version comes from the clock regardless
   * of stack; detection only decides which files render it. This is what keeps "integrate is the
   * only flow into the default branch" universal instead of carving out the stub repositories.
   */
  @Test
  public void aRepositoryWithNoVersionFilesIsStillARelease() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "stackless", "stackless-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "stackless-b", "notes.txt", "hi\n", "a note");

    String version =
        integrate(repoId, "stackless", "no manifests here")
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

    integrate(repoId, "clash", "this will not apply")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("conflict"))
        .body("reason", equalTo("CONFLICT"))
        .body("conflicts", hasItem("shared.txt"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(activeLabels(repoId).contains("clash"), "a refused integrate resolves nothing");
    assertEquals(List.of(), announcer.announced(), "nothing was released, so nothing is announced");
  }

  /**
   * What a lost 200 looks like on the retry. Integrate is not idempotent by design — each call
   * stamps a new version, because two integrates are two releases — so retry safety comes from the
   * preflight instead: a source that is already an ancestor of the default branch is refused rather
   * than turned into an empty second release.
   */
  @Test
  public void anAlreadyIntegratedBranchIsRefusedRatherThanReleasedAgain() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "twice", "twice-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "twice-b", "done.txt", "done\n", "the work");
    // The branch reaches the default branch by some other route — which is exactly the state a
    // successful integrate whose response never arrived leaves behind.
    inOrigin(repoId, "git", "branch", "-f", "master", "twice-b");
    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    integrate(repoId, "twice", "already in")
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

    integrate(repoId, "loser", "the second one")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("NOT_FAST_FORWARD"))
        .body("message", containsString("fast-forward"));

    assertEquals(
        otherWriter,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the default branch holds the winner's commit exactly, never a mix of the two");
    assertTrue(activeLabels(repoId).contains("loser"), "the loser resolved nothing");
    assertEquals(List.of(), announcer.announced());
  }

  /**
   * Two integrates of one repository at once. The repository lease is not what makes this safe — the
   * push already is — but it is what turns "one of them fails" into "one of them waits", and this is
   * the test that says so: <b>both</b> come back 200. Without the wait the second would be refused
   * as busy; without any lease at all it would find the first one's worktree still checked out.
   */
  @Test
  public void twoConcurrentIntegratesAreSerializedAndBothLand() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "first", "first-b");
    createWorkspace(repoId, "second", "second-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "first-b", "one.txt", "one\n", "first work");
    TestOrigin.commitOnBranch(dataDir, repoId, "second-b", "two.txt", "two\n", "second work");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Integer>> calls =
          List.of(
              () -> integrate(repoId, "first", "one of two").statusCode(),
              () -> integrate(repoId, "second", "two of two").statusCode());
      List<Future<Integer>> results = pool.invokeAll(calls);
      for (Future<Integer> result : results) {
        assertEquals(200, result.get(), "the lease serializes; neither integrate should be refused");
      }
    } finally {
      pool.shutdownNow();
    }

    // Both releases are in, each as its own merge commit, and both files are on the branch.
    assertEquals("one", inOrigin(repoId, "git", "show", "master:one.txt"));
    assertEquals("two", inOrigin(repoId, "git", "show", "master:two.txt"));
    assertEquals(
        2,
        inOrigin(repoId, "git", "log", "--format=%s", "master").lines()
            .filter(subject -> subject.startsWith("release("))
            .count());
    assertEquals(2, announcer.announced().size());
    assertTrue(
        !activeLabels(repoId).contains("first") && !activeLabels(repoId).contains("second"),
        "both workspaces resolved");
  }

  // -----------------------------------------------------------------------------------------
  // the request
  // -----------------------------------------------------------------------------------------

  @Test
  public void aSummaryIsRequired() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "blank", "blank-b");

    integrate(repoId, "blank", "  ")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  /** 100 characters, because {@code release(2026.731.193059): } already costs ~24 of a 72 budget. */
  @Test
  public void anOversizedSummaryIsRefused() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "long", "long-b");

    integrate(repoId, "long", "x".repeat(101))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void anUnknownWorkspaceIs404() {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.IntegrateRequest("nobody home"))
        .when()
        .post("/workspaces/api/workspaces/999999/integrate")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /**
   * The workspace that sits <em>on</em> the default branch has nothing to integrate into it. A 400
   * rather than a 409: the request is malformed, not losing a race.
   */
  @Test
  public void theDefaultBranchCannotIntegrateIntoItself() throws Exception {
    String repoId = seedRepository();

    integrate(repoId, "master", "nowhere to go")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
