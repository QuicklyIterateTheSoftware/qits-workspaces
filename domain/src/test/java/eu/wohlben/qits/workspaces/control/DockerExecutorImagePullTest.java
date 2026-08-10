package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pull-if-absent in front of {@code docker run}: the launch of a workspace container whose image the
 * host daemon does not hold.
 *
 * <p>Plain JUnit with no docker. {@link DockerExecutor#runCapturing} is the single place that class
 * starts a process, so a subclass that overrides it records the argv and answers with a scripted
 * exit code — which is what makes the ORDER of the verbs assertable at all. A test against a real
 * daemon could only see the outcome, and the outcome is the same whether the pull happened first or
 * docker pulled implicitly during the run.
 */
class DockerExecutorImagePullTest {

  /**
   * The pin's shape rather than the pin: registry host with a port, repository path, calver tag. The
   * version is invented and stays invented, for the reason {@code WorkspaceContainerFactoryTest}
   * gives — the release train moves the real one and would not move a test literal.
   */
  private static final String IMAGE = "localhost:8081/qits/workspace:2026.101.1";

  /** A {@link DockerExecutor} that runs nothing: every invocation is recorded and scripted. */
  private static final class RecordingDocker extends DockerExecutor {

    final List<List<String>> commands = new ArrayList<>();
    final List<Duration> timeouts = new ArrayList<>();
    /** Exit code by first two words of the command, e.g. "docker pull". Absent means 0. */
    final Map<String, Integer> exitCodes = new LinkedHashMap<>();

    @Override
    ExecResult runCapturing(
        Path cwd, List<String> command, Consumer<String> onLine, Duration timeout) {
      commands.add(List.copyOf(command));
      timeouts.add(timeout);
      String verb = command.get(0) + " " + command.get(1);
      int exitCode = exitCodes.getOrDefault(verb, 0);
      return new ExecResult(exitCode, exitCode == 0 ? "" : verb + " failed");
    }

    /** The verbs run, as "docker pull"-style pairs, in order. */
    List<String> verbs() {
      return commands.stream().map(c -> c.get(0) + " " + c.get(1)).toList();
    }
  }

  private static RecordingDocker executor() {
    RecordingDocker docker = new RecordingDocker();
    docker.runtime = "docker";
    docker.containerNetwork = "network";
    docker.imagePullTimeoutMs = 900000;
    docker.containerFactory = factory();
    return docker;
  }

  private static WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.image = IMAGE;
    f.network = "qits-net";
    f.claudeVolume = "";
    f.claudeMount = "/claude-home";
    f.mavenVolume = "";
    f.pnpmVolume = "";
    f.timezone = Optional.empty();
    f.memoryLimit = Optional.empty();
    f.pidsLimit = Optional.empty();
    f.cpus = Optional.empty();
    // Off, so the launch is exactly the image check and the run — no volume verb in between to read
    // past. The persistent volume has its own coverage.
    f.persistWorkspace = false;
    f.gitIdentity = new GitIdentity();
    f.gitIdentity.name = "qits";
    f.gitIdentity.email = "qits@local";
    f.qitsHostResolver = new QitsHostResolver();
    f.qitsHostResolver.configured = "qits";
    f.qitsPort = "8080";
    f.daemonApiToken = "qits-workspace-daemon";
    f.nameResolver = StubInstance.empty();
    f.repositories = StubInstance.empty();
    return f;
  }

  @Test
  void anImagePresentOnTheHostIsNotPulled() {
    RecordingDocker docker = executor();

    docker.run("repo12345678abc", "work", 1L, "main", null);

    // inspect answered 0, so the wire is never touched: the ordinary launch costs no pull.
    assertEquals(List.of("docker image", "docker run"), docker.verbs());
    assertFalse(docker.commands.toString().contains("pull"), docker.commands.toString());
  }

  @Test
  void anAbsentImageIsPulledBeforeTheContainerIsRun() {
    RecordingDocker docker = executor();
    docker.exitCodes.put("docker image", 1); // `docker image inspect` misses

    docker.run("repo12345678abc", "work", 1L, "main", null);

    assertEquals(List.of("docker image", "docker pull", "docker run"), docker.verbs());
    assertEquals(List.of("docker", "pull", IMAGE), docker.commands.get(1));
    // The pull is the one verb here that reaches the network, and it is the one verb with a bound.
    assertEquals(Duration.ofMillis(900000), docker.timeouts.get(1));
    assertNull(docker.timeouts.get(0), "an inspect is local and waits as long as it likes");
    assertNull(docker.timeouts.get(2), "a run is local too — the pull already happened");
  }

  @Test
  void aPinNothingPublishedFailsNamingTheImageAndTheRegistry() {
    RecordingDocker docker = executor();
    docker.exitCodes.put("docker image", 1);
    docker.exitCodes.put("docker pull", 1);

    InternalServerErrorException failure =
        assertThrows(
            InternalServerErrorException.class,
            () -> docker.run("repo12345678abc", "work", 1L, "main", null));

    // The two facts an operator needs to act on a wrong pin: which reference, and which registry was
    // asked for it. A message with only "pull failed" in it sends them to the wrong host.
    assertTrue(failure.getMessage().contains(IMAGE), failure.getMessage());
    assertTrue(failure.getMessage().contains("localhost:8081"), failure.getMessage());
    // ...and nothing is started on the old image that may still be lying around under another tag.
    assertEquals(List.of("docker image", "docker pull"), docker.verbs());
  }

  @Test
  void theRegistryNamedInAFailureIsTheReferencesOwn() {
    // A host is a leading segment carrying a dot or a port, or literally localhost — docker's rule,
    // and the reason `qits/workspace` is a namespace on the default registry rather than a host.
    assertEquals("localhost:8081", DockerExecutor.registryOf("localhost:8081/qits/workspace:1"));
    assertEquals("registry.example.com", DockerExecutor.registryOf("registry.example.com/a/b:1"));
    assertEquals("docker.io", DockerExecutor.registryOf("qits/workspace:latest"));
    assertEquals("docker.io", DockerExecutor.registryOf("alpine"));
  }
}
