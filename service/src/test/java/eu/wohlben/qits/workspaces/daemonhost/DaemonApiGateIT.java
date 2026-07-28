package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * <b>The gate for the workspace-daemon's HTTP API</b>
 * (final-workspaces-and-agent-communication-migration-plan.md §5 step 6): a real container, a real
 * native daemon, a real bearer, and a browser-equivalent client doing what a user does — list files,
 * read one, launch a command, terminate it, and attach a terminal that <em>survives a client
 * reconnect</em>.
 *
 * <p><b>Why a `@QuarkusIntegrationTest` would not have been enough.</b> The interesting failures are
 * in another process: whether the injected token actually reaches the daemon's config, whether
 * {@code WorkspaceApi} binds at all, whether a websocket detaches rather than terminating on close.
 * None of that is observable from inside this JVM, and every one of them fails silently — an unbound
 * server is a connection error, and a terminal that terminates on close looks identical to one that
 * detaches until you reconnect and find the command gone.
 *
 * <p><b>What this does not cover, deliberately.</b> The proxy hop. This test reaches the daemon
 * directly on a published port, because {@code FakeContainerRuntime} replaces {@code DockerExecutor}
 * globally in this module's {@code @QuarkusTest}s (via {@code @Mock}) — so an application booted
 * here cannot drive real docker, and a test that drives real docker cannot boot the application.
 * {@code ContainerProxyRouteTest} covers the hop instead, against a real Vert.x server on a real
 * socket, including the bearer and a WebSocket round-trip. The two together are the chain; neither
 * alone is.
 *
 * <p>Production publishes no ports — qits reaches a container by DNS name on {@code qits-net} — so
 * {@code -p 127.0.0.1::13338} here is the host-run equivalent of that reachability and nothing more.
 * It is also, incidentally, the thing stage 2 removes.
 *
 * <p>Part of the extended suite; self-skips when docker or the {@code qits/workspace} image is
 * absent. Run with {@code ./mvnw verify -DskipITs=false}.
 */
