package eu.wohlben.qits.domain.repository.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The framework-free {@link WorkspaceContainer} builder — argv order and omission behavior. */
class WorkspaceContainerTest {

  @Test
  void rendersInFixedOrderRegardlessOfCallOrder() {
    // Deliberately set out of order; the render order must still be fixed.
    List<String> argv =
        new WorkspaceContainer()
            .command("sleep", "infinity")
            .image("img:latest")
            .cpus("2")
            .pidsLimit("2048")
            .memory("4g")
            .network("qits-net")
            .volume("vol", "/mnt")
            .addHost("host.docker.internal:host-gateway")
            .label("qits.repository", "r")
            .user("1000")
            .name("c")
            .toRunArgv();

    assertEquals(
        List.of(
            "-d",
            "--init",
            "--name",
            "c",
            "--user",
            "1000",
            "--label",
            "qits.repository=r",
            "--add-host=host.docker.internal:host-gateway",
            "--network",
            "qits-net",
            "--memory",
            "4g",
            "--memory-swap",
            "4g",
            "--pids-limit",
            "2048",
            "--cpus",
            "2",
            "-v",
            "vol:/mnt",
            "img:latest",
            "sleep",
            "infinity"),
        argv);
  }

  @Test
  void omitsVolumeUserNetworkAndLimitsWhenNoneSet() {
    List<String> argv =
        new WorkspaceContainer().name("c").image("img").command("sleep", "infinity").toRunArgv();

    assertFalse(argv.contains("-v"), argv.toString());
    assertFalse(argv.contains("--network"), argv.toString());
    assertFalse(argv.contains("-p"), argv.toString());
    assertFalse(argv.contains("--user"), argv.toString());
    assertFalse(argv.contains("--memory"), argv.toString());
    assertFalse(argv.contains("--pids-limit"), argv.toString());
    assertFalse(argv.contains("--cpus"), argv.toString());
    assertEquals(List.of("-d", "--init", "--name", "c", "img", "sleep", "infinity"), argv);
  }

  @Test
  void memoryRendersAsHardCapWithEqualSwapAndBlankAddsNothing() {
    List<String> capped = new WorkspaceContainer().image("img").memory("4g").toRunArgv();
    // --memory-swap equals --memory: the container can't swap-thrash the host past the cap.
    assertEquals(List.of("-d", "--init", "--memory", "4g", "--memory-swap", "4g", "img"), capped);

    List<String> blank = new WorkspaceContainer().image("img").memory(" ").toRunArgv();
    assertEquals(List.of("-d", "--init", "img"), blank);
  }

  @Test
  void envRendersAsDockerEnvFlagsInInsertionOrder() {
    List<String> argv =
        new WorkspaceContainer().image("img").env("A", "1").env("B", "2").toRunArgv();

    // -e A=1 then -e B=2, before the image.
    assertEquals(List.of("-d", "--init", "-e", "A=1", "-e", "B=2", "img"), argv);
  }

  @Test
  void networkRendersAsSingleFlag() {
    List<String> argv = new WorkspaceContainer().image("img").network("qits-net").toRunArgv();

    assertEquals(List.of("-d", "--init", "--network", "qits-net", "img"), argv);
  }
}
