package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test helper: records every {@link WorkspaceReadyForServices} the async event bus delivers, so
 * tests can assert whether (and when) the bootstrap runner released service auto-start.
 */
@ApplicationScoped
public class WorkspaceReadyForServicesRecorder {

  private final List<WorkspaceReadyForServices> events = new CopyOnWriteArrayList<>();

  void onReady(@ObservesAsync WorkspaceReadyForServices evt) {
    events.add(evt);
  }

  public List<WorkspaceReadyForServices> events() {
    return List.copyOf(events);
  }

  public long countFor(String repoId, String workspaceId) {
    return events.stream()
        .filter(e -> e.repoId().equals(repoId) && e.workspaceId().equals(workspaceId))
        .count();
  }

  public void clear() {
    events.clear();
  }
}
