package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceGitStatus}: a workspace's clean/dirty and head are unknown
 * ({@link Optional#empty()}) until a test {@linkplain #report reports} them, so by default every
 * {@code @QuarkusTest} sees no daemon-reported status and {@code WorkspaceDto.clean} is null.
 * Mirrors the always-on {@link FakeWorkspaceDaemonLiveness}. Keep the {@code domain}/{@code
 * service} copies in sync if one is added on the service side.
 *
 * <p><b>Unknown is refusal, not permission.</b> Since the in-container git moved to the daemon, the
 * host has no way to check cleanliness itself, so {@code WorkspaceService} treats an unreported
 * workspace as dirty and blocks anything that could discard work. A test that needs a destructive
 * operation to <em>succeed</em> must therefore {@code report(workspaceId, true)} first — the same
 * thing a live daemon does on connect. Forgetting to is indistinguishable from a workspace whose
 * daemon never dialled home, and fails the same way.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceGitStatus implements WorkspaceGitStatus {

  private final ConcurrentHashMap<Long, Boolean> clean = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, String> head = new ConcurrentHashMap<>();

  public void report(Long workspaceId, boolean isClean) {
    clean.put(workspaceId, isClean);
  }

  /** Report both halves of a {@code GitStatus} frame, as a live daemon sends them together. */
  public void report(Long workspaceId, boolean isClean, String headSha) {
    clean.put(workspaceId, isClean);
    head.put(workspaceId, headSha);
  }

  public void reportHead(Long workspaceId, String headSha) {
    head.put(workspaceId, headSha);
  }

  public void forget(Long workspaceId) {
    clean.remove(workspaceId);
    head.remove(workspaceId);
  }

  @Override
  public Optional<Boolean> isClean(Long workspaceId) {
    return Optional.ofNullable(clean.get(workspaceId));
  }

  @Override
  public Optional<String> head(Long workspaceId) {
    return Optional.ofNullable(head.get(workspaceId));
  }
}
