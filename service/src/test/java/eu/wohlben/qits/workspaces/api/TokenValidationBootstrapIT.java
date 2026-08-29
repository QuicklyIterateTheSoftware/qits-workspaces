package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import eu.wohlben.qits.workspaces.wiring.testdb.EmbeddedPg;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, with the machine-auth gate <b>on</b> — a posture no
 * {@code @QuarkusTest} in this repo can reach. {@code DaemonControlSocketMachineAuthTest} comes
 * closest and stops exactly where this one starts: it flips {@code qits.auth.machine.required} too,
 * but blanks {@code quarkus.oidc.auth-server-url} and hands Quarkus a static {@code
 * quarkus.oidc.public-key} instead — so the shipped pair that matters in a deployment,
 * {@code auth-server-url} plus {@code jwks-path=jwks}, fetched over a real listener before any
 * caller arrives, is exercised nowhere. The far side here is {@link MockIdp}, which serves that
 * JWKS and <b>records</b> the fetch, so the interaction is assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. Both stories
 * are browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>Why this is the sixth {@code *IT.java} and shares nothing with the other five.</b> Those
 * are the docker-backed {@code extended} suite — a real container, a real native daemon, a real
 * image — and they self-skip. This one needs no docker at all: an embedded postgres this JVM
 * spawns, a mock idp on a random port, and the fast-jar failsafe has just built. So it is opted in
 * by name rather than by {@code skipITs}, and a run that wants it drags none of the five along:
 *
 * <pre>{@code ./mvnw verify -DskipITs=false -Dit.test=TokenValidationBootstrapIT}</pre>
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-qits-workspaces-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG = "a-stranger-s-token-never-opens-the-workspaces-api";

  /** The route both stories present a bearer to. See the accept story for why it is this one. */
  static final String GUARDED_ROUTE = "/workspaces/api/history?repositoryId=qits-workspaces";

  /**
   * Hands the launched artifact its config the way a deployment does — the generic resource triples
   * under the <b>variable names the shipped expressions read</b>, so the expressions themselves
   * stay under test rather than being bypassed by a second spelling. The overrides reach the
   * launched process as system properties and expression expansion reads the whole config, so
   * {@code ${QITS_RESOURCE_DB_URL}} in the domain jar's own defaults resolves against them.
   *
   * <p>The databases are the same embedded postgres the surefire suite spawns, under IT-own names
   * so nothing is shared with the {@code @QuarkusTest} ones — the per-(module, datasource) rule,
   * one size further out. The mock idp starts here, <em>before</em> the application, via {@link
   * MockIdp#ensureStarted()}, which parks its coordinates in system properties: a test profile is
   * instantiated in more than one classloader, and the property table is the one thing every copy
   * (and the story method's {@link MockIdp#attach()}) shares.
   *
   * <p><b>Every key here is a RUNTIME key.</b> A packaged process cannot be handed a build-time
   * one: Quarkus fixed those at augmentation, so a {@code -D} for such a key is silently the
   * default — which in this repo is a defect class with its own paragraph in AGENTS.md rather than
   * a theoretical hazard.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    /**
     * Environment-qualified on purpose. The shipped default is the bare {@code qits-workspaces} and
     * a deployment injects the tier's spelling; the audience the stories mint against is the one
     * this profile set, so {@code quarkus.oidc.token.audience=${qits.auth.machine.audience}} is
     * proved to be read rather than assumed.
     */
    static final String AUDIENCE = "dev-qits-workspaces";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> config = new LinkedHashMap<>();

      // --- the two databases a deployment creates before the container starts -------------------
      // `resources: postgresql:db, postgresql:eventstream:…` in .config/qits/deployments.yml, and
      // the variable names follow the resource NAME — which is why they are spelled here exactly
      // as the deployer would spell them, rather than as the datasource keys they end up filling.
      config.put("QITS_RESOURCE_DB_URL", EmbeddedPg.url("workspaces_packaged_it"));
      config.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      config.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      config.put(
          "QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url("workspaces_eventstream_packaged_it"));
      config.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      config.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);

      // --- the gate, turned on -------------------------------------------------------------------
      // This IS the deployed rollout posture, not a test convenience. `quarkus.oidc.tenant-enabled`
      // is spelled `${qits.auth.machine.required:false}` in application.properties, so a platform
      // that has finished the rollout sets this one key and the tenant comes up — everything else
      // about the tenant already ships. Flipping the derived key directly would prove the tenant
      // and skip the seam.
      config.put("qits.auth.machine.required", "true");
      config.put("qits.auth.machine.audience", AUDIENCE);
      // The one seam this test MOVES: where the idp is. A runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off, and `jwks-path=jwks` is joined onto it.
      config.put("quarkus.oidc.auth-server-url", idp.baseUrl());

      // --- what a host-run process must be told, with no deployment behind it --------------------
      // qits.projects.url has NO default, and HttpRepositoryLookup refuses to start a production
      // build without one — deliberately, because coming up and 404ing every repository-scoped
      // route is a misconfiguration wearing the costume of an empty system. That refusal is why
      // this repo could have no artifact-level IT at all until the port was implemented. An address
      // nothing listens on is the honest value here: neither story reaches qits-projects, and a
      // reachable one would only invite a run to find a real registry on the developer's own
      // machine (the reasoning the test properties already give for qits.containers.url).
      config.put("qits.projects.url", "http://127.0.0.1:1");
      // Under target/, never the shipped ${user.home}/.qits tree: the CI step container's home is
      // not this service's to write in, and no story here needs a mirror.
      config.put("qits.workspaces.data-dir", "target/workspaces-packaged-it-data");

      // --- dark outside a deployment, like %dev/%test — both runtime keys ------------------------
      config.put("quarkus.otel.sdk.disabled", "true");
      config.put("qits.eventstream.enabled", "false");
      return config;
    }
  }

  @UserStory(
      value = "On start, qits-workspaces fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-workspaces must validate service bearers before any caller arrives:
      at startup it fetches the signing keys (JWKS) from qits-platform-idp — discovery stays off,
      the path is configured — so the very first machine request is judged on the platform's own
      keys. The callers that depend on it are the ones that cannot log in: a workspace daemon
      dialling its control socket, and the pipeline step that drives the release door.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-workspaces starts with the machine-auth gate on, beside a live qits-platform-idp");
    given().get("/workspaces/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. That ordering is the whole claim. A service that fetched keys lazily, on the
    // first bearer, would look identical from this end and fail its first caller after a restart.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.happened("qits-workspaces", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. The route is
    // a plain REST GET over this context's own store — the workspace history — and it is guarded
    // by `qits:admin`, like every other door in api/. That is not a softer target than a
    // `qits:system` one: creating, releasing and inspecting workspaces already requires the
    // platform admin role, and the machine callers this gate exists for carry it — a commissioned
    // workspace credential does, which is what lets a pipeline step drive
    // POST /workspaces/api/branches/release. The only `qits:system`-reachable surfaces here are the
    // daemon control socket, a WebSocket whose caller is a container and whose story is
    // DaemonControlSocketMachineAuthTest's, and the gc door, a POST that sweeps branches. Neither
    // is a plain read, and the claim under test is about the token rather than the route's work.
    String platformToken =
        idp.token()
            .subject("qits-platform-orchestrator")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system", "qits:admin")
            .mint();
    given()
        .header("Authorization", "Bearer " + platformToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(200)
        .body("entries", notNullValue());
    story
        .happened(
            "a platform service",
            "qits-workspaces",
            "GET /workspaces/api/history (Bearer, groups=[qits:system, qits:admin])")
        .as("history-served");
  }

  @UserStory(
      value = "A stranger's token never opens the workspaces API",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys: a token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks. The audience half is the one worth stating out loud, because every
      service on qits-net is issued tokens by the same idp, and a bearer good for qits-containers
      must not also open the release door.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system", "qits:admin")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-workspaces",
            "GET /workspaces/api/history (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    // qits-containers and not an invented name: it is an audience this service's own oidc-client
    // really requests, so the story documents the confusion that could actually happen on qits-net
    // rather than a strawman.
    String wrongAudienceToken =
        idp.token().audience("qits-containers").groups("qits:system", "qits:admin").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(GUARDED_ROUTE)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-workspaces",
            "GET /workspaces/api/history (another service's audience) -> 401")
        .as("wrong-audience-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-workspaces",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "history-served");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
  }
}
