package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the workspace-daemon proxy against a real loopback origin: a Vert.x server plays the
 * in-container {@code WorkspaceApi} — echoing the path it was called on, the {@code Authorization}
 * it was given and the {@code Host} it saw — and {@code FakeContainerRuntime} resolves the target to
 * {@code 127.0.0.1} + the configured daemon port, so that port <em>is</em> the port the proxy
 * targets. No docker involved; the sibling of {@link ServiceProxyRouteTest} and deliberately the
 * same kind of test.
 *
 * <p>The port has to be fixed before the application starts, because unlike a service's web-view
 * port it comes from configuration rather than from a staged config document — see {@link
 * #PORT_PROPERTY} for how it gets there and why it is latched.
 */
@QuarkusTest
@TestProfile(ContainerProxyRouteTest.TestProfile.class)
public class ContainerProxyRouteTest {

  private static final String TOKEN = "test-daemon-token";

  /**
   * The port the fake daemon binds, chosen by the profile and handed to the test through a system
   * property.
   *
   * <p>It has to travel that way, and the <em>first caller wins</em>. Unlike a service's web-view
   * port, the daemon's comes from configuration, so it must be fixed before the application boots —
   * and {@link QuarkusTestProfile} is instantiated in more than one classloader, so
   * {@code getConfigOverrides()} runs more than once. A plain static initializer picks a different
   * port each time; an unconditional {@code setProperty} lets the later call overwrite the value the
   * application was actually configured with. Either way the proxy targets a port nothing is
   * listening on, and the symptom is every proxying assertion failing with a bare, bodyless 502 that
   * says nothing about why. Latching the first value is what makes both halves agree.
   */
  private static final String PORT_PROPERTY = "qits.test.container-proxy.daemon-port";

  private static synchronized int latchedPort() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (Exception e) {
      throw new IllegalStateException("no free port for the fake daemon", e);
    }
  }

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-container-proxy-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.workspace.daemon-api-port", String.valueOf(latchedPort()),
            "qits.workspace.daemon-api-token", TOKEN);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private int daemonPort() {
    return latchedPort();
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.repositories.data-dir")
  String dataDir;

  private Vertx daemonVertx;
  private HttpServer daemonServer;
  private final AtomicInteger daemonHits = new AtomicInteger();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastHost = new AtomicReference<>();

  @BeforeEach
  void startFakeDaemon() throws Exception {
    daemonVertx = Vertx.vertx();
    daemonServer =
        daemonVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  daemonHits.incrementAndGet();
                  lastAuthorization.set(req.getHeader("Authorization"));
                  lastHost.set(req.getHeader("Host"));
                  req.response().end("daemon:" + req.uri());
                })
            // The real daemon authenticates the HANDSHAKE with the same bearer it checks on every
            // request, so this stub must too. It used to accept any upgrade, and that is precisely
            // what hid a live bug: vertx-http-proxy skips its interceptor chain on an upgrade, so
            // the bearer never reached the daemon and both interactive sockets were answered 401
            // in production while this test stayed green.
            .webSocketHandshakeHandler(
                handshake -> {
                  if (("Bearer " + TOKEN).equals(handshake.headers().get("Authorization"))) {
                    handshake.accept();
                  } else {
                    handshake.reject(401);
                  }
                })
            .webSocketHandler(
                ws ->
                    ws.textMessageHandler(
                        msg -> ws.writeTextMessage("ws-daemon:" + ws.path() + ":" + msg)))
            .listen(daemonPort(), "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    daemonHits.set(0);
  }

  @AfterEach
  void stopFakeDaemon() {
    if (daemonVertx != null) {
      daemonVertx.close();
    }
  }

  /** A repository with one workspace whose (fake) container is provisioned and running. */
  private Long workspaceWithContainer() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    Long id = workspaceIds.of(repoId, "work");
    workspaceService.ensureContainer(id);
    return id;
  }

  @Test
  public void forwardsTheDaemonsPathVerbatimWithQitsOwnBearer() throws Exception {
    Long id = workspaceWithContainer();
    String base = "/workspaces/container/" + id;

    given()
        .get(base + "/files/content?path=README")
        .then()
        .statusCode(200)
        // The daemon carries no {repoId}/{workspaceId} prefix and the proxy strips none of its own:
        // The WHOLE path reaches the daemon, prefix included — this route rewrites nothing, and the
        // daemon is configured with that prefix as its base (QITS_WORKSPACE_DAEMON_API_BASE_PATH,
        // injected by WorkspaceContainerFactory). If this assertion ever looks wrong, the fix is on
        // the daemon's side of the contract, not a substring here; ContainerProxyRoute's javadoc
        // says why. Everything after the workspace id is the daemon's path, untouched, query
        // included. (The
        // query value is slash-free on purpose — RestAssured percent-encodes a slash inside one, and
        // the resulting mismatch would be the client's encoding, not the proxy's forwarding.)
        .body(containsString("daemon:" + base + "/files/content?path=README"));

    assertEquals(
        "Bearer " + TOKEN,
        lastAuthorization.get(),
        "qits presents its own credential — the daemon's bearer is peer authentication");
  }

  @Test
  public void aCallerSuppliedAuthorizationIsReplacedRatherThanForwarded() throws Exception {
    Long id = workspaceWithContainer();

    given()
        .header("Authorization", "Bearer smuggled-in-by-the-caller")
        .get("/workspaces/container/" + id + "/detection")
        .then()
        .statusCode(200);

    // Forwarding it would be both meaningless (the daemon has no user identity to check) and a way
    // to move a credential into a container that runs an untrusted checkout.
    assertEquals("Bearer " + TOKEN, lastAuthorization.get());
  }

  @Test
  public void theDaemonSeesAPinnedAuthority() throws Exception {
    Long id = workspaceWithContainer();

    given().get("/workspaces/container/" + id + "/detection").then().statusCode(200);

    // Not qits' own Host and not the container's DNS name: a constant, so the daemon's view of who
    // called it does not change when the origin does.
    assertEquals("localhost:" + daemonPort(), lastHost.get());
  }

  @Test
  public void aWebSocketUpgradeRidesAlong() throws Exception {
    Long id = workspaceWithContainer();
    String path = "/workspaces/container/" + id + "/terminal/commands/abc123";

    CompletableFuture<String> reply = new CompletableFuture<>();
    WebSocket ws =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://127.0.0.1:" + RestAssured.port + path),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    reply.complete(data.toString());
                    return null;
                  }
                })
            .get(10, TimeUnit.SECONDS);
    ws.sendText("{\"type\":\"data\",\"data\":\"x\"}", true);

    // This is what carries WS /terminal/commands/{id} and WS /chat/commands/{id}; neither end knows
    // it is proxied, and the daemon's path arrives unstripped.
    assertEquals(
        "ws-daemon:" + path + ":{\"type\":\"data\",\"data\":\"x\"}", reply.get(10, TimeUnit.SECONDS));
    ws.abort();
  }

  @Test
  public void unknownSoftDeletedAndNonNumericIdsAllAnswerTheSame404() throws Exception {
    int hitsBefore = daemonHits.get();

    // Never existed.
    given().get("/workspaces/container/999999/files").then().statusCode(404);
    // Not an id at all.
    given().get("/workspaces/container/not-a-number/files").then().statusCode(404);
    // No id.
    given().get("/workspaces/container/").then().statusCode(404);

    // Soft-deleted: the row lingers, and it must answer exactly as an unknown one does.
    Long id = workspaceWithContainer();
    workspaceService.deleteContainer(id);
    workspaceService.discardWorkspace(id);
    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    assertEquals(hitsBefore, daemonHits.get(), "none of these may reach a container");
  }

  @Test
  public void aStoppedContainerIsADistinctAnswerFromAMissingWorkspace() throws Exception {
    Long id = workspaceWithContainer();
    workspaceService.stopContainer(id);

    int hitsBefore = daemonHits.get();
    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        .statusCode(502)
        // A naive proxy reports every one of these as one indistinguishable connection error, and
        // then every daemon problem looks like the same problem.
        .body(containsString("not running"));
    assertEquals(hitsBefore, daemonHits.get(), "a stopped container must not be forwarded to");
  }
}
