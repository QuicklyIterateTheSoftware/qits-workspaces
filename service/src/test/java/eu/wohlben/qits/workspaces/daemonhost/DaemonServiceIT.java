package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.ProvisionFailed;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.ServiceTransition;
import eu.wohlben.qits.workspacedaemon.protocol.SignalService;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker proof of <b>daemon-supervised services</b> (docs/epics/qits-workspace-daemon/ Part
 * 4): the native {@code workspace-daemon}, as the tail of its own boot sequence, starts the
 * auto-start service declared in {@code /workspace/.qits-config.yml} <em>itself</em> (no host
 * {@code docker exec tmux}), reports its lifecycle over the socket as {@link ServiceTransition}s,
 * and — on a {@link SignalService} stop — group-kills the whole session <b>including a backgrounded
 * fork</b> via {@code pkill -s}, with no {@code /proc} scan.
 *
 * <p>Reuses {@link DaemonBootstrapIT}'s standalone-Vert.x approach: one server plays the control
 * socket (collecting service events, and sending the stop) and a dumb-HTTP git host (serving a bare
 * repo whose commit declares an auto-start service that echoes a ready banner and holds a
 * backgrounded child). The in-JVM {@link DaemonControlSocketTest} covers the backend projection
 * side.
 *
 * <p>Part of the <strong>extended</strong> suite ({@code ./mvnw verify -Pextended}); self-skips
 * when docker or the {@code qits/workspace} image (built WITH the workspace-daemon stage) is
 * absent.
 */
@Tag("extended")
public class DaemonServiceIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");
  private static final String REPO_ID = "service-repo";
  private static final String BRANCH = "main";
  // A distinctive duration so pgrep can prove the backgrounded fork was reaped, not some other
  // sleep.
  private static final String FORK_MARKER = "987654";

  @Test
  public void daemonStartsSupervisesAndReapsServiceOnBoot() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(), "docker + " + IMAGE + " (built with workspace-daemon) required");

    Path work = Files.createTempDirectory("qits-service-it");
    Path bare = prepareServedBareRepo(work);

    Vertx vertx = Vertx.vertx();
    String container = "qits-service-it-" + UUID.randomUUID().toString().substring(0, 8);
    CompletableFuture<Hello> helloReceived = new CompletableFuture<>();
    CompletableFuture<Provisioned> provisioned = new CompletableFuture<>();
    CompletableFuture<ProvisionFailed> failed = new CompletableFuture<>();
    CompletableFuture<ServiceTransition> ready = new CompletableFuture<>();
    CompletableFuture<ServiceTransition> stopped = new CompletableFuture<>();
    CopyOnWriteArrayList<String> states = new CopyOnWriteArrayList<>();

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
                    case ServiceTransition event -> {
                      states.add(event.id() + ":" + event.state());
                      if ("web".equals(event.id())) {
                        if (ServiceTransition.State.READY.equals(event.state())
                            && !ready.isDone()) {
                          ready.complete(event);
                          // Play qits: once the service is up, ask the daemon to stop it.
                          ws.writeTextMessage(
                              new JsonObject(
                                      DaemonCodec.encode(
                                          new SignalService(
                                              UUID.randomUUID().toString(), "web", "TERM")))
                                  .encode());
                        } else if (ServiceTransition.State.STOPPED.equals(event.state())) {
                          stopped.complete(event);
                        }
                      }
                    }
                    default -> {
                      /* DaemonLog / CommandChunk / Heartbeat / Bootstrapped — not asserted here */
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

      // The daemon auto-started the service and reported it READY (STARTING first).
      ready.get(60, TimeUnit.SECONDS);
      assertTrue(states.contains("web:STARTING"), states.toString());

      // While running, the backgrounded fork is alive in the container.
      assumeTrue(
          pgrepCount(container, "sleep " + FORK_MARKER) >= 2,
          "the service and its fork should be running before the stop");

      // The SignalService we sent on READY stops it; the daemon reports STOPPED...
      stopped.get(30, TimeUnit.SECONDS);
      // ...and reaped the WHOLE session, including the backgrounded fork — proven by pgrep, with no
      // host /proc scan or `docker exec kill` issued by this test.
      long deadline = System.currentTimeMillis() + 15_000;
      while (System.currentTimeMillis() < deadline
          && pgrepCount(container, "sleep " + FORK_MARKER) > 0) {
        Thread.sleep(200);
      }
      assertEquals(
          0,
          pgrepCount(container, "sleep " + FORK_MARKER),
          "pkill -s reaped the service session incl. the fork");
    } finally {
      run(RUNTIME, "rm", "-f", container);
      server.close();
      vertx.close();
      deleteRecursively(work);
    }
  }

  /**
   * A bare repo with one commit declaring an auto-start service that echoes a banner + holds a
   * fork.
   */
  private static Path prepareServedBareRepo(Path work) throws Exception {
    Path src = work.resolve("src");
    Files.createDirectories(src);
    git(src, "init", "-q", "-b", BRANCH);
    git(src, "config", "user.email", "it@qits.local");
    git(src, "config", "user.name", "qits-it");
    Files.writeString(src.resolve("hello.txt"), "hello from the service IT\n");
    // The service backgrounds a sleep (the escaped-fork case, in miniature), prints its ready
    // banner,
    // then blocks. auto-start is on, so the daemon starts it as the boot-sequence tail with no host
    // instruction.
    Files.writeString(
        src.resolve(".qits-config.yml"),
        "version: 1\n"
            + "daemons:\n"
            + "  - name: web\n"
            + "    start: \"( sleep "
            + FORK_MARKER
            + " & ) ; echo listening ; sleep "
            + FORK_MARKER
            + "\"\n"
            + "    ready-pattern: listening\n"
            + "    auto-start: true\n");
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

  private static int pgrepCount(String container, String pattern) {
    try {
      Process p =
          new ProcessBuilder(RUNTIME, "exec", container, "pgrep", "-f", pattern)
              .redirectErrorStream(true)
              .start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor(30, TimeUnit.SECONDS);
      return (int) out.lines().filter(l -> l.matches("\\d+")).count();
    } catch (Exception e) {
      return -1;
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
