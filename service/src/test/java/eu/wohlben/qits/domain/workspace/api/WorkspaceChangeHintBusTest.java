package eu.wohlben.qits.domain.workspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.domain.daemon.control.DaemonEventService;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint.Topic;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangePublisher;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code domain} → SSE hint bus end to end through real CDI async delivery: the
 * publisher's {@code fireAsync} reaches an {@code @ObservesAsync} observer ({@link HintCollector}),
 * and a real producer ({@link DaemonEventService#publish}) fires the right topic at its
 * choke-point.
 */
@QuarkusTest
class WorkspaceChangeHintBusTest {

  @Inject WorkspaceChangePublisher publisher;

  @Inject DaemonEventService daemonEventService;

  @Inject HintCollector collector;

  @TestHTTPResource("/api/repositories/repo-sse/workspaces/wt-sse/events")
  URL sseUrl;

  @BeforeEach
  void reset() {
    collector.clear();
  }

  /** Drain hints until one for {@code repoId} arrives (ignoring unrelated ones), or time out. */
  private WorkspaceChangeHint awaitHint(String repoId, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    long remaining;
    while ((remaining = deadline - System.currentTimeMillis()) > 0) {
      WorkspaceChangeHint hint = collector.poll(remaining);
      if (hint == null) {
        return null;
      }
      if (hint.repoId().equals(repoId)) {
        return hint;
      }
    }
    return null;
  }

  @Test
  void firedHintsAreDeliveredToAsyncObservers() throws InterruptedException {
    publisher.fire("repo-bus", "wt-bus", Topic.COMMANDS);

    WorkspaceChangeHint hint = awaitHint("repo-bus", 2000);
    assertNotNull(hint, "expected the fired hint to reach the async observer");
    assertEquals(Topic.COMMANDS, hint.topic());
    assertEquals("wt-bus", hint.workspaceId());
  }

  @Test
  void publishingADaemonEventFiresADaemonEventsHint() throws InterruptedException {
    daemonEventService.publish(
        new DaemonEventDto(
            "repo-de",
            "wt-de",
            "daemon-1",
            "Dev server",
            DaemonEventKind.STATUS_CHANGED,
            DaemonEventSeverity
                .INFO, // INFO so the agent notifier is skipped; the hint fires anyway
            DaemonStatus.READY,
            "ready",
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now()));

    WorkspaceChangeHint hint = awaitHint("repo-de", 2000);
    assertNotNull(hint, "publish() should fire a DAEMON_EVENTS hint");
    assertEquals(Topic.DAEMON_EVENTS, hint.topic());
    assertEquals("wt-de", hint.workspaceId());
  }

  @Test
  void theSseEndpointStreamsAHintFrameOverHttp() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(sseUrl.toURI())
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();

    // Open the stream; when send() returns, the server has begun the response and subscribed.
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());

    BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    Thread reader =
        new Thread(
            () -> {
              try (BufferedReader in =
                  new BufferedReader(
                      new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                  lines.add(line);
                }
              } catch (Exception ignored) {
                // stream closed at test teardown — expected
              }
            });
    reader.setDaemon(true);
    reader.start();

    Thread.sleep(400); // let the subscription settle before firing
    publisher.fire("repo-sse", "wt-sse", Topic.DAEMONS);

    // Read frames until the "daemons" data line arrives (ignoring heartbeat/blank/comment lines).
    long deadline = System.currentTimeMillis() + 3000;
    boolean seen = false;
    long remaining;
    while (!seen && (remaining = deadline - System.currentTimeMillis()) > 0) {
      String line = lines.poll(remaining, TimeUnit.MILLISECONDS);
      if (line != null && line.startsWith("data:") && line.substring(5).trim().equals("daemons")) {
        seen = true;
      }
    }
    Assertions.assertTrue(seen, "expected a 'data: daemons' SSE frame over HTTP");
  }
}
