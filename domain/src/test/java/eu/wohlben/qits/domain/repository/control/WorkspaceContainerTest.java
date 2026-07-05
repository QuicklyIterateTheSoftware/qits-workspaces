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
            .publishPort(8080)
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
            "-v",
            "vol:/mnt",
            "-p",
            "127.0.0.1:0:8080",
            "img:latest",
            "sleep",
            "infinity"),
        argv);
  }

  @Test
  void omitsVolumeUserAndPortsWhenNoneSet() {
    List<String> argv =
        new WorkspaceContainer().name("c").image("img").command("sleep", "infinity").toRunArgv();

    assertFalse(argv.contains("-v"), argv.toString());
    assertFalse(argv.contains("-p"), argv.toString());
    assertFalse(argv.contains("--user"), argv.toString());
    assertEquals(List.of("-d", "--init", "--name", "c", "img", "sleep", "infinity"), argv);
  }

  @Test
  void publishPortsAddsOneEntryPerPort() {
    List<String> argv =
        new WorkspaceContainer().image("img").publishPorts(List.of(8080, 5173)).toRunArgv();

    assertEquals(1, count(argv, "127.0.0.1:0:8080"));
    assertEquals(1, count(argv, "127.0.0.1:0:5173"));
    assertEquals(2, count(argv, "-p"));
  }

  private static long count(List<String> argv, String value) {
    return argv.stream().filter(value::equals).count();
  }
}
