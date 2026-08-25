package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The branch sweep — the nightly gc's door — and, above all, what it must never touch.
 *
 * <p>The fixture is one origin wearing every hat at once: a fully-merged plain branch (the one
 * thing the sweep exists to remove), a fully-merged {@code environment/} ref (merged BY
 * CONSTRUCTION — the exact shape the sweep condemns, protected exactly because of that), a
 * diverged branch (unmerged work), and the main branch. The interesting assertions are the
 * survivors.
 */
@QuarkusTest
public class GcControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;

  private String seedOrigin() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      workspaceService.createMainWorkspace(repoId, "master");
      Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
      // A fully-merged plain branch (a tip that IS master's) and a fully-merged deploy ref.
      TestGit.exec(origin.toFile(), "git", "branch", "merged-work", "master");
      TestGit.exec(origin.toFile(), "git", "branch", "environment/dev", "master");
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  private String originBranches(String repoId) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin").toAbsolutePath();
    return TestGit.exec(
        origin.toFile(), "git", "for-each-ref", "--format=%(refname:short)", "refs/heads");
  }

  private JsonPath sweep(String repoId, boolean dryRun) {
    return given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "dryRun", dryRun,
                "repositories",
                    List.of(Map.of("id", repoId, "name", "test-repo", "mainBranch", "master")),
                "keepPrefixes", List.of()))
        .when()
        .post("/workspaces/api/gc/branches")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  @Test
  public void aDryRunNamesTheMergedBranchAndDeletesNothing() throws Exception {
    String repoId = seedOrigin();

    JsonPath report = sweep(repoId, true);

    assertThat(report.getBoolean("dryRun"), is(true));
    assertThat(report.getList("removed.branch", String.class), hasItem("merged-work"));
    assertThat(report.getList("removed.branch", String.class), not(hasItem("environment/dev")));
    assertThat(report.getList("removed.branch", String.class), not(hasItem("feature")));
    assertThat(report.getList("removed.branch", String.class), not(hasItem("master")));
    // Named, not touched: the ref is still there.
    assertThat(originBranches(repoId), containsString("merged-work"));
  }

  @Test
  public void aRealRunRemovesOnlyTheMergedPlainBranch() throws Exception {
    String repoId = seedOrigin();

    JsonPath report = sweep(repoId, false);

    assertThat(report.getList("removed.branch", String.class), hasItem("merged-work"));
    assertThat(report.getList("errors"), is(List.of()));

    String survivors = originBranches(repoId);
    assertThat(survivors, not(containsString("merged-work")));
    // The three protections, each surviving for its own reason: the deploy ref (prefix), the
    // diverged branch (unmerged commits), and the main branch itself.
    assertThat(survivors, containsString("environment/dev"));
    assertThat(survivors, containsString("feature"));
    assertThat(survivors, containsString("master"));
  }

  @Test
  public void aCallerMayWidenTheProtectionButNeverNarrowIt() throws Exception {
    String repoId = seedOrigin();

    JsonPath report =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "dryRun", true,
                    "repositories",
                        List.of(Map.of("id", repoId, "name", "test-repo", "mainBranch", "master")),
                    "keepPrefixes", List.of("merged-")))
            .when()
            .post("/workspaces/api/gc/branches")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertThat(report.getList("removed"), is(List.of()));
  }

  @Test
  public void anUnknownRepositoryIsAnErrorEntryAndTheSweepGoesOn() throws Exception {
    String repoId = seedOrigin();

    JsonPath report =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "dryRun", true,
                    "repositories",
                        List.of(
                            Map.of(
                                "id", "00000000-0000-4000-8000-000000000000",
                                "name", "gone",
                                "mainBranch", "main"),
                            Map.of("id", repoId, "name", "test-repo", "mainBranch", "master")),
                    "keepPrefixes", List.of()))
            .when()
            .post("/workspaces/api/gc/branches")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    // The broken repository is an error entry; the healthy one was still examined and judged.
    assertThat(report.getList("errors.repositoryId", String.class).size(), is(1));
    assertThat(report.getList("removed.branch", String.class), hasItem("merged-work"));
  }
}
