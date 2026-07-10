package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.dto.CommandDto;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The agent sink end to end: an event lands as one prefixed stream-json user message on the newest
 * running chat's stdin (visible via the live user echo on an attached sink), and with no chat
 * running it is spooled and handed to the next session.
 */
@QuarkusTest
@TestProfile(DaemonAgentNotifierTest.TestProfile.class)
public class DaemonAgentNotifierTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-notify-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject WorkspaceService workspaceService;

  @Inject CommandService commandService;

  @Inject CommandRegistry commandRegistry;

  @Inject DaemonAgentNotifier notifier;

  @Inject DaemonEventSpool spool;

  private String repoWithWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Notify Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  private static DaemonEventDto event(String repoId, String summary, String excerpt) {
    return new DaemonEventDto(
        repoId,
        "work",
        "daemon-1",
        "dev-server",
        DaemonEventKind.ERROR_DETECTED,
        DaemonEventSeverity.ERROR,
        DaemonStatus.DEGRADED,
        summary,
        excerpt,
        "cmd-1",
        null,
        null,
        null,
        null,
        Instant.now());
  }

  @Test
  public void deliversToTheNewestRunningChatAsAPrefixedUserMessage() throws Exception {
    String repoId = repoWithWorkspace();
    // A stand-in chat process that stays alive with stdin open (no real claude in tests).
    CommandDto chat =
        commandService.launchChat(repoId, "work", "Claude chat", "sleep 10", Map.of());
    try {
      // chatSend echoes the injected turn into the unified live stream (ring + broadcast; user
      // echoes are no longer persisted — the durable record is the transcript import), so the
      // message (with its [daemon:…] prefix) must reach an attached sink.
      CapturingSink sink = new CapturingSink();
      assertTrue(commandRegistry.attach(chat.id(), sink), "the chat must accept a sink");

      notifier.deliver(event(repoId, "NPE in handler", "stacktrace-here"));

      String streamed = awaitSinkContaining(sink, "[daemon:dev-server]");
      assertTrue(
          streamed.contains("stacktrace-here"),
          "the log excerpt travels with the message: " + streamed);
      assertEquals(
          List.of(),
          spool.drain(repoId, "work"),
          "a delivered event must not additionally be spooled");
    } finally {
      commandRegistry.terminate(chat.id());
    }
  }

  private static final class CapturingSink implements CommandOutputSink {
    private final StringBuilder received = new StringBuilder();

    @Override
    public synchronized void write(String data) {
      received.append(data);
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    synchronized String text() {
      return received.toString();
    }
  }

  private String awaitSinkContaining(CapturingSink sink, String needle)
      throws InterruptedException {
    for (int i = 0; i < 40; i++) {
      String text = sink.text();
      if (text.contains(needle)) {
        return text;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("sink never received '" + needle + "': " + sink.text());
  }

  @Test
  public void spoolsWhenNoChatIsRunning() throws Exception {
    String repoId = repoWithWorkspace();

    notifier.deliver(event(repoId, "crashed (exit 1)", "boom"));

    List<String> spooled = spool.drain(repoId, "work");
    assertEquals(1, spooled.size());
    assertTrue(
        spooled.get(0).startsWith("[daemon:dev-server] ERROR: crashed (exit 1)"), spooled.get(0));
    assertTrue(spooled.get(0).contains("boom"), spooled.get(0));
    assertEquals(List.of(), spool.drain(repoId, "work"), "drain empties the spool");
  }
}
