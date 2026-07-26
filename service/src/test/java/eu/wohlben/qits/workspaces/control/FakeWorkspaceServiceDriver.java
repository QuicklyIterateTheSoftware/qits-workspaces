package eu.wohlben.qits.workspaces.control;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link WorkspaceServiceDriver}: stands in for the in-container workspace-daemon's
 * service (dev-server) supervision (docs/epics/qits-workspace-daemon/ Part 4). It records the
 * host's {@code startService}/{@code signalService} calls and captures the {@link ServiceEventSink}
 * the host {@code ServiceSupervisor} subscribes at startup, so a test can <b>play the daemon</b> —
 * feed lifecycle events/output through the sink and assert the host projects them (status, segment,
 * proxy). Unlike the {@code domain} copy (a global {@code @Mock}), this is a profile-scoped {@link
 * Alternative}: the real driver here is the backend {@code WorkspaceDaemonRegistry}, which the
 * other service tests and the daemon ITs still need, so a test opts in with {@code
 * getEnabledAlternatives()} rather than replacing the registry globally. Keep the {@code
 * domain}/{@code service} copies in sync.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeWorkspaceServiceDriver implements WorkspaceServiceDriver {

  // Accessed via methods, not the fields directly: this is an @ApplicationScoped bean, so a test
  // injects a client proxy whose field reads would not see the real instance's state.
  private final List<String> started = new CopyOnWriteArrayList<>();
  private final List<String> signalled = new CopyOnWriteArrayList<>();
  private volatile ServiceEventSink sink;

  /** Service names the host asked the daemon to start (manual starts). */
  public List<String> started() {
    return started;
  }

  /** Service names the host asked the daemon to signal (stops). */
  public List<String> signalled() {
    return signalled;
  }

  @Override
  public void startService(
      String workspaceId, String serviceName, String script, Map<String, String> env) {
    started.add(serviceName);
  }

  @Override
  public void signalService(String workspaceId, String serviceName, String signal) {
    signalled.add(serviceName);
  }

  @Override
  public void subscribe(ServiceEventSink sink) {
    this.sink = sink;
  }

  /** The host's projection sink — a test feeds it to play the daemon. */
  public ServiceEventSink sink() {
    return sink;
  }

  /** Clear recorded calls between tests (the bean is a shared singleton across a test class). */
  public void reset() {
    started.clear();
    signalled.clear();
  }
}
