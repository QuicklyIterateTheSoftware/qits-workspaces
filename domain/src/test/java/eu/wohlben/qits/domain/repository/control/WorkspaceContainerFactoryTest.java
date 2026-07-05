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
    f.claudeVolume = "qits_shared_dot_claude";
    f.claudeMount = "/claude-home";
    return f;
  }

  @Test
  void alwaysSeedsTheCredentialVolumeLabelsHostUserImageAndCommand() {
    List<String> argv =
        factory()
            .forWorktree("repo12345678abc", "work", "main", "0parent", List.of(8080, 5173))
            .toRunArgv();

    // The guarantee: the shared credential volume is mounted on every container.
    assertSequence(argv, "-v", "qits_shared_dot_claude:/claude-home");
    // The qits.* reconciliation labels.
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.worktree=work");
    assertSequence(argv, "--label", "qits.branch=main");
    assertSequence(argv, "--label", "qits.parent=0parent");
    // Host alias, host uid, deterministic name, image, entrypoint.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertTrue(argv.contains("--user"), argv.toString());
    assertSequence(argv, "--name", "qits-wt-work-repo1234");
    assertTrue(argv.contains("qits/workspace:latest"), argv.toString());
    assertSequence(argv, "sleep", "infinity");
    // Declared daemon ports, published to localhost.
    assertTrue(argv.contains("127.0.0.1:0:8080"), argv.toString());
    assertTrue(argv.contains("127.0.0.1:0:5173"), argv.toString());
  }

  @Test
  void blankCredentialVolumeOmitsOnlyTheMount() {
    WorkspaceContainerFactory f = factory();
    f.claudeVolume = "";

    List<String> argv =
        f.forWorktree("repo12345678abc", "work", "main", null, List.of()).toRunArgv();

    assertFalse(argv.contains("-v"), argv.toString());
    // Everything else still present, incl. an empty parent label for the null parent.
    assertTrue(argv.contains("--add-host=host.docker.internal:host-gateway"), argv.toString());
    assertSequence(argv, "--label", "qits.repository=repo12345678abc");
    assertSequence(argv, "--label", "qits.parent=");
    assertSequence(argv, "--name", "qits-wt-work-repo1234");
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
