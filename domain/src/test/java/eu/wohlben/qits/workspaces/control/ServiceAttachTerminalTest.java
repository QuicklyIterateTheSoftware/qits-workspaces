package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Covers the interactive-terminal seam of tmux-backed services (Increment 2): {@link
 * ContainerRuntime#attachServiceCommand} plus the ordinary {@link CommandRegistry} PTY path
 * together give a live attach that streams the running service's output and accepts input/resize,
 * and whose termination cleans up without touching the daemon. Real tmux behavior is exercised by
 * the extended real-docker IT; here the fake runtime emulates the attach with a {@code tail -f}, so
 * the wiring — spawn → stream → input/resize → terminate — is verified without a terminal
 * multiplexer.
 */
@QuarkusTest
public class ServiceAttachTerminalTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject ProjectService projectService;
  @Inject RepositoryService repositoryService;
  @Inject WorkspaceService workspaceService;
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
  public void attachStreamsTheServiceOutputAndTerminatesCleanly() throws Exception {
    String fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
    var project = projectService.create("Attach Project", null);
    var repo = repositoryService.cloneRepository(fixtureUrl, null, project);
    workspaceService.createWorkspace(repo.id, "work", "master", "work");
    // Creation is lazy; provision the container for the out-of-band session start below.
    workspaceService.ensureContainer(repo.id, "work");
    String container = containers.containerName("work", repo.id);

    // Start a service session directly (the supervisor's follower isn't needed to prove the
    // attach):
    // it prints a recognizable marker line the attach must stream.
    String serviceId = "attach-service-1";
    String script = "while true; do echo attach-marker; sleep 0.2; done";
    containers.startService(container, serviceId, script, Map.of("QITS_SERVICE_ID", serviceId));
    assertTrue(containers.serviceAlive(container, serviceId), "the service session is running");

    String sessionId = "service-attach-test";
    CapturingSink sink = new CapturingSink();
    try {
      // The exact wiring ServiceTerminalSocket uses: an ordinary registry PTY running the runtime's
      // attach command, with no-op exit/log listeners (the follower owns persistence).
      registry.spawn(
          sessionId,
          container,
          containers.attachServiceCommand(serviceId),
          Map.of("TERM", "xterm-256color"),
          (id, exitCode, terminatedManually) -> {},
          (id, sequence, channel, content, timestamp) -> {},
          sink);

      assertTrue(
          awaitContains(sink, "attach-marker"),
          "the attach streams the running service's live output");
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
        containers.serviceAlive(container, serviceId), "the daemon keeps running after detach");

    containers.killService(container, serviceId);
    // Poll rather than snapshot: a SIGKILL'd detached process can linger briefly (zombie until
    // reaped) under suite load, and serviceAlive reads ProcessHandle.isAlive.
    assertTrue(awaitServiceDead(container, serviceId), "kill tears the service session down");
  }

  private boolean awaitServiceDead(String container, String serviceId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (!containers.serviceAlive(container, serviceId)) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
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
