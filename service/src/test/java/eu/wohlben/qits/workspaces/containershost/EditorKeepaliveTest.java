package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.FakeContainerRuntime;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The keepalive wired up: a report about a workspace becomes one {@code touch} at the container the
 * workspace's row names, and repeated reports inside the window become no further ones.
 *
 * <p>It runs behind a profile because <b>the shipped configuration is off</b> — {@code
 * qits.editor.idle-stop-after} is blank, nothing is idle-stopped, and a keepalive would be a request
 * nothing acts on. Turning it on for this class is what makes the wiring visible at all, and the
 * profile is also the assertion that the switch is what gates it.
 */
@QuarkusTest
@TestProfile(EditorKeepaliveTest.IdleStopOn.class)
public class EditorKeepaliveTest {

  /** A deployment that idle-stops editors, with a touch window long enough to debounce inside. */
  public static class IdleStopOn implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.editor.idle-stop-after", "PT30M", "qits.editor.touch-interval", "PT30S");
    }
  }

  @Inject EditorKeepalive keepalive;
  @Inject ContainerRuntime containers;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Test
  void aReportTouchesTheWorkspacesOwnContainerOncePerWindow() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", "keepalive-keepalive");
    workspaceService.createMainWorkspace(repoId, "master");
    Long rowId = workspaceIds.of(repoId, "master");

    FakeContainerRuntime runtime = (FakeContainerRuntime) containers;
    String container = runtime.containerName("master", repoId);
    runtime.clearTouches();

    keepalive.touched(rowId);
    assertTrue(keepalive.awaitQuiet(10_000));
    assertEquals(1, runtime.touchCount(container), "the row names the container, not the caller");

    // Everything inside the window is silence. An editor session is a stream of frames, so this is
    // the difference between one request per half-minute and one per keystroke.
    for (int i = 0; i < 50; i++) {
      keepalive.touched(rowId);
    }
    assertTrue(keepalive.awaitQuiet(10_000));
    assertEquals(1, runtime.touchCount(container), "one touch per interval, not one per report");
  }

  @Test
  void aWorkspaceThatIsNotThereIsNotTouched() throws Exception {
    // The row is what names the container, so a report about a workspace that has since resolved
    // reaches nothing — silently, because a keepalive is best-effort by contract.
    FakeContainerRuntime runtime = (FakeContainerRuntime) containers;
    runtime.clearTouches();

    keepalive.touched(-42L);
    assertTrue(keepalive.awaitQuiet(10_000));

    assertEquals(0, runtime.touchCount("anything"));
  }
}
