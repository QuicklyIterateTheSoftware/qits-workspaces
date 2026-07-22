package eu.wohlben.qits.workspacedaemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.CommandExit;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.RunCommand;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real-docker proof that the native {@code workspace-daemon} binary is the workspace container's
 * process, dials home over the control socket, and executes a backend-initiated command
 * in-container (docs/epics/qits-workspace-daemon/). Standalone Vert.x WebSocket server stands in
 * for the qits backend (the in-JVM {@link DaemonControlSocketTest} already covers {@link
 * WorkspaceDaemonRegistry} itself), so this isolates "does the shipped binary actually run and
 * round-trip a command".
 *
 * <p>Part of the <strong>extended</strong> suite ({@code ./mvnw verify -Pextended}); self-skips
 * when docker or the {@code qits/workspace} image (which must be built WITH the workspace-daemon
 * stage) is absent. Reaches the host-run test server the same way host-run qits does — {@code
 * host.docker.internal}.
 */
@Tag("extended")
public class DaemonControlSocketIT {

  private static final String IMAGE =
      System.getProperty("qits.workspace.image", "qits/workspace:latest");
  private static final String RUNTIME =
      System.getProperty("qits.workspace.container-runtime", "docker");

  @Test
  public void nativeClientdDialsHomeAndRoundTripsACommand() throws Exception {
    assumeTrue(
        dockerAndImageAvailable(), "docker + " + IMAGE + " (built with workspace-daemon) required");

    Vertx vertx = Vertx.vertx();
    String container = "qits-workspace-daemon-it-" + UUID.randomUUID().toString().substring(0, 8);
    String correlationId = "it-" + UUID.randomUUID();
    CompletableFuture<Hello> helloReceived = new CompletableFuture<>();
    CompletableFuture<Integer> exitReceived = new CompletableFuture<>();
    StringBuilder stdout = new StringBuilder();

    // A minimal stand-in backend: on HELLO, ask the client to run `echo`; collect the reply.
    HttpServer server = vertx.createHttpServer();
    server.webSocketHandler(
        ws ->
            ws.textMessageHandler(
                text -> {
                  DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
                  switch (message) {
                    case Hello hello -> {
                      helloReceived.complete(hello);
                      ws.writeTextMessage(
                          encode(
                              new RunCommand(
                                  correlationId,
                                  List.of("echo", "workspace-daemon-it-ok"),
                                  "/workspace",
                                  Map.of())));
                    }
                    case CommandChunk chunk -> {
                      if (chunk.stream() == Stream.STDOUT) {
                        stdout.append(chunk.text());
                      }
                    }
                    case CommandExit exit -> exitReceived.complete(exit.exitCode());
                    default -> {
                      /* Heartbeat / DaemonLog — ignored by the stand-in */
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
          // No command: the workspace image's ENTRYPOINT is qits-workspace-daemon, exactly as
          // production runs it (WorkspaceContainerFactory appends no command).
          IMAGE);

      Hello hello = helloReceived.get(30, TimeUnit.SECONDS);
      assertEquals("it-ws", hello.workspaceId());
      int exit = exitReceived.get(30, TimeUnit.SECONDS);
      assertEquals(0, exit);
      assertTrue(stdout.toString().contains("workspace-daemon-it-ok"), stdout.toString());
    } finally {
      run(RUNTIME, "rm", "-f", container);
      server.close();
      vertx.close();
    }
  }

  private static String encode(DaemonMessage message) {
    return new JsonObject(DaemonCodec.encode(message)).encode();
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
}
