package eu.wohlben.qits.daemonproxy;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.control.DaemonSupervisor;
import eu.wohlben.qits.domain.daemon.control.RepositoryDaemonService;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.daemon.entity.RestartPolicy;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the daemon web-view proxy against a real loopback origin: a Vert.x echo server plays
 * the daemon's dev server (the {@code FakeContainerRuntime} maps published ports 1:1, so the
 * daemon's {@code httpPort} <em>is</em> the host port the proxy targets). Verifies the base-path
 * contract (paths forwarded verbatim, unstripped), the lifecycle responses (splash/502/404), the
 * trailing-slash redirect, the WebSocket round-trip (the HMR path), and that unknown keys never
 * reach the origin.
 */
@QuarkusTest
@TestProfile(DaemonProxyRouteTest.TestProfile.class)
public class DaemonProxyRouteTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-proxy-test-repos");
        return Map.of(
            "qits.repositories.data-dir", tempDir.toString(),
            "qits.daemons.ready-grace-ms", "300",
            "qits.daemons.stop-grace-ms", "1000",
            "qits.daemons.restart-backoff-initial-ms", "100",
            "qits.daemons.file-tail-poll-ms", "100");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject WorkspaceService workspaceService;

  @Inject RepositoryDaemonService repositoryDaemonService;

  @Inject DaemonSupervisor supervisor;

  private Vertx echoVertx;
  private HttpServer echoServer;
  private final AtomicInteger echoHits = new AtomicInteger();
  private final java.util.concurrent.atomic.AtomicReference<String> lastHostHeader =
      new java.util.concurrent.atomic.AtomicReference<>();

  @BeforeEach
  void startEchoServer() throws Exception {
    echoVertx = Vertx.vertx();
    echoServer =
        echoVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  echoHits.incrementAndGet();
                  lastHostHeader.set(req.getHeader("Host"));
                  req.response().end("echo:" + req.uri());
                })
            .webSocketHandler(
                ws ->
                    ws.textMessageHandler(
                        msg -> ws.writeTextMessage("ws-echo:" + ws.path() + ":" + msg)))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    echoHits.set(0);
  }

  @AfterEach
  void stopEchoServer() {
    if (echoVertx != null) {
      echoVertx.close();
    }
  }

  /** Definition before workspace, so the (fake) container "publishes" the port at creation. */
  private Setup setUpReadyDaemon(String script, String readyPattern) throws Exception {
    return setUpReadyDaemon(script, readyPattern, null);
  }

  private Setup setUpReadyDaemon(String script, String readyPattern, String basePath)
      throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Proxy Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    String daemonId =
        repositoryDaemonService.create(
                repo.id,
                "echo-daemon",
                null,
                script,
                readyPattern,
                "TERM",
                RestartPolicy.NEVER,
                null, // autoStart (default true)
                0,
                null,
                echoServer.actualPort(),
                null,
                basePath,
                null,
                null,
                null)
            .id;
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    supervisor.start(repo.id, "work", daemonId);
    return new Setup(repo.id, daemonId);
  }

  private record Setup(String repoId, String daemonId) {}

  private void awaitStatus(Setup setup, DaemonStatus expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    DaemonStatus last = null;
    while (System.currentTimeMillis() < deadline) {
      last =
          supervisor.effectiveDaemons(setup.repoId(), "work").stream()
              .filter(i -> i.daemon().id().equals(setup.daemonId()))
              .findFirst()
              .map(i -> i.status())
              .orElse(null);
      if (last == expected) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last: " + last);
  }

  private void stopQuietly(Setup setup) {
    try {
      supervisor.stop(setup.repoId(), "work", setup.daemonId());
      awaitStatus(setup, DaemonStatus.STOPPED);
    } catch (Exception ignored) {
      // already stopped or never live
    }
  }

  @Test
  public void forwardsVerbatimRedirectsBareKeyAndRefusesAfterStop() throws Exception {
    Setup setup = setUpReadyDaemon("sleep 300", null);
    try {
      awaitStatus(setup, DaemonStatus.READY);
      String base = "/daemon/work/" + setup.daemonId();

      // Verbatim passthrough: the origin sees the unstripped path and query.
      given()
          .get(base + "/some/nested/path?q=1")
          .then()
          .statusCode(200)
          .body(containsString("echo:" + base + "/some/nested/path?q=1"));

      // Bare key 302s to the trailing-slash form so relative URLs resolve inside the frame.
      given()
          .redirects()
          .follow(false)
          .get(base)
          .then()
          .statusCode(302)
          .header("Location", base + "/");

      // WebSocket upgrade (the HMR path) round-trips through the proxy, path unstripped.
      CompletableFuture<String> reply = new CompletableFuture<>();
      WebSocket ws =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + RestAssured.port + base + "/hmr"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      reply.complete(data.toString());
                      return null;
                    }
                  })
              .get(10, TimeUnit.SECONDS);
      ws.sendText("ping", true);
      assertEquals("ws-echo:" + base + "/hmr:ping", reply.get(10, TimeUnit.SECONDS));
      ws.abort();
    } finally {
      stopQuietly(setup);
    }

    // Stopped: the instance still resolves, but the proxy answers 502 instead of forwarding.
    int hitsBefore = echoHits.get();
    given()
        .get("/daemon/work/" + setup.daemonId() + "/")
        .then()
        .statusCode(502)
        .body(containsString("not running"));
    assertEquals(hitsBefore, echoHits.get(), "a stopped daemon must not be forwarded to");
  }

  @Test
  public void rewritesHostHeaderToLocalhostSoTheDevServerAllowsIt() throws Exception {
    // Regression for the devcontainer move: qits now reaches containers by DNS name, so the proxy
    // must present the origin's Host as `localhost` (always allow-listed by Angular's dev server)
    // instead of the container's DNS name (rejected with "This host is not allowed"). TCP still
    // targets the fixed origin; only the Host/:authority header is rewritten.
    Setup setup = setUpReadyDaemon("sleep 300", null);
    try {
      awaitStatus(setup, DaemonStatus.READY);
      given()
          .get("/daemon/work/" + setup.daemonId() + "/index.html")
          .then()
          .statusCode(200)
          .body(containsString("echo:"));
      assertEquals(
          "localhost:" + echoServer.actualPort(),
          lastHostHeader.get(),
          "the origin must see a localhost Host, not qits' own or the container's DNS name");
    } finally {
      stopQuietly(setup);
    }
  }

  @Test
  public void basePathPrefixedRequestsForwardVerbatim() throws Exception {
    // A daemon with a webView.basePath serves under /daemon/{w}/{d}/app/ — the proxy stays a dumb
    // passthrough; the extra sub-path is part of the verbatim-forwarded path, never stripped.
    Setup setup = setUpReadyDaemon("sleep 300", null, "app");
    try {
      awaitStatus(setup, DaemonStatus.READY);
      String servedBase = "/daemon/work/" + setup.daemonId() + "/app";
      given()
          .get(servedBase + "/main.js")
          .then()
          .statusCode(200)
          .body(containsString("echo:" + servedBase + "/main.js"));
    } finally {
      stopQuietly(setup);
    }
  }

  @Test
  public void unknownKeysAnswer404WithoutTouchingAnyOrigin() {
    int hitsBefore = echoHits.get();
    given().get("/daemon/no-such-workspace/no-such-daemon/index.html").then().statusCode(404);
    given().get("/daemon/onlyonesegment").then().statusCode(404);
    given().get("/daemon/").then().statusCode(404);
    assertEquals(hitsBefore, echoHits.get(), "unknown keys must never reach an origin");
  }

  @Test
  public void startingDaemonGetsTheAutoRefreshingSplash() throws Exception {
    // A ready pattern that never matches keeps the instance in STARTING.
    Setup setup = setUpReadyDaemon("sleep 300", "NEVER_MATCHES_ANYTHING");
    try {
      String body =
          given()
              .get("/daemon/work/" + setup.daemonId() + "/")
              .then()
              .statusCode(200)
              .extract()
              .asString();
      assertTrue(body.contains("http-equiv=\"refresh\""), "splash must auto-refresh: " + body);
      assertTrue(body.contains("starting"), "splash names the state: " + body);
    } finally {
      stopQuietly(setup);
    }
  }
}
