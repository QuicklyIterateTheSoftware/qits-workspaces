package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.ContainerRuntime.ExecResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * What {@link DockerExecutor#ensureNetwork} creates when the shared network is missing.
 *
 * <p>The driver is the whole subject. A swarm service cannot join a bridge, so a bridge created
 * here would partition the platform from every workspace container the moment the network had to be
 * rebuilt. An existing network is never touched, whatever its driver — this service still runs on
 * the bridge platform until the swarm re-bootstrap converts it.
 *
 * <p>Plain JUnit with no docker, the way {@link DockerExecutorImagePullTest} does it: {@link
 * DockerExecutor#runCapturing} is the single place this class starts a process, so a subclass that
 * overrides it records the argv and answers with a scripted result.
 */
class DockerExecutorNetworkTest {

  private static final String NET = "qits-net";

  /** A {@link DockerExecutor} that runs nothing: every invocation is recorded and scripted. */
  private static final class RecordingDocker extends DockerExecutor {

    final List<List<String>> commands = new ArrayList<>();
    /** Result by command shape, e.g. "create overlay". Absent means exit 0. */
    final Map<String, ExecResult> answers = new LinkedHashMap<>();

    @Override
    ExecResult runCapturing(
        Path cwd, List<String> command, Consumer<String> onLine, Duration timeout) {
      commands.add(List.copyOf(command));
      return answers.getOrDefault(shape(command), new ExecResult(0, ""));
    }

    /** What a command does, as the test talks about it — not the name it does it to. */
    static String shape(List<String> command) {
      if (command.contains("inspect")) {
        return "inspect";
      }
      return command.contains("overlay") ? "create overlay" : "create bridge";
    }

    List<String> shapes() {
      return commands.stream().map(RecordingDocker::shape).toList();
    }
  }

  private static RecordingDocker executor() {
    RecordingDocker docker = new RecordingDocker();
    docker.runtime = "docker";
    WorkspaceContainerFactory factory = new WorkspaceContainerFactory();
    factory.network = NET;
    docker.containerFactory = factory;
    return docker;
  }

  @Test
  void aMissingNetworkIsCreatedAsAnAttachableOverlay() {
    RecordingDocker docker = executor();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));

    docker.ensureNetwork();

    assertEquals(List.of("inspect", "create overlay"), docker.shapes());
    assertEquals(
        List.of("docker", "network", "create", "-d", "overlay", "--attachable", NET),
        docker.commands.get(1));
  }

  @Test
  void anExistingNetworkIsLeftAloneWhateverItsDriver() {
    RecordingDocker docker = executor();
    // The inspect answers 0 — a bridge on today's platform reads exactly like an overlay here, and
    // both are left as they are.

    docker.ensureNetwork();

    assertEquals(List.of("inspect"), docker.shapes());
  }

  @Test
  void aDaemonOutsideASwarmFallsBackToTheDefaultDriver() {
    RecordingDocker docker = executor();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));
    docker.answers.put(
        "create overlay", new ExecResult(1, "Error response from daemon: This node is not a swarm"));

    docker.ensureNetwork();

    // A developer machine has no overlay driver, and a workspace with no network is worse than a
    // bridge. The overlay is still tried first, so a swarm host never gets the bridge.
    assertEquals(List.of("inspect", "create overlay", "create bridge"), docker.shapes());
    assertEquals(List.of("docker", "network", "create", NET), docker.commands.get(2));
  }

  @Test
  void aNetworkCreatedByAnotherProcessIsNotCreatedAgain() {
    RecordingDocker docker = executor();
    docker.answers.put("inspect", new ExecResult(1, "No such network: " + NET));
    docker.answers.put(
        "create overlay",
        new ExecResult(
            1, "Error response from daemon: network with name " + NET + " already exists"));

    docker.ensureNetwork();

    // The loser of the race must not create a bridge beside the winner's overlay.
    assertEquals(List.of("inspect", "create overlay"), docker.shapes());
  }

  @Test
  void aBlankNetworkNameRunsNothing() {
    RecordingDocker docker = executor();
    docker.containerFactory.network = "";

    docker.ensureNetwork();

    assertTrue(docker.commands.isEmpty(), docker.commands.toString());
  }
}
