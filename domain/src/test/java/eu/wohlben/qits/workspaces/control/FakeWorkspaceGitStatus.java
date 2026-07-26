package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceGitStatus}: a workspace's clean/dirty is unknown ({@link
 * Optional#empty()}) until a test {@linkplain #report reports} it, so by default every
 * {@code @QuarkusTest} sees no daemon-reported status and {@code WorkspaceDto.clean} is null.
 * Mirrors the always-on {@link FakeWorkspaceDaemonLiveness}. Keep the {@code domain}/{@code
 * service} copies in sync if one is added on the service side.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceGitStatus implements WorkspaceGitStatus {

  private final ConcurrentHashMap<String, Boolean> clean = new ConcurrentHashMap<>();

  public void report(String workspaceId, boolean isClean) {
    clean.put(workspaceId, isClean);
  }

  public void forget(String workspaceId) {
    clean.remove(workspaceId);
  }

  @Override
  public Optional<Boolean> isClean(String workspaceId) {
    return Optional.ofNullable(clean.get(workspaceId));
  }
}
