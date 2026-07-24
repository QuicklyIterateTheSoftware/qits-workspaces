package eu.wohlben.qits.domain.repository.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceDaemonLiveness}: a workspace is "daemon-live" only once a test
 * {@linkplain #markLive marks} it, so by default every {@code @QuarkusTest} sees no live daemon and
 * the host {@code ServiceSupervisor} keeps its tmux path (the degradation contract). A projection
 * test marks a workspace live to exercise the daemon-backed path. Keep the {@code domain}/{@code
 * service} copies in sync.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceDaemonLiveness implements WorkspaceDaemonLiveness {

  private final Set<String> live = ConcurrentHashMap.newKeySet();

  public void markLive(String workspaceId) {
    live.add(workspaceId);
  }

  public void markDead(String workspaceId) {
    live.remove(workspaceId);
  }

  @Override
  public boolean isDaemonLive(String workspaceId) {
    return live.contains(workspaceId);
  }
}
