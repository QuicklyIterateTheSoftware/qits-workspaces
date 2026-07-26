package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The test-side {@link WorkspaceProcessTracker}: hands out real ids and records the segment calls,
 * without any of the streaming machinery the real technical-process framework carries.
 *
 * <p>Its presence is what makes {@code ensure-container} and {@code recreate-container} return a
 * non-null {@code technicalProcessId}, which is what those endpoints' tests assert. It also lets a
 * test check the segment sequence ({@code docker-run} → {@code clone}, or {@code container-start})
 * without asserting on frame plumbing that belongs to another context.
 */
@ApplicationScoped
public class FakeWorkspaceProcessTracker implements WorkspaceProcessTracker {

  /** One recorded call against a handle. */
  public record Event(String processId, String kind, String segment, String detail) {}

  private final List<Event> events = new CopyOnWriteArrayList<>();
  private final Map<String, String> active = new ConcurrentHashMap<>();

  @Override
  public Handle begin(String repoId, String workspaceId) {
    String id = UUID.randomUUID().toString();
    active.put(key(repoId, workspaceId), id);
    events.add(new Event(id, "begin", null, key(repoId, workspaceId)));
    return new RecordingHandle(id, key(repoId, workspaceId));
  }

  @Override
  public Optional<String> activeFor(String repoId, String workspaceId) {
    return Optional.ofNullable(active.get(key(repoId, workspaceId)));
  }

  public List<Event> events() {
    return List.copyOf(events);
  }

  /** The segments opened for a process, in order. */
  public List<String> segmentsOf(String processId) {
    return events.stream()
        .filter(e -> processId.equals(e.processId()) && "openSegment".equals(e.kind()))
        .map(Event::segment)
        .toList();
  }

  public void clear() {
    events.clear();
    active.clear();
  }

  private static String key(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }

  private final class RecordingHandle implements Handle {

    private final String id;
    private final String key;

    private RecordingHandle(String id, String key) {
      this.id = id;
      this.key = key;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void openSegment(String name) {
      events.add(new Event(id, "openSegment", name, null));
    }

    @Override
    public void appendLine(String segmentName, String line) {
      events.add(new Event(id, "appendLine", segmentName, line));
    }

    @Override
    public void settleSegment(String segmentName, boolean ok) {
      events.add(new Event(id, "settleSegment", segmentName, String.valueOf(ok)));
    }

    @Override
    public void completeNoOp(String segmentName, String note) {
      events.add(new Event(id, "completeNoOp", segmentName, note));
      active.remove(key, id);
    }

    @Override
    public void finishProvision(boolean ok) {
      events.add(new Event(id, "finishProvision", null, String.valueOf(ok)));
      active.remove(key, id);
    }

    @Override
    public void failProvision(String message) {
      events.add(new Event(id, "failProvision", null, message));
      active.remove(key, id);
    }
  }
}
