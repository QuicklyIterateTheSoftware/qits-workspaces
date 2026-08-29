package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The framework-free {@link WorkspaceContainer} builder — what a setter decides, and what "not set"
 * means.
 *
 * <p>This suite used to assert a {@code docker run} argv: the flag order, the {@code -d --init}
 * prefix, {@code -e K=V} concatenation, the image being the last token. None of those spellings
 * exists any more (see the class javadoc), so what is left is the part that was never about docker —
 * every setter's value comes back through its reader, insertion order survives, and a blank cap is
 * reported as blank so the one adapter that reads this can leave it off the wire spec.
 */
class WorkspaceContainerTest {

  @Test
  void everySetterReadsBackThroughItsReader() {
    // Deliberately set out of order: the setters accumulate, they do not sequence anything.
    WorkspaceContainer container =
        new WorkspaceContainer()
            .image("img:latest")
            .cpus("2")
            .pidsLimit("2048")
            .memory("4g")
            .memorySwap("8g")
            .network("qits-net")
            .volume("vol", "/mnt")
            .addHost("host.docker.internal:host-gateway")
            .label("qits.repository", "r")
            .user("1000")
            .name("c");

    assertEquals("c", container.name());
    assertEquals("1000", container.user());
    assertEquals(Map.of("qits.repository", "r"), container.labels());
    assertEquals(List.of("host.docker.internal:host-gateway"), container.addHosts());
    assertEquals("qits-net", container.network());
    // The memory cap and the memory+swap total beside it — docker's --memory-swap includes the
    // cap, so this pair is 4G of RAM plus 4G of swap.
    assertEquals("4g", container.memory());
    assertEquals("8g", container.memorySwap());
    assertEquals("2048", container.pidsLimit());
    assertEquals("2", container.cpus());
    assertEquals(List.of(new WorkspaceContainer.Mount("vol", "/mnt")), container.volumes());
    assertEquals("img:latest", container.image());
  }

  @Test
  void omitsVolumeUserNetworkAndLimitsWhenNoneSet() {
    WorkspaceContainer container = new WorkspaceContainer().name("c").image("img");

    // Nothing set is absent rather than empty-but-present: the adapter leaves each of these off the
    // spec entirely, so a null here is the whole decision.
    assertNull(container.user());
    assertNull(container.network());
    assertNull(container.memory());
    assertNull(container.memorySwap());
    assertNull(container.pidsLimit());
    assertNull(container.cpus());
    assertEquals(List.of(), container.volumes());
    assertEquals(Map.of(), container.labels());
    assertEquals(Map.of(), container.env());
    assertEquals(List.of(), container.addHosts());
    assertEquals("c", container.name());
    assertEquals("img", container.image());
  }

  @Test
  void memoryReadsBackAsTheCapAndBlankCapsNothing() {
    assertEquals("4g", new WorkspaceContainer().image("img").memory("4g").memory());

    // A blank cap caps nothing. It is reported as-is rather than nulled here, because the config
    // that supplies it filters blanks itself and the adapter treats blank as absent.
    assertTrue(new WorkspaceContainer().image("img").memory(" ").memory().isBlank());
  }

  @Test
  void envReadsBackInInsertionOrder() {
    Map<String, String> env =
        new WorkspaceContainer().image("img").env("A", "1").env("B", "2").env("C", "3").env();

    assertEquals(List.of("A", "B", "C"), List.copyOf(env.keySet()));
    assertEquals(new LinkedHashMap<>(Map.of("A", "1", "B", "2", "C", "3")), env);
  }

  @Test
  void labelsReadBackInInsertionOrder() {
    Map<String, String> labels =
        new WorkspaceContainer()
            .label("qits.repository", "r")
            .label("qits.workspace", "w")
            .label("qits.branch", "main")
            .labels();

    assertEquals(
        List.of("qits.repository", "qits.workspace", "qits.branch"), List.copyOf(labels.keySet()));
    assertEquals("main", labels.get("qits.branch"));
  }
}
