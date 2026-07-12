package eu.wohlben.qits.domain.repository.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(WorkspaceControllerTest.TestProfile.class)
public class WorkspaceControllerTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  // The effective data dir the app uses, so tests can commit directly inside a workspace on disk.
  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private final String fixtureUrl;

  public WorkspaceControllerTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  private String createProjectAndRepository() {
    String projectId =
        given()
            .contentType(ContentType.JSON)
            .body(
                new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRequest(
                    "Workspace Project", null))
            .when()
            .post("/api/projects")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .extract()
            .path("project.id");

    return given()
        .contentType(ContentType.JSON)
        .body(
            new eu.wohlben.qits.domain.project.api.ProjectController.CreateProjectRepositoryRequest(
                fixtureUrl, null))
        .when()
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("repository.id");
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
  public void testFastForwardAdvancesBranchToParent() {
    String repoId = createProjectAndRepository();

    // parent-wt owns parent-branch (== master); child-wt forks child-branch off it, so the two
    // start at the same commit.
    createWorkspace(repoId, "parent-wt", "master", "parent-branch");
    createWorkspace(repoId, "child-wt", "parent-branch", "child-branch");

    // Advance parent-branch by merging the (diverged) feature branch into it. child-branch now
    // lags strictly behind parent-branch with no commits of its own — a clean fast-forward.
    createWorkspace(repoId, "src-wt", "feature", "src-branch");
    mergeInto(repoId, "src-wt", "parent-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'child-wt' }.workspace.behind",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'child-wt' }.workspace.ahead", equalTo(0));

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/child-wt/fast-forward")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // After fast-forwarding, child-branch sits on parent-branch's tip: no longer ahead or behind.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'child-wt' }.workspace.behind", equalTo(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'child-wt' }.workspace.ahead", equalTo(0));
  }

  @Test
  public void testFastForwardRejectsDivergedBranch() {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "dv-parent", "master", "dv-parent-branch");
    createWorkspace(repoId, "dv-child", "dv-parent-branch", "dv-child-branch");
    createWorkspace(repoId, "dv-src", "feature", "dv-src-branch");

    // Merge feature into both branches independently: each gets its own merge commit, so the
    // child branch ends up both ahead of and behind its parent — a fast-forward can't apply.
    mergeInto(repoId, "dv-src", "dv-parent-branch");
    mergeInto(repoId, "dv-src", "dv-child-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/dv-child/fast-forward")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
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

  @Test
  public void testUpdateFromParentMergesDivergedBranch() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "up-parent", "master", "up-parent-branch");
    createWorkspace(repoId, "up-child", "up-parent-branch", "up-child-branch");

    // Diverge cleanly: each branch adds its own distinct file.
    commitFile(repoId, "up-parent", "parent-only.txt", "from parent\n", "parent commit");
    commitFile(repoId, "up-child", "child-only.txt", "from child\n", "child commit");

    // A fast-forward can't apply (the child has its own commit), but a merge can.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/up-child/update-from-parent")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // After merging the parent in, the child contains the parent's commit, so it's no longer
    // behind.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'up-child' }.workspace.behind", equalTo(0));
  }

  @Test
  public void testUpdateFromParentRejectsConflictAndLeavesWorkspaceUsable() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "uc-parent", "master", "uc-parent-branch");
    createWorkspace(repoId, "uc-child", "uc-parent-branch", "uc-child-branch");

    // Both edit the same line: a merge of the parent into the child would conflict.
    commitFile(repoId, "uc-parent", "conflict.txt", "parent version\n", "parent edit");
    commitFile(repoId, "uc-child", "conflict.txt", "child version\n", "child edit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/uc-child/update-from-parent")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

    // The aborted merge must leave the workspace exactly as it was: still diverged, not mid-merge.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'uc-child' }.workspace.behind",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'uc-child' }.workspace.ahead",
            greaterThan(0));
  }

  @Test
  public void testIncomingCommitsListsParentCommitsNotInBranch() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "in-parent", "master", "in-parent-branch");
    createWorkspace(repoId, "in-child", "in-parent-branch", "in-child-branch");

    // Advance the parent branch by one commit; the child forked before it, so it's now behind by 1.
    commitFile(repoId, "in-parent", "p.txt", "p\n", "incoming parent commit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/in-child/incoming-commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("branch", equalTo("in-child-branch"))
        .body("parent", equalTo("in-parent-branch"))
        // the commit waiting on the parent that a fast-forward/merge would pull in
        .body("commits.message", hasItem("incoming parent commit"));
  }

  @Test
  public void testIncomingCommitsEmptyWhenUpToDate() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "ut-wt", "master", "ut-branch");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/ut-wt/incoming-commits")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commits", hasSize(0));
  }

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
        .statusCode(Response.Status.OK.getStatusCode());
    return Path.of(dataDir, repoId, "workspaces", workspaceId);
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
  public void testListFilesIncludesTrackedAndNewUntrackedFiles() throws Exception {
    String repoId = createProjectAndRepository();
    // The default main workspace is checked out at "master".
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.writeString(workspacePath.resolve("browse-me.txt"), "hello\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        // a brand-new untracked file shows up (ls-files --others), sorted alongside tracked ones
        .body("paths", hasItem("browse-me.txt"));
  }

  @Test
  public void testFileContentReturnsText() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.writeString(workspacePath.resolve("readme.md"), "# Title\n\nbody\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files/content?path=readme.md")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("path", equalTo("readme.md"))
        .body("binary", equalTo(false))
        .body("content", equalTo("# Title\n\nbody\n"));
  }

  @Test
  public void testFileContentDetectsBinary() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // A NUL byte marks the file as binary; the viewer gets no content.
    Files.write(workspacePath.resolve("blob.bin"), new byte[] {1, 2, 0, 3, 4});

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files/content?path=blob.bin")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("binary", equalTo(true))
        .body("content", nullValue());
  }

  @Test
  public void testFileContentRejectsPathTraversal() {
    String repoId = createProjectAndRepository();
    // `path` is user-supplied; a `..` escape out of the workspace root is rejected.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(
            "/api/repositories/"
                + repoId
                + "/workspaces/master/files/content?path=../origin/config")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testFileContentRejectsSymlinkEscape() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // A cloned repo is untrusted: a symlink committed inside the workspace that points outside it
    // must not be followed when reading (path traversal via symlink).
    Path secret = Files.createTempFile("qits-secret", ".txt");
    Files.writeString(secret, "top secret");
    Files.createSymbolicLink(workspacePath.resolve("escape-link"), secret);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files/content?path=escape-link")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testFileContentRejectsIntermediateSymlinkEscape() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // A symlinked *directory* committed inside the workspace is transparently followed during path
    // resolution, so a request whose intermediate segment is that link escapes the workspace even
    // though the final segment is an ordinary file. The read must be rejected (path traversal via
    // an
    // intermediate symlink, not just the final component).
    Path outside = Files.createTempDirectory("qits-outside");
    Files.writeString(outside.resolve("secret.txt"), "top secret");
    Files.createSymbolicLink(workspacePath.resolve("escape-dir"), outside);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(
            "/api/repositories/"
                + repoId
                + "/workspaces/master/files/content?path=escape-dir/secret.txt")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testListFilesRejectsIntermediateSymlinkEscape() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // Same escape via an intermediate symlinked directory, but for a listing: the final segment
    // resolves to a real directory outside the workspace, which must not be walked.
    Path outside = Files.createTempDirectory("qits-outside");
    Files.createDirectories(outside.resolve("nested"));
    Files.createSymbolicLink(workspacePath.resolve("escape-dir"), outside);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=escape-dir/nested")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
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
  public void testListFilesReturnsGitignoredDirectoryAsLazyStub() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.writeString(workspacePath.resolve(".gitignore"), "node_modules/\n");
    Files.createDirectories(workspacePath.resolve("node_modules/pkg"));
    Files.writeString(workspacePath.resolve("node_modules/top.js"), "x\n");
    Files.writeString(workspacePath.resolve("node_modules/pkg/index.js"), "y\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        // the ignored dir is a collapsed stub, not walked into: no node_modules contents in paths
        .body("paths", not(hasItem(startsWith("node_modules"))))
        // …but present as a lazy stub with a cheap immediate-child count and a self-referential
        // href
        .body("lazyDirs.path", hasItem("node_modules"))
        .body("lazyDirs.find { it.path == 'node_modules' }.childCount", equalTo(2))
        .body(
            "lazyDirs.find { it.path == 'node_modules' }.href",
            containsString("path=node_modules"));
  }

  @Test
  public void testListLazyDirectoryContentsOneLevelDeep() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.writeString(workspacePath.resolve(".gitignore"), "node_modules/\n");
    Files.createDirectories(workspacePath.resolve("node_modules/pkg"));
    Files.writeString(workspacePath.resolve("node_modules/top.js"), "x\n");
    Files.writeString(workspacePath.resolve("node_modules/pkg/index.js"), "y\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=node_modules")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        // immediate regular files are eager; the nested subdir stays lazy
        .body("paths", hasItem("node_modules/top.js"))
        .body("paths", not(hasItem("node_modules/pkg/index.js")))
        .body("lazyDirs.path", hasItem("node_modules/pkg"))
        .body("lazyDirs.find { it.path == 'node_modules/pkg' }.childCount", equalTo(1));
  }

  @Test
  public void testListFilesRejectsPathTraversal() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=../origin")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testListFilesRejectsSymlinkDirectoryEscape() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // A symlinked directory committed inside the workspace must not redirect the listing outside
    // it.
    Path outside = Files.createTempDirectory("qits-outside");
    Files.createSymbolicLink(workspacePath.resolve("escape-dir"), outside);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=escape-dir")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testListFilesRejectsNonDirectory() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.writeString(workspacePath.resolve("a-file.txt"), "hi\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=a-file.txt")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testListFilesRejectsGitDirectory() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/api/repositories/" + repoId + "/workspaces/master/files?path=.git")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
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

    // First use provisions the container from the durable branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("RUNNING"));

    // Graceful stop removes the container but keeps the workspace active (STOPPED).
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/stop-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("STOPPED"));

    // Ensure re-provisions from the durable branch — the container is back and RUNNING.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/api/repositories/" + repoId + "/workspaces/wt-run/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("RUNNING"));
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

  @Test
  public void testComponentMapScansInlineAndExternalTemplateComponents() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    Files.createDirectories(workspacePath.resolve("src/app/detail"));
    Files.writeString(workspacePath.resolve("src/app/greeting.ts"), INLINE_COMPONENT);
    Files.writeString(
        workspacePath.resolve("src/app/detail/detail.ts"),
        """
        @Component({
          selector: 'app-detail, [appDetail]',
          templateUrl: './detail.html',
          styleUrls: ['./detail.scss'],
        })
        export class Detail {}
        """);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("framework", equalTo("angular"))
        .body("components", hasSize(2))
        // the inline-template component carries only its .ts file
        .body(
            "components.find { it.className == 'Greeting' }.componentFile",
            equalTo("src/app/greeting.ts"))
        .body("components.find { it.className == 'Greeting' }.templateFile", nullValue())
        .body("components.find { it.className == 'Greeting' }.styleFiles", hasSize(0))
        .body(
            "components.find { it.className == 'Greeting' }.selectors[0].element",
            equalTo("app-greeting"))
        // external refs resolve relative to the component file; the multi-selector is structured
        .body(
            "components.find { it.className == 'Detail' }.templateFile",
            equalTo("src/app/detail/detail.html"))
        .body(
            "components.find { it.className == 'Detail' }.styleFiles",
            contains("src/app/detail/detail.scss"))
        .body(
            "components.find { it.className == 'Detail' }.selectors[0].element",
            equalTo("app-detail"))
        .body(
            "components.find { it.className == 'Detail' }.selectors[1].attribute",
            equalTo("appDetail"));
  }

  @Test
  public void testComponentMapEmptyForRepoWithoutComponents() {
    String repoId = createProjectAndRepository();

    // the fixture repo has no TypeScript at all — git grep matches nothing (exit 1), which must
    // surface as an empty map, never an error
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("framework", equalTo("angular"))
        .body("components", hasSize(0));
  }

  @Test
  public void testComponentMapPicksUpNewUntrackedComponent() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");

    // prime the cache with an empty scan…
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("components", hasSize(0));

    // …then a brand-new (untracked, uncommitted) component must invalidate it and appear
    Files.writeString(workspacePath.resolve("fresh.ts"), INLINE_COMPONENT);

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("components.className", hasItem("Greeting"));
  }

  @Test
  public void testComponentMapReflectsEditToDirtyTrackedFile() throws Exception {
    String repoId = createProjectAndRepository();
    commitFile(repoId, "master", "tracked.ts", INLINE_COMPONENT, "add component");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "components.find { it.className == 'Greeting' }.selectors[0].element",
            equalTo("app-greeting"));

    // an uncommitted edit to the tracked file must invalidate the cached scan (git diff marker)
    Path workspacePath = Path.of(dataDir, repoId, "workspaces", "master");
    Files.writeString(
        workspacePath.resolve("tracked.ts"),
        INLINE_COMPONENT.replace("app-greeting", "app-renamed"));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "components.find { it.className == 'Greeting' }.selectors[0].element",
            equalTo("app-renamed"));
  }

  @Test
  public void testComponentMapExcludesSpecFiles() throws Exception {
    String repoId = createProjectAndRepository();
    Path workspacePath = ensuredWorkspacePath(repoId, "master");
    // test-host components in specs would pollute the map
    Files.writeString(
        workspacePath.resolve("greeting.spec.ts"),
        INLINE_COMPONENT.replace("Greeting", "TestHost"));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(componentMapUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("components", hasSize(0));
  }

  private String detectionUrl(String repoId) {
    return "/api/repositories/" + repoId + "/workspaces/master/detection";
  }

  /**
   * The whole feature in one call: a Java project (pom-refined to Quarkus) nested with a Vitest
   * Angular project, returning projects, per-framework membership, and the source→test link graph
   * with runner kinds — all resolved server-side over the live working tree.
   */
  @Test
  public void testDetectionReturnsProjectsMembershipAndLinksInOneCall() throws Exception {
    String repoId = createProjectAndRepository();
    Path ws = ensuredWorkspacePath(repoId, "master");
    Files.createDirectories(ws.resolve("src/main/java/com"));
    Files.createDirectories(ws.resolve("src/test/java/com"));
    Files.createDirectories(ws.resolve("web/src"));
    // a Java/Quarkus project at the repo root
    Files.writeString(
        ws.resolve("pom.xml"),
        "<project><dependency><groupId>io.quarkus</groupId></dependency></project>\n");
    Files.writeString(ws.resolve("src/main/java/com/App.java"), "package com; class App {}\n");
    Files.writeString(
        ws.resolve("src/test/java/com/AppTest.java"), "package com; class AppTest {}\n");
    // a nested Angular project whose test builder is Vitest (must NOT default to karma)
    Files.writeString(
        ws.resolve("web/angular.json"),
        "{ \"projects\": { \"app\": { \"architect\": { \"test\": { \"builder\":"
            + " \"@angular/build:unit-test\" } } } } }\n");
    Files.writeString(ws.resolve("web/src/foo.ts"), "export const foo = 1;\n");
    Files.writeString(ws.resolve("web/src/foo.spec.ts"), "describe('foo', () => {});\n");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get(detectionUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        // projects: the Java root pom-refined to Quarkus, plus the nested Angular root
        .body("projects.find { it.frameworkId == 'java-quarkus' }.label", equalTo("Java / Quarkus"))
        .body("projects.find { it.frameworkId == 'java-quarkus' }.root", equalTo(""))
        .body("projects.find { it.root == 'web' }.frameworkId", equalTo("ts-angular"))
        // membership: all detected frameworks' member sets, in the one call
        .body(
            "frameworks.find { it.frameworkId == 'java-quarkus' }.memberPaths",
            hasItems("pom.xml", "src/main/java/com/App.java", "src/test/java/com/AppTest.java"))
        .body(
            "frameworks.find { it.root == 'web' }.memberPaths",
            hasItems("web/angular.json", "web/src/foo.ts", "web/src/foo.spec.ts"))
        // links: Java source → its JUnit test
        .body(
            "links.find { it.path == 'src/main/java/com/App.java' }.tests[0].path",
            equalTo("src/test/java/com/AppTest.java"))
        .body(
            "links.find { it.path == 'src/main/java/com/App.java' }.tests[0].kinds",
            contains("junit"))
        .body("links.find { it.path == 'src/main/java/com/App.java' }.projectRoot", equalTo(""))
        // links: Angular source → its Vitest spec (config-detected, not a karma default)
        .body(
            "links.find { it.path == 'web/src/foo.ts' }.tests[0].path",
            equalTo("web/src/foo.spec.ts"))
        .body("links.find { it.path == 'web/src/foo.ts' }.tests[0].kinds", contains("vitest"));
  }

  /**
   * Render-consistency: {@code /files} and {@code /detection}, over the same unchanged tree, agree
   * on the generation token, so the client applies detection only while it matches the tree it
   * renders.
   */
  @Test
  public void testFilesAndDetectionShareTheGenerationToken() {
    String repoId = createProjectAndRepository();
    String filesUrl = "/api/repositories/" + repoId + "/workspaces/master/files";

    String filesGeneration =
        given()
            .contentType(ContentType.JSON)
            .when()
            .get(filesUrl)
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("generation", not(emptyOrNullString()))
            .extract()
            .path("generation");

    // /detection stamps the identical token so the two never render as a skewed combination.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(detectionUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("generation", equalTo(filesGeneration));
  }

  @Test
  public void testDetectionIsEmptyForARepoWithNoRecognisedFramework() {
    String repoId = createProjectAndRepository();

    // the fixture repo has no pom.xml / angular.json / docs dir — detection yields empty lists,
    // never an error, and /files stays a pure String[] transport
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(detectionUrl(repoId))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("projects", hasSize(0))
        .body("frameworks", hasSize(0))
        .body("links", hasSize(0));
  }
}
