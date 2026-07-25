package eu.wohlben.qits.domain.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.dto.CommandDto;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorkspaceService;
import eu.wohlben.qits.domain.service.dto.ServiceEventDto;
import eu.wohlben.qits.domain.service.entity.ServiceEventKind;
import eu.wohlben.qits.domain.service.entity.ServiceEventSeverity;
import eu.wohlben.qits.domain.service.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
public class ServiceAgentNotifierTest {

  @Inject ProjectService projectService;

  @Inject RepositoryService repositoryService;

  @Inject WorkspaceService workspaceService;

  @Inject CommandService commandService;

  @Inject CommandRegistry commandRegistry;

  @Inject ServiceAgentNotifier notifier;

  @Inject ServiceEventSpool spool;

  private String repoWithWorkspace() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Notify Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    return repo.id;
  }

  private static ServiceEventDto event(String repoId, String summary, String excerpt) {
    return new ServiceEventDto(
        repoId,
        "work",
        "daemon-1",
        "dev-server",
        ServiceEventKind.STATUS_CHANGED,
        ServiceEventSeverity.ERROR,
        ServiceStatus.CRASHED,
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
