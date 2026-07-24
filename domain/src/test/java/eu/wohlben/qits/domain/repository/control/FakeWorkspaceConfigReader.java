package eu.wohlben.qits.domain.repository.control;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceConfigReader}: the config-sourced definitions (services, actions,
 * bootstrap steps) the Part-5 single-source-of-truth runtime reads. Tests stage a workspace's
 * config with {@link #setConfig} instead of creating DB rows (the DB config store is gone), and the
 * supervised surfaces (supervisor, coupler, bootstrap runner) resolve from it. An unset workspace
 * reads empty — the no-live-daemon case.
 *
 * <p>An enabled alternative so it wins the {@code Instance<WorkspaceConfigReader>} injection even
 * where the backend registry is present (service module tests). Keep the {@code domain}/{@code
 * service} copies in sync.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeWorkspaceConfigReader implements WorkspaceConfigReader {

  private final Map<String, WorkspaceConfigView> views = new ConcurrentHashMap<>();

  @Override
  public Optional<WorkspaceConfigView> readConfig(String workspaceId) {
    return Optional.ofNullable(views.get(workspaceId));
  }

  /** Stage {@code config} as {@code workspaceId}'s in-container config (warning-free). */
  public void setConfig(String workspaceId, QitsConfig config) {
    views.put(workspaceId, new WorkspaceConfigView(config, null));
  }

  /** Forget every staged config (call between tests sharing the bean). */
  public void clear() {
    views.clear();
  }
}