@Tag("extended")
public class DaemonApiGateIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");
  private static final String REPO_ID = "apigate-repo";
  private static final String BRANCH = "main";
  private static final String TOKEN = "gate-it-token";

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @Test
  public void aBrowserEquivalentClientDrivesTheDaemonOverItsHttpApi() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(), "docker + " + IMAGE + " (built with workspace-daemon) required");

    Path work = Files.createTempDirectory("qits-apigate-it");
    Path bare = prepareServedBareRepo(work);

    Vertx vertx = Vertx.vertx();
    String container = "qits-apigate-it-" + UUID.randomUUID().toString().substring(0, 8);
    CompletableFuture<Provisioned> provisioned = new CompletableFuture<>();
    CompletableFuture<ProvisionFailed> failed = new CompletableFuture<>();

    HttpServer server = vertx.createHttpServer();
    server.requestHandler(
        req -> {
          String prefix = "/git/" + REPO_ID + "/";
          if (req.path().startsWith(prefix)) {
            serveBareFile(bare, req.path().substring(prefix.length()), req);
          } else {
            req.response().setStatusCode(404).end();
          }
        });
    // The control socket. The daemon provisions autonomously and binds its HTTP API only once the
    // checkout exists, so waiting for Provisioned is also waiting for the API to be up.
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  switch (DaemonCodec.decode(new JsonObject(text).getMap())) {
                    case Provisioned p -> provisioned.complete(p);
                    case ProvisionFailed f -> failed.complete(f);
                    case Hello ignored -> {}
                    default -> {}
                  }
                }));
    int port =
        server
            .listen(0, "0.0.0.0")
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)
            .actualPort();

    try {
      run(
          RUNTIME,
          "run",
          "-d",
          "--init",
          "--name",
          container,
          "--user",
          hostUid(),
          "--add-host=host.docker.internal:host-gateway",
          // The host-run equivalent of reaching the container by DNS name on qits-net.
          "-p",
          "127.0.0.1::13338",
          "-e",
          "QITS_WORKSPACE_DAEMON_URL=ws://host.docker.internal:" + port + "/workspaces/daemon/it-ws",
          "-e",
          "QITS_WORKSPACE_DAEMON_WORKSPACE_ID=it-ws",
          "-e",
          "QITS_WORKSPACE_DAEMON_REPOSITORY_ID=" + REPO_ID,
          "-e",
          "QITS_WORKSPACE_DAEMON_BRANCH=" + BRANCH,
          // The fifteenth env var, and the whole reason the API is reachable at all: without it
          // WorkspaceApi refuses to bind and every assertion below would be a connection error.
          "-e",
          "QITS_WORKSPACE_DAEMON_API_TOKEN=" + TOKEN,
          IMAGE);

      assertFalse(failed.isDone(), () -> "unexpected ProvisionFailed: " + failed.getNow(null));
      provisioned.get(90, TimeUnit.SECONDS);
      String api = "http://127.0.0.1:" + publishedPort(container);

      // --- the token is the precondition, not decoration ----------------------------------------
      assertEquals(401, get(api + "/files", null).statusCode(), "no bearer, no answer");
      assertEquals(401, get(api + "/files", "Bearer wrong").statusCode());

      // --- one file listing, one file read ------------------------------------------------------
      HttpResponse<String> listing = get(api + "/files", bearer());
      assertEquals(200, listing.statusCode());
      assertTrue(listing.body().contains("hello.txt"), listing.body());

      HttpResponse<String> content = get(api + "/files/content?path=hello.txt", bearer());
      assertEquals(200, content.statusCode());
      assertTrue(content.body().contains("hello from the api gate"), content.body());

      // --- the two surfaces that had no address until this change -------------------------------
      HttpResponse<String> services = get(api + "/services", bearer());
      assertEquals(200, services.statusCode());
      assertTrue(services.body().contains("\"services\""), services.body());

      HttpResponse<String> bootstrap = get(api + "/bootstrap-commands", bearer());
      assertEquals(200, bootstrap.statusCode());
      assertTrue(bootstrap.body().contains("\"steps\""), bootstrap.body());

      // --- one command launched, watched, and terminated ----------------------------------------
      HttpResponse<String> launched =
          post(api + "/commands", "{\"actionId\":\"hold\"}", bearer());
      assertEquals(200, launched.statusCode(), launched.body());
      String commandId = new JsonObject(launched.body()).getJsonObject("command").getString("id");

      assertEquals("RUNNING", statusOf(api, commandId), "the action holds until terminated");

      // --- a terminal that echoes a keystroke ---------------------------------------------------
      String terminal = "ws://127.0.0.1:" + publishedPort(container) + "/terminal/commands/" + commandId;
      AtomicReference<StringBuilder> firstSeen = new AtomicReference<>(new StringBuilder());
      CompletableFuture<String> echoed = new CompletableFuture<>();
      WebSocket first = attach(terminal, firstSeen.get(), echoed, "gate-keystroke");
      first.sendText("{\"type\":\"data\",\"data\":\"gate-keystroke\\n\"}", true);
      assertTrue(
          echoed.get(30, TimeUnit.SECONDS).contains("gate-keystroke"),
          "the PTY echoed the keystroke back");

      // --- ...and survives the client going away ------------------------------------------------
      // Closing detaches; it never terminates. That is what makes a command survive a browser
      // refresh, and a proxy that breaks it breaks it silently — the socket still opens, the
      // command is just gone.
      first.sendClose(WebSocket.NORMAL_CLOSURE, "refresh").get(10, TimeUnit.SECONDS);
      Thread.sleep(500);
      assertEquals("RUNNING", statusOf(api, commandId), "closing a terminal must not kill it");

      CompletableFuture<String> echoedAgain = new CompletableFuture<>();
      WebSocket second =
          attach(terminal, new StringBuilder(), echoedAgain, "gate-after-reconnect");
      second.sendText("{\"type\":\"data\",\"data\":\"gate-after-reconnect\\n\"}", true);
      assertTrue(
          echoedAgain.get(30, TimeUnit.SECONDS).contains("gate-after-reconnect"),
          "re-attaching reaches the same live command");
      // Resizing is part of the same message contract and must not disturb it.
      second.sendText("{\"type\":\"resize\",\"cols\":100,\"rows\":40}", true);
      second.abort();

      HttpResponse<String> terminated =
          post(api + "/commands/" + commandId + "/terminate", "", bearer());
      assertEquals(200, terminated.statusCode(), terminated.body());
      assertNotEquals(
          "RUNNING", awaitLeavesRunning(api, commandId), "terminate actually stopped it");
    } finally {
      run(RUNTIME, "rm", "-f", container);
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  // --- the served repository ---------------------------------------------------------------------

  /**
   * A bare repo with one commit on {@link #BRANCH}, carrying a {@code .qits-config.yml} that
   * declares an interactive action the terminal assertions attach to, plus a service and a bootstrap
   * step so the two re-exposed surfaces have something to report.
   */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    git(src, "init", "-q", "-b", BRANCH);
    git(src, "config", "user.email", "it@qits.local");
    git(src, "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello from the api gate\n");
    Files.writeString(
        src.resolve(".qits-config.yml"),
        """
        actions:
          - name: hold
            description: an interactive shell that stays up until terminated
            interactive: true
            execute: cat
        services:
          - name: idle
            description: a service the gate only lists, never starts
            start: sleep 3600
            auto-start: false
        bootstrap:
          - name: noop
            description: a step the gate only lists
            execute: 'true'
        """);
    git(src, "add", "hello.txt", ".qits-config.yml");
    git(src, "commit", "-q", "-m", "initial");
    Path bare = work.resolve("served.git");
    git(work, "clone", "-q", "--bare", src.toString(), bare.toString());
    git(bare, "update-server-info");
    return bare;
  }

  private static void serveBareFile(
      Path bare, String rel, io.vertx.core.http.HttpServerRequest req) {
    Path file = bare.resolve(rel).normalize();
    if (!file.startsWith(bare) || !Files.isRegularFile(file)) {
      req.response().setStatusCode(404).end();
      return;
    }
    try {
      req.response()
          .putHeader("Content-Type", "application/octet-stream")
          .end(Buffer.buffer(Files.readAllBytes(file)));
    } catch (Exception e) {
      req.response().setStatusCode(500).end();
    }
  }

  // --- the client ---------------------------------------------------------------------------------

  private static String bearer() {
    return "Bearer " + TOKEN;
  }

  private static HttpResponse<String> get(String url, String authorization) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).GET();
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String url, String body, String authorization)
      throws Exception {
    return CLIENT.send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String statusOf(String api, String commandId) throws Exception {
    return new JsonObject(get(api + "/commands/" + commandId, bearer()).body()).getString("status");
  }

  private static String awaitLeavesRunning(String api, String commandId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    String status = null;
    while (System.nanoTime() < deadline) {
      status = statusOf(api, commandId);
      if (!"RUNNING".equals(status)) {
        return status;
      }
      Thread.sleep(50);
    }
    return status;
  }

  /** Attach a terminal socket, completing {@code echoed} once {@code marker} comes back. */
  private static WebSocket attach(
      String url, StringBuilder seen, CompletableFuture<String> echoed, String marker)
      throws Exception {
    return CLIENT
        .newWebSocketBuilder()
        .header("Authorization", bearer())
        .buildAsync(
            URI.create(url),
            new WebSocket.Listener() {
              @Override
              public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                seen.append(data);
                if (seen.indexOf(marker) >= 0) {
                  echoed.complete(seen.toString());
                }
                socket.request(1);
                return null;
              }
            })
        .get(30, TimeUnit.SECONDS);
  }

  // --- docker -------------------------------------------------------------------------------------

  /** The host port docker published the daemon's API on. */
  private static String publishedPort(String container) throws Exception {
    String mapping = exec(new ProcessBuilder(RUNTIME, "port", container, "13338/tcp")).trim();
    // "127.0.0.1:49154" (possibly several lines for several families) — take the first port.
    String first = mapping.lines().findFirst().orElseThrow();
    return first.substring(first.lastIndexOf(':') + 1).trim();
  }

  private static void git(Path cwd, String... args) throws Exception {
    String[] argv = new String[args.length + 3];
    argv[0] = "git";
    argv[1] = "-C";
    argv[2] = cwd.toString();
    System.arraycopy(args, 0, argv, 3, args.length);
    exec(new ProcessBuilder(argv));
  }

  private static String exec(ProcessBuilder builder) throws Exception {
    Process process = builder.redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes());
    process.waitFor(60, TimeUnit.SECONDS);
    return out;
  }

  /**
   * Whether docker is here <em>and</em> {@link #IMAGE} is a workspace image built with the
   * workspace-daemon stage.
   *
   * <p>The entrypoint is the predicate because it is the contract: {@code
   * WorkspaceContainerFactory} appends no command, so a workspace container runs whatever the image
   * entrypoints to, and an image entrypointing to anything else cannot serve this test. Checking
   * only that the image exists is what this used to do, and against a pre-daemon image of the same
   * name every daemon IT then failed on a 30-second timeout instead of skipping — a build
   * environment problem wearing the costume of a product bug.
   */
  private boolean dockerAndImageAvailable() {
    try {
      Process process =
          new ProcessBuilder(
                  RUNTIME, "image", "inspect", "--format", "{{.Config.Entrypoint}}", IMAGE)
              .redirectErrorStream(true)
              .start();
      String entrypoint = new String(process.getInputStream().readAllBytes());
      return process.waitFor() == 0 && entrypoint.contains("qits-workspace-daemon");
    } catch (Exception e) {
      return false;
    }
  }

  private static void run(String... argv) throws Exception {
    Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    process.getInputStream().readAllBytes();
    process.waitFor(60, TimeUnit.SECONDS);
  }

  private static String hostUid() {
    try {
      Object uid = Files.getAttribute(Path.of(System.getProperty("user.home")), "unix:uid");
      return String.valueOf(((Number) uid).longValue());
    } catch (Exception e) {
      return "1000";
    }
  }

  private static void deleteRecursively(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception e) {
      // best-effort temp cleanup
    }
  }
}
