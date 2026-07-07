package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The always-on cross-cutting config the factory guarantees on every workspace container. Plain
 * JUnit (same package) sets the {@code @ConfigProperty} fields directly, so no docker or Quarkus
 * boot is needed — this is the coverage {@link FakeContainerRuntime} (which models neither the
 * volume nor the labels) cannot give.
 */
class WorkspaceContainerFactoryTest {

  private WorkspaceContainerFactory factory() {
    WorkspaceContainerFactory f = new WorkspaceContainerFactory();
    f.image = "qits/workspace:latest";
    f.network = "qits-net";
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    return f;
  }

  @Test
  void alwaysSeedsTheCredentialVolumeLabelsHostUserImageAndCommand() {
    List<String> argv =
        factory().forWorkspace("repo12345678abc", "work", "main", "0parent").toRunArgv();

    // The guarantee: the shared credential volume is mounted on every container.
    assertSequence(argv, "-v", "qits_shared_dot_claude:/claude-home");
    // ...and every in-container `claude` is pointed at it regardless of HOME, so a login persists
    // across containers even for ad-hoc runs.
    assertSequence(argv, "-e", "CLAUDE_CONFIG_DIR=/claude-home/.claude");
    // The qits.* reconciliation labels.
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.workspace=work");
    assertSequence(argv, "--label", "qits.branch=main");
    assertSequence(argv, "--label", "qits.parent=0parent");
    // Host alias, host uid, deterministic name, image, entrypoint.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertTrue(argv.contains("--user"), argv.toString());
    assertSequence(argv, "--name", "qits-ws-work-repo1234");
    assertTrue(argv.contains("qits/workspace:latest"), argv.toString());
    assertSequence(argv, "sleep", "infinity");
    // The shared network, so qits reaches the container's ports by DNS name with no host publish.
    assertSequence(argv, "--network", "qits-net");
    assertFalse(argv.contains("-p"), argv.toString());
  }

  @Test
  void blankCredentialVolumeOmitsOnlyTheMount() {
    WorkspaceContainerFactory f = factory();
    f.claudeVolume = "";

    List<String> argv = f.forWorkspace("repo12345678abc", "work", "main", null).toRunArgv();

    assertFalse(argv.contains("-v"), argv.toString());
    // With no shared volume there is nothing to point CLAUDE_CONFIG_DIR at, so it is omitted too.
    assertFalse(argv.contains("CLAUDE_CONFIG_DIR=/claude-home/.claude"), argv.toString());
    // Everything else still present, incl. an empty parent label for the null parent.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.parent=");
    assertSequence(argv, "--name", "qits-ws-work-repo1234");
  }

  /** Assert {@code first} appears immediately followed by {@code second}. */
  private static void assertSequence(List<String> argv, String first, String second) {
    for (int i = 0; i < argv.size() - 1; i++) {
      if (first.equals(argv.get(i)) && second.equals(argv.get(i + 1))) {
        return;
      }
    }
    throw new AssertionError("expected [" + first + ", " + second + "] in " + argv);
  }
}
