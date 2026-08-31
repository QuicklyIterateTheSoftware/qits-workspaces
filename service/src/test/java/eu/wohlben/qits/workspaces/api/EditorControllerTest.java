package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The editor door: one idempotent route that a client polls and reads four scalars off.
 *
 * <p>The contract under test is the whole of what the SPA depends on — the query-parameter scope, the
 * bare (envelope-free) body, the row id as a String, 201 for a start, and {@code editorReady} false
 * while nothing has reported. The last of those is not a placeholder: until the daemon's {@code
 * EditorState} frame reaches the registry there is no report to have, and a door that claimed
 * readiness anyway would send a reader to an origin that answers nothing.
 *
 * <p>The 201 → 200 transition is {@code EditorServiceTest}'s rather than this file's: whether a
 * second call starts anything depends on the container being up with its daemon on the socket, and
 * no {@code @QuarkusTest} here has one — the runtime is faked and nothing dials home.
 */
@QuarkusTest
public class EditorControllerTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private String wrapperRepository(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    return repoId;
  }

  @Test
  public void theFirstCallStartsTheEditorAndTheSecondFindsIt() throws Exception {
    String repoId = wrapperRepository("doorproj");

    // 201: this call started something. The body is BARE — four scalars, no envelope — because the
    // client polls it every two seconds and reads them directly.
    String workspaceId =
        given()
            .contentType(ContentType.JSON)
            .queryParam("repositoryId", repoId)
            .body("{}")
            .when()
            .post("/workspaces/api/editor/ensure")
            .then()
            .statusCode(201)
            .body("workspaceId", notNullValue())
            .body("containerStatus", notNullValue())
            // Nothing has reported: no daemon frame reaches the registry yet, so the state is null
            // and the readiness is false. A client waits, which is exactly right.
            .body("editorState", nullValue())
            .body("editorReady", equalTo(false))
            .extract()
            .path("workspaceId");

    // The id is the workspace ROW id as a String — the identity /stop-container and
    // /recreate-container address. A branch label here would 404 both of them.
    assertEquals(
        "master",
        workspaceService.getWorkspace(Long.valueOf(workspaceId)).workspaceId(),
        "the door names the wrapper's main workspace");

    // Said again, it finds what the first call made rather than making a second one. The row is
    // keyed on the branch it claims, so idempotence is structural and not a guard.
    //
    // The STATUS is deliberately not asserted here. `fresh` means "this call started something",
    // and whether the second one does depends on the container being up with its daemon on the
    // socket — which no @QuarkusTest has, since the runtime is faked and nothing dials home. The
    // 201 → 200 transition is EditorServiceTest's, where the liveness port can be told what to say.
    given()
        .contentType(ContentType.JSON)
        .queryParam("repositoryId", repoId)
        .body("{}")
        .when()
        .post("/workspaces/api/editor/ensure")
        .then()
        .body("workspaceId", equalTo(workspaceId))
        .body("editorReady", equalTo(false));
  }

  @Test
  public void aRepositoryThatIsNotAWrapperIsRefusedRatherThanStarted() throws Exception {
    // A caller sent at the wrong repository would otherwise get a perfectly ordinary workspace and
    // poll it to ready forever — it runs the plain image, so no editor can ever report.
    String repoId = TestOrigin.create(dataDir);
    repositories.registerAs(repoId, "master", "SERVICE");

    given()
        .contentType(ContentType.JSON)
        .queryParam("repositoryId", repoId)
        .body("{}")
        .when()
        .post("/workspaces/api/editor/ensure")
        .then()
        .statusCode(400);
  }

  @Test
  public void anUnknownRepositoryIs404() {
    given()
        .contentType(ContentType.JSON)
        .queryParam("repositoryId", "no-such-repository")
        .body("{}")
        .when()
        .post("/workspaces/api/editor/ensure")
        .then()
        .statusCode(404);
  }
}
