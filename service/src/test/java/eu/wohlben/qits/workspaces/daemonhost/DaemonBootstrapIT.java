package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapStep;
import eu.wohlben.qits.workspacedaemon.protocol.Bootstrapped;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker proof of the <b>daemon-run bootstrap chain</b> (docs/epics/qits-workspace-daemon/
 * Part 3): the native {@code workspace-daemon}, after its autonomous self-clone, reads {@code
 * /workspace/.qits-config.yml} from the checkout and runs the declared bootstrap chain
 * <em>itself</em> — no host {@code docker exec bash -lc} — streaming each step's phase and outcome
 * and a terminal {@link Bootstrapped} home, and only its {@code check}-passing commands touch the
 * checkout.
 *
 * <p>Reuses {@link DaemonSelfCloneIT}'s standalone-Vert.x approach: one server plays the control
 * socket (collecting the bootstrap events) and a dumb-HTTP git host (serving a bare repo whose
 * commit carries a two-step {@code bootstrap:} — one that runs, one whose {@code check} skips it).
 * The in-JVM {@link DaemonControlSocketTest} covers the backend await side.
 *
 * <p>Part of the <strong>extended</strong> suite ({@code ./mvnw verify -Pextended}); self-skips
 * when docker or the {@code qits/workspace} image (built WITH the workspace-daemon stage) is
 * absent.
 */
@Tag("extended")
public class DaemonBootstrapIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");
  private static final String REPO_ID = "bootstrap-repo";
  private static final String BRANCH = "main";

  @Test
  public void daemonRunsBootstrapChainFromInContainerConfigOnBoot() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(), "docker + " + IMAGE + " (built with workspace-daemon) required");

    Path work = Files.createTempDirectory("qits-bootstrap-it");
    Path bare = prepareServedBareRepo(work);

    Vertx vertx = Vertx.vertx();
    String container = "qits-bootstrap-it-" + UUID.randomUUID().toString().substring(0, 8);
    CompletableFuture<Hello> helloReceived = new CompletableFuture<>();
    CompletableFuture<Provisioned> provisioned = new CompletableFuture<>();
    CompletableFuture<ProvisionFailed> failed = new CompletableFuture<>();
    CompletableFuture<Bootstrapped> bootstrapped = new CompletableFuture<>();
    Map<String, String> outcomes = new ConcurrentHashMap<>(); // step name -> outcome
    CopyOnWriteArrayList<String> steps = new CopyOnWriteArrayList<>();

    HttpServer server = vertx.createHttpServer();
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
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
                  switch (message) {
                    case Hello hello -> helloReceived.complete(hello);
                    case Provisioned p -> provisioned.complete(p);
                    case ProvisionFailed f -> failed.complete(f);
                    case BootstrapStep s -> steps.add(s.name() + ":" + s.phase());
                    case BootstrapOutcome o -> outcomes.put(o.name(), o.outcome());
                    case Bootstrapped b -> bootstrapped.complete(b);
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
      String url = "ws://host.docker.internal:" + port + "/workspaces/daemon/it-ws";
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
          IMAGE);

      assertEquals("it-ws", helloReceived.get(30, TimeUnit.SECONDS).workspaceId());
      assertFalse(failed.isDone(), () -> "unexpected ProvisionFailed: " + failed.getNow(null));
      provisioned.get(60, TimeUnit.SECONDS);

      // The daemon ran the chain itself and reported the terminal + per-step outcomes.
      Bootstrapped done = bootstrapped.get(60, TimeUnit.SECONDS);
      assertTrue(done.ok(), "the chain (one run, one skip) succeeds overall");
      assertEquals("SUCCEEDED", outcomes.get("mark"), outcomes.toString());
      assertEquals("SKIPPED", outcomes.get("skipme"), outcomes.toString());
      assertTrue(steps.contains("mark:EXECUTE"), steps.toString());
      assertTrue(steps.contains("skipme:SKIP"), steps.toString());

      // And the checkout reflects it: the run step wrote its marker, the skipped one did not — all
      // done in-container, with no host `docker exec bash -lc` ever issued by this test.
      String marker = execCapture(container, "cat", "/workspace/bootstrap-ran.txt").trim();
      assertEquals("bootstrapped", marker, "only the check-passing step wrote the marker");
    } finally {
      run(RUNTIME, "rm", "-f", container);
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  /**
   * A bare repo with one commit on {@link #BRANCH} carrying a two-step {@code bootstrap:} config: a
   * command that runs (writing a marker) and one whose {@code check} skips it.
   */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    git(src, "init", "-q", "-b", BRANCH);
    git(src, "config", "user.email", "it@qits.local");
    git(src, "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello from the bootstrap IT\n");
    Files.writeString(
        src.resolve(".qits-config.yml"),
        "version: 1\n"
            + "bootstrap:\n"
            + "  - name: mark\n"
            + "    execute: echo -n bootstrapped > /workspace/bootstrap-ran.txt\n"
            + "  - name: skipme\n"
            + "    check: test -f /workspace/nonexistent-marker\n"
            + "    execute: echo -n should-not-run >> /workspace/bootstrap-ran.txt\n");
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
