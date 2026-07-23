package eu.wohlben.qits.workspacedaemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker proof of the <b>autonomous self-clone on boot</b> (docs/epics/qits-workspace-daemon/
 * Part 1): the native {@code workspace-daemon} binary, given only its dial-home env, clones {@code
 * /workspace} from qits' git host <em>itself</em> — no host {@code docker exec git clone} — and
 * reports {@link Provisioned} with the checked-out {@code HEAD}.
 *
 * <p>A single standalone Vert.x server plays both roles the daemon needs: the control socket (at
 * {@code /api/workspace-daemon/*}, where we collect the {@code Provisioned}) and a minimal
 * dumb-HTTP git host (at {@code /git/*}, serving a bare repo's files) — reached the same way
 * host-run qits is, via {@code host.docker.internal}. This isolates the self-clone; the
 * name-addressed submodule-closure walk over the real {@code GitHostRoutes} is exercised by the
 * full-stack acceptance path. The in-JVM {@link DaemonControlSocketTest} covers the backend {@code
 * awaitProvision} side.
 *
 * <p>Part of the <strong>extended</strong> suite ({@code ./mvnw verify -Pextended}); self-skips
 * when docker or the {@code qits/workspace} image (built WITH the workspace-daemon stage) is
 * absent.
 */
@Tag("extended")
public class DaemonSelfCloneIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");
  private static final String REPO_ID = "selfclone-repo";
  private static final String BRANCH = "main";

  @Test
  public void daemonSelfClonesWorkspaceOnBootWithoutHostGit() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(), "docker + " + IMAGE + " (built with workspace-daemon) required");

    Path work = Files.createTempDirectory("qits-selfclone-it");
    Path bare =
        prepareServedBareRepo(work); // a bare repo with one commit on `main`, dumb-http ready
    String expectedHead = rev(bare); // its HEAD sha, which Provisioned must echo

    Vertx vertx = Vertx.vertx();
    String container = "qits-selfclone-it-" + UUID.randomUUID().toString().substring(0, 8);
    CompletableFuture<Hello> helloReceived = new CompletableFuture<>();
    CompletableFuture<Provisioned> provisioned = new CompletableFuture<>();
    CompletableFuture<ProvisionFailed> failed = new CompletableFuture<>();

    HttpServer server = vertx.createHttpServer();
    // The git host: serve any file under the bare repo at /git/<repoId>/<path> (dumb HTTP).
    server.requestHandler(
        req -> {
          String path = req.path();
          String prefix = "/git/" + REPO_ID + "/";
          if (path.startsWith(prefix)) {
            serveBareFile(bare, path.substring(prefix.length()), req);
          } else {
            req.response().setStatusCode(404).end();
          }
        });
    // The control socket: the daemon self-provisions autonomously, so we send nothing — just
    // collect
    // the terminal event it reports home.
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
                  switch (message) {
                    case Hello hello -> helloReceived.complete(hello);
                    case Provisioned p -> provisioned.complete(p);
                    case ProvisionFailed f -> failed.complete(f);
                    default -> {
                      /* DaemonLog / CommandChunk / Heartbeat — not needed for the assertion */
                    }
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
      String url = "ws://host.docker.internal:" + port + "/api/workspace-daemon/it-ws";
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
          "-e",
          "QITS_WORKSPACE_DAEMON_URL=" + url,
          "-e",
          "QITS_WORKSPACE_DAEMON_WORKSPACE_ID=it-ws",
          "-e",
          "QITS_WORKSPACE_DAEMON_REPOSITORY_ID=" + REPO_ID,
          "-e",
          "QITS_WORKSPACE_DAEMON_BRANCH=" + BRANCH,
          // No PROJECT_ID/REPO_NAME → the daemon id-addresses (/git/<repositoryId>), which the
          // dumb-http server above serves. No `docker exec` is ever issued by this test.
          IMAGE);

      Hello hello = helloReceived.get(30, TimeUnit.SECONDS);
      assertEquals("it-ws", hello.workspaceId());

      // The daemon self-cloned and reported home.
      assertFalse(failed.isDone(), () -> "unexpected ProvisionFailed: " + failed.getNow(null));
      Provisioned p = provisioned.get(60, TimeUnit.SECONDS);
      assertEquals(expectedHead, p.head(), "Provisioned HEAD should match the served repo tip");

      // And /workspace is a real checkout — verified by asking the container (a read, not a clone).
      String head = execCapture(container, "git", "-C", "/workspace", "rev-parse", "HEAD").trim();
      assertEquals(expectedHead, head);
      String tracked = execCapture(container, "git", "-C", "/workspace", "ls-files").trim();
      assertTrue(tracked.contains("hello.txt"), tracked);
    } finally {
      run(RUNTIME, "rm", "-f", container);
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  /**
   * A bare repo with a single commit on {@link #BRANCH}, {@code update-server-info}'d for dumb
   * HTTP.
   */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    git(src, "init", "-q", "-b", BRANCH);
    git(src, "config", "user.email", "it@qits.local");
    git(src, "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello from the self-clone IT\n");
    git(src, "add", "hello.txt");
    git(src, "commit", "-q", "-m", "initial");
    Path bare = work.resolve("served.git");
    git(work, "clone", "-q", "--bare", src.toString(), bare.toString());
    git(bare, "update-server-info"); // write info/refs + objects/info/packs for the dumb protocol
    return bare;
  }

  private static void serveBareFile(
      Path bare, String rel, io.vertx.core.http.HttpServerRequest req) {
    // Ignore any ?service=... query: returning the dumb `info/refs` makes git fall back to dumb
    // HTTP.
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

  private static String rev(Path bare) throws Exception {
    return exec(new ProcessBuilder("git", "-C", bare.toString(), "rev-parse", "HEAD")).trim();
  }

  private static void git(Path cwd, String... args) throws Exception {
    String[] argv = new String[args.length + 3];
    argv[0] = "git";
    argv[1] = "-C";
    argv[2] = cwd.toString();
    System.arraycopy(args, 0, argv, 3, args.length);
    exec(new ProcessBuilder(argv));
  }

  private static String execCapture(String container, String... argv) throws Exception {
    String[] full = new String[argv.length + 3];
    full[0] = RUNTIME;
    full[1] = "exec";
    full[2] = container;
    System.arraycopy(argv, 0, full, 3, argv.length);
    return exec(new ProcessBuilder(full));
  }

  private static String exec(ProcessBuilder builder) throws Exception {
    Process process = builder.redirectErrorStream(true).start();
    String out = new String(process.getInputStream().readAllBytes());
    process.waitFor(60, TimeUnit.SECONDS);
    return out;
  }

  private boolean dockerAndImageAvailable() {
    try {
      return new ProcessBuilder(RUNTIME, "image", "inspect", IMAGE).start().waitFor() == 0;
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
