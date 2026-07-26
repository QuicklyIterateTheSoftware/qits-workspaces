package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.workspaces.control.TestOrigin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkspaceControllerTest {

  // The effective data dir the app uses, so tests can commit directly inside a workspace on disk.
  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.FakeRepositoryLookup repositories;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.WorkspaceService workspaceService;

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture. Those routes belong to the projects and repositories contexts and are not part of this
   * jar, so the same state is set up directly instead — the endpoints under test here are the
   * workspace ones, not the seeding ones.
   */
  private String createProjectAndRepository() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      workspaceService.createMainWorkspace(repoId, "master");
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  @Test
  public void testCreateWorkspaceAndMergeAndDiscard() {
    String repoId = createProjectAndRepository();

    // fork a new branch "step-work" from the feature branch
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest("step-01", "feature", "step-work", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.workspaceId", equalTo("step-01"));

    // merge the workspace's branch into master
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("master"))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/step-01/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    // discard
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/step-01/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }

  @Test
  public void testListWorkspacesReturnsCreatedWorkspaceWithBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest("wt-list", "master", "wt-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem("wt-list"))
        // branch is the workspace's own forked branch, resolved from the on-disk workspace
        // (also a regression guard for the path fix).
        .body(
            "entries.find { it.workspace.workspaceId == 'wt-list' }.workspace.branch",
            equalTo("wt-branch"));
  }

  @Test
  public void testTwoWorkspacesCanForkFromTheSameParentBranch() {
    String repoId = createProjectAndRepository();

    // Two workspaces forking new branches from the same parent must not conflict —
    // the old behaviour (checking out an existing branch) made this impossible.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("fork-a", "master", "branch-a", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("fork-b", "master", "branch-b", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'fork-a' }.workspace.branch",
            equalTo("branch-a"))
        .body(
            "entries.find { it.workspace.workspaceId == 'fork-b' }.workspace.branch",
            equalTo("branch-b"));
  }

  @Test
  public void testCreateWorkspaceAdoptsExistingBranch() {
    String repoId = createProjectAndRepository();

    // `feature` already exists in the fixture with no workspace. Adopting it must succeed (no
    // duplicate `git branch`) and record a workspace that owns that branch, parented on main.
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                "feature-ws", "master", "feature", null, true))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.workspaceId", equalTo("feature-ws"))
        // branch is resolved only in the list path (the create DTO mapper ignores it), so the
        // adopted-branch assertion lives on the list call below.
        .body("workspace.parent", equalTo("master"));

    // It shows up in the list bound to the adopted branch (not a fresh fork).
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'feature-ws' }.workspace.branch",
            equalTo("feature"));
  }

  @Test
  public void testCreateWorkspaceRejectsExistingBranchWhenNotAdopting() {
    String repoId = createProjectAndRepository();

    // Without adoptExisting, a workspace whose branch already exists must fail loudly rather than
    // silently adopt it — a typo'd "branch off" name is a real error, not an adoption.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("dup-ws", "master", "feature", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
  }

  @Test
  public void testListWorkspacesReportsCommitsAheadAndBehindParent() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest("ab-wt", "master", "ab-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // A workspace freshly forked from its parent has made no commits yet, so it is
    // neither ahead of nor behind the parent branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.workspace.workspaceId == 'ab-wt' }.workspace.ahead", equalTo(0))
        .body("entries.find { it.workspace.workspaceId == 'ab-wt' }.workspace.behind", equalTo(0));
  }

  private void createWorkspace(String repoId, String id, String parent, String branch) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(id, parent, branch, null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private void mergeInto(String repoId, String workspaceId, String target) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest(target))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }



  @Test
  public void testDivergedButCleanWorkspaceReportsNoConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "clean-parent", "master", "clean-parent-branch");
    createWorkspace(repoId, "clean-child", "clean-parent-branch", "clean-child-branch");

    // Both branches add their own distinct file, so each is ahead of and behind the other, yet
    // merging the parent in applies cleanly — divergence without a conflict.
    commitFile(repoId, "clean-parent", "parent-only.txt", "from parent\n", "parent commit");
    commitFile(repoId, "clean-child", "child-only.txt", "from child\n", "child commit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.ahead",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.behind",
            greaterThan(0))
        // diverged but a merge would apply cleanly → no conflict warning
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.conflictsWithParent",
            equalTo(false));
  }

  @Test
  public void testDivergedConflictingWorkspaceReportsConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "cf-parent", "master", "cf-parent-branch");
    createWorkspace(repoId, "cf-child", "cf-parent-branch", "cf-child-branch");

    // Both branches change the same line of the same file to different values, so a merge of the
    // parent into the child can't apply without manual resolution.
    commitFile(repoId, "cf-parent", "conflict.txt", "parent version\n", "parent edit");
    commitFile(repoId, "cf-child", "conflict.txt", "child version\n", "child edit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.ahead",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.behind",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.conflictsWithParent",
            equalTo(true));
  }


  // MOVED with the routes: the four fast-forward / update-from-parent cases.
  //
  //   testFastForwardAdvancesBranchToParent
  //   testFastForwardRejectsDivergedBranch
  //   testUpdateFromParentMergesDivergedBranch
  //   testUpdateFromParentRejectsConflictAndLeavesWorkspaceUsable
  //
  // POST /{workspaceId}/fast-forward and /update-from-parent are now workspace-daemon routes -- the
  // git they drove ran `docker exec` inside the container the daemon owns. These are real
  // behavioural cases (ff-only refuses divergence; a conflicting merge aborts and leaves the
  // workspace usable) and they should be re-asserted against the daemon's HTTP API, not lost.




  /**
   * Writes a file inside the workspace on disk, commits it on the workspace's branch, and pushes.
   * The workspace is a container-style clone, so a commit stays local until pushed; the origin-side
   * ahead/behind, conflict and incoming-commits probes only see pushed commits.
   */
  /**
   * Provisions the workspace's container (creation is lazy — nothing exists to write into until
   * first use) and returns the fake runtime's host-clone path for it, for tests that touch the
   * working tree directly.
   */
  private Path ensuredWorkspacePath(String repoId, String workspaceId) {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    // ensure-container provisions asynchronously now (it returns a technical-process id and the
    // work streams over SSE), so wait for the provision to finish before touching the working
    // tree.
    awaitProvisioned(repoId, workspaceId);
    return Path.of(dataDir, repoId, "workspaces", workspaceId);
  }

  /**
   * Polls until the workspace's technical process completed — i.e. the async provision (and any
   * service auto-start) is fully done. Deliberately NOT a runtime-status poll: the workspace list
   * computes RUNNING live from the container set, which reports the container as soon as {@code
   * docker run} happened — while the clone is still writing the working tree (and its final index
   * write would clobber a concurrent {@code git add} from the test).
   */
  private void awaitProvisioned(String repoId, String workspaceId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      String processId =
          given()
              .when()
              .get("/api/repositories/" + repoId + "/workspaces/" + workspaceId + "/active-process")
              .then()
              .statusCode(Response.Status.OK.getStatusCode())
              .extract()
              .path("technicalProcessId");
      if (processId == null) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("workspace " + workspaceId + " provision never completed");
  }

  private void commitFile(
      String repoId, String workspaceId, String file, String content, String msg) throws Exception {
    Path workspacePath = ensuredWorkspacePath(repoId, workspaceId);
    Files.writeString(workspacePath.resolve(file), content);
    runGit(workspacePath, "git", "add", file);
    runGit(
        workspacePath,
        "git",
        "-c",
        "user.email=test@example.com",
        "-c",
        "user.name=Test",
        "commit",
        "-m",
        msg);
    runGit(workspacePath, "git", "push", "origin", "HEAD");
  }

  private void runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0) {
      String out = new String(p.getInputStream().readAllBytes());
      throw new RuntimeException("git " + String.join(" ", command) + " failed: " + out);
    }
  }

  @Test
  public void testFreshRepositoryHasADefaultMainWorkspace() {
    String repoId = createProjectAndRepository();

    // Adding a repository now checks out its main branch in a default workspace (a root with no
    // parent), so the workspace list is never empty for a fresh repo.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries", hasSize(1))
        .body("entries[0].workspace.branch", equalTo("master"))
        .body("entries[0].workspace.workspaceId", equalTo("master"))
        .body("entries[0].workspace.parent", nullValue());
  }








  @Test
  public void testFileContentMissingFileReturns404() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(
            "/api/repositories/"
                + repoId
                + "/workspaces/master/files/content?path=does-not-exist.txt")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }







  @Test
  public void testCreateWorkspaceRejectsPathTraversalAndFlagIds() {
    String repoId = createProjectAndRepository();
    // A workspace id becomes a path segment + git operand; slashes/dots/leading-dash are rejected.
    for (String badId : new String[] {"../escape", "a/b", "-D", "."}) {
      given()
          .contentType(ContentType.JSON)
          .body(new WorkspaceController.CreateWorkspaceRequest(badId, "master", "wt-branch", null))
          .when()
          .post("/api/repositories/" + repoId + "/workspaces")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @Test
  public void stopThenEnsureContainerRoundTripsTheRuntimeStatus() {
    String repoId = createProjectAndRepository();

    // Creation is lazy: the fresh workspace has no container yet, so it reports STOPPED.
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest("wt-run", "master", "run-branch", null))
        .when()
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.runtimeStatus", equalTo("STOPPED"));

    // First use provisions the container from the durable branch (asynchronously — the response
    // carries the technical-process id; completion is awaited via /active-process).
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    awaitProvisioned(repoId, "wt-run");
    assertRuntimeStatus(repoId, "wt-run", "RUNNING");

    // Graceful stop removes the container but keeps the workspace active (STOPPED).
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/stop-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("STOPPED"));

    // Ensure re-provisions from the durable branch — the container comes back RUNNING.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    awaitProvisioned(repoId, "wt-run");
    assertRuntimeStatus(repoId, "wt-run", "RUNNING");
  }

  @Test
  public void recreateContainerRejectsAWorkspaceWhoseCleanlinessIsUnknown() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "rc-unknown", "master", "rc-unknown-branch");

    // No workspace-daemon is connected in a @QuarkusTest, so the registry reports the working tree
    // as UNKNOWN — recreate must refuse it (unknown is not a safe basis to destroy a container),
    // surfacing the domain BadRequestException as a 400.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/rc-unknown/recreate-container")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void recreateContainerIs404ForAnUnknownWorkspace() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/no-such-ws/recreate-container")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /** Asserts the workspace's runtime status through the list endpoint. */
  private void assertRuntimeStatus(String repoId, String workspaceId, String expected) {
    given()
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == '"
                + workspaceId
                + "' }"
                + ".workspace.runtimeStatus",
            equalTo(expected));
  }

  private static final String INLINE_COMPONENT =
      """
      import { Component } from '@angular/core';

      @Component({
        selector: 'app-greeting',
        template: `
          @if (greeting(); as g) {
            <h1>Hello, {{ g.name }}!</h1>
          }
        `,
      })
      export class Greeting {}
      """;

  private String componentMapUrl(String repoId) {
    return "/api/repositories/" + repoId + "/workspaces/master/component-map";
  }






  private String detectionUrl(String repoId) {
    return "/api/repositories/" + repoId + "/workspaces/master/detection";
  }




}
