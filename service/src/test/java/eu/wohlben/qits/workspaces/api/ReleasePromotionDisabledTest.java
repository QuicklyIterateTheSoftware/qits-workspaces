package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The off switch: {@code qits.workspaces.release.promotion-branches} blank, and a release is exactly
 * what it was before the promotion existed.
 *
 * <p>Its own class because the key is read at injection and a profile is per class. It is worth a
 * whole app boot for one reason: the promotion is a <b>further push into a repository</b> per deploy
 * branch, and a deployment that has not cut over to deploy branches must be able to switch it off
 * without switching off releases. Blank rather than a boolean, because a promotion with no target
 * branches is not configured — one key answers "where to" and "whether at all" with no way to set
 * them apart.
 *
 * <p><b>The switch outranks the repository</b>, and that is what this fixture is built to show: the
 * repository here <em>declares</em> its deploy branches in {@code .config/qits/deployments.yml}, so
 * a release would promote to them, and the blank key stops it anyway. It belongs to the deployment;
 * a repository that could talk its way past it would leave nothing switched off.
 */
@QuarkusTest
@TestProfile(ReleasePromotionDisabledTest.NoPromotion.class)
public class ReleasePromotionDisabledTest {

  public static class NoPromotion implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // An EMPTY value, which is what a deployment writes to turn this off. SmallRye reads it as
      // "no value", which is what the Optional in ReleaseIntegrator is for.
      return Map.of("qits.workspaces.release.promotion-branches", "");
    }
  }

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;

  @Test
  public void blankPromotionBranchesReleaseAndPromoteNothing() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    // The repository asks to be deployed, in the file that decides it. The switch says no.
    TestOrigin.commitOnBranch(
        dataDir,
        repoId,
        "master",
        ".config/qits/deployments.yml",
        "deploy_branches: environment/prod\n",
        "declare how this deploys");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, "solo", "master", "solo-b", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    TestOrigin.commitOnBranch(dataDir, repoId, "solo-b", "shipped.md", "shipped\n", "the work");
    TestOrigin.recordPushOptions(dataDir, repoId);

    String commitSha =
        given()
            .contentType(ContentType.JSON)
            .body(new WorkspaceController.ReleaseRequest("no deploy branch here"))
            .when()
            .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "solo") + "/release")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("version", not(emptyOrNullString()))
            .body("promotions", empty())
            .extract()
            .path("commitSha");

    assertEquals(
        commitSha,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "the release itself is untouched by the switch");
    assertEquals(
        "",
        inOrigin(
            repoId,
            "git",
            "for-each-ref",
            "--format=%(refname)",
            "refs/heads/environment",
            "refs/heads/platform"),
        "no deploy branch was created — none of the further pushes happened");
    assertEquals(
        java.util.List.of("qits.release"),
        TestOrigin.pushOptionsFor(dataDir, repoId, "refs/heads/master"),
        "and the trunk push stays CI-hot: with the switch on there is no deploy branch to build"
            + " this sha instead");
  }

  private String inOrigin(String repoId, String... argv) throws Exception {
    return TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), argv).trim();
  }
}
