package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.command.control.CommandService;
import eu.wohlben.qits.domain.command.dto.CommandDto;
import eu.wohlben.qits.domain.command.dto.CommandLogLineDto;
import eu.wohlben.qits.domain.daemon.dto.DaemonEventDto;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventKind;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import eu.wohlben.qits.domain.daemon.entity.DaemonStatus;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorktreeService;
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
 * running chat's stdin (visible via the persisted user echo), and with no chat running it is
 * spooled and handed to the next session.
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

  @Inject WorktreeService worktreeService;

  @Inject CommandService commandService;

  @Inject CommandRegistry commandRegistry;

  @Inject DaemonAgentNotifier notifier;

  @Inject DaemonEventSpool spool;

  private String repoWithWorktree() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Notify Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    worktreeService.createWorktree(repo.id, "work", "master", "work");
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
    String repoId = repoWithWorktree();
    // A stand-in chat process that stays alive with stdin open (no real claude in tests).
    CommandDto chat =
        commandService.launchChat(repoId, "work", "Claude chat", "sleep 10", Map.of());
    try {
      notifier.deliver(event(repoId, "NPE in handler", "stacktrace-here"));

      // chatSend echoes the injected turn into the unified stream, which is persisted — so the
      // message (with its [daemon:…] prefix) must show up in the chat's log.
      List<CommandLogLineDto> lines = awaitLogContaining(chat.id(), "[daemon:dev-server]");
      assertTrue(
          lines.stream().anyMatch(l -> l.content().contains("stacktrace-here")),
          "the log excerpt travels with the message: " + lines);
      assertEquals(
          List.of(),
          spool.drain(repoId, "work"),
          "a delivered event must not additionally be spooled");
    } finally {
      commandRegistry.terminate(chat.id());
    }
  }

  @Test
  public void spoolsWhenNoChatIsRunning() throws Exception {
    String repoId = repoWithWorktree();

    notifier.deliver(event(repoId, "crashed (exit 1)", "boom"));

    List<String> spooled = spool.drain(repoId, "work");
    assertEquals(1, spooled.size());
    assertTrue(
        spooled.get(0).startsWith("[daemon:dev-server] ERROR: crashed (exit 1)"), spooled.get(0));
    assertTrue(spooled.get(0).contains("boom"), spooled.get(0));
    assertEquals(List.of(), spool.drain(repoId, "work"), "drain empties the spool");
  }

  private List<CommandLogLineDto> awaitLogContaining(String commandId, String needle)
      throws InterruptedException {
    for (int i = 0; i < 40; i++) {
      List<CommandLogLineDto> lines = commandService.log(commandId, null);
      if (lines.stream().anyMatch(l -> l.content().contains(needle))) {
        return lines;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("log never contained: " + needle);
  }
}
