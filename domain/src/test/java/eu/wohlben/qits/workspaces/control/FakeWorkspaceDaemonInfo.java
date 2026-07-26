package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceDaemonInfo}: a workspace has no live daemon (so {@link #lookup}
 * is {@link Optional#empty()} and {@link #all} is empty) until a test {@linkplain #report reports}
 * one, so by default every {@code @QuarkusTest} sees no registry facts and {@code WorkspaceDto}'s
 * daemon fields (including {@code daemonOutdated}) stay null. Mirrors {@link
 * FakeWorkspaceGitStatus}; drives the "latest agent version"/outdated computation in {@link
 * WorkspaceService#listWorkspaces}.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceDaemonInfo implements WorkspaceDaemonInfo {

  private final ConcurrentHashMap<String, Info> infos = new ConcurrentHashMap<>();

  /**
   * Announce a live daemon for {@code workspaceId} with the given build identity. A null {@code
   * buildTime} mimics an older image that reported none — never orderable, so never "the latest".
   */
  public void report(String workspaceId, String version, Instant buildTime) {
    infos.put(workspaceId, new Info(Instant.EPOCH, version, buildTime));
  }

  public void forget(String workspaceId) {
    infos.remove(workspaceId);
  }

  @Override
  public Optional<Info> lookup(String workspaceId) {
    return Optional.ofNullable(infos.get(workspaceId));
  }

  @Override
  public Collection<Info> all() {
    return List.copyOf(infos.values());
  }
}
