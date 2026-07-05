package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import eu.wohlben.qits.domain.command.control.CommandRegistry;
import eu.wohlben.qits.domain.project.control.ProjectService;
import eu.wohlben.qits.domain.repository.control.ContainerRuntime;
import eu.wohlben.qits.domain.repository.control.RepositoryService;
import eu.wohlben.qits.domain.repository.control.WorktreeService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Covers the interactive-terminal seam of tmux-backed daemons (Increment 2): {@link
 * ContainerRuntime#attachDaemonCommand} plus the ordinary {@link CommandRegistry} PTY path together
 * give a live attach that streams the running daemon's output and accepts input/resize, and whose
 * termination cleans up without touching the daemon. Real tmux behavior is exercised by the
 * extended real-docker IT; here the fake runtime emulates the attach with a {@code tail -f}, so the
 * wiring — spawn → stream → input/resize → terminate — is verified without a terminal multiplexer.
 */
@QuarkusTest
@TestProfile(DaemonAttachTerminalTest.TestProfile.class)
public class DaemonAttachTerminalTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-attach-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorktreeService worktreeService;
  @Inject ContainerRuntime containers;
  @Inject CommandRegistry registry;

  /** A framework-free sink that accumulates everything the PTY writes. */
  private static final class CapturingSink implements CommandOutputSink {
    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean open = new AtomicBoolean(true);

    @Override
    public synchronized void write(String data) {
      buffer.append(data);
    }

    @Override
    public boolean isOpen() {
      return open.get();
    }

    synchronized String text() {
      return buffer.toString();
    }
  }

  @Test
  public void attachStreamsTheDaemonOutputAndTerminatesCleanly() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Attach Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    worktreeService.createWorktree(repo.id, "work", "master", "work");
    String container = containers.containerName("work", repo.id);

    // Start a daemon session directly (the supervisor's follower isn't needed to prove the attach):
    // it prints a recognizable marker line the attach must stream.
    String daemonId = "attach-daemon-1";
    String script = "while true; do echo attach-marker; sleep 0.2; done";
    containers.startDaemon(container, daemonId, script, Map.of("QITS_DAEMON_ID", daemonId));
    assertTrue(containers.daemonAlive(container, daemonId), "the daemon session is running");

    String sessionId = "daemon-attach-test";
    CapturingSink sink = new CapturingSink();
    try {
      // The exact wiring DaemonTerminalSocket uses: an ordinary registry PTY running the runtime's
      // attach command, with no-op exit/log listeners (the follower owns persistence).
      registry.spawn(
          sessionId,
          container,
          containers.attachDaemonCommand(daemonId),
          Map.of("TERM", "xterm-256color"),
          (id, exitCode, terminatedManually) -> {},
          (id, sequence, channel, content, timestamp) -> {},
          sink);

      assertTrue(
          awaitContains(sink, "attach-marker"),
          "the attach streams the running daemon's live output");
      assertTrue(registry.isRunning(sessionId), "the attach session is live");
      // Input and resize are accepted by the live PTY (a tail ignores stdin, but the call
      // succeeds).
      assertTrue(registry.input(sessionId, "\n".getBytes()), "input reaches the attach PTY");
      assertTrue(registry.resize(sessionId, 120, 40), "resize reaches the attach PTY");
    } finally {
      registry.terminate(sessionId);
    }

    assertTrue(awaitStopped(sessionId), "terminating the attach removes it from the registry");
    // The daemon itself is untouched: killing the attach client only detaches it.
    assertTrue(
        containers.daemonAlive(container, daemonId), "the daemon keeps running after detach");

    containers.killDaemon(container, daemonId);
    assertFalse(containers.daemonAlive(container, daemonId), "kill tears the daemon session down");
  }

  private boolean awaitContains(CapturingSink sink, String needle) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (sink.text().contains(needle)) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private boolean awaitStopped(String sessionId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (!registry.isRunning(sessionId)) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }
}
