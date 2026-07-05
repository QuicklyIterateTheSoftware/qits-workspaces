package eu.wohlben.qits.domain.daemon.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-workspace buffer of agent-bound daemon messages that had no running chat session to land in.
 * {@code AgentLaunchService} drains it when a chat starts, so events that happened while nobody was
 * listening still reach the next session as seed context. In-memory, bounded, best-effort — the
 * durable trail is the event feed and the daemon command logs.
 */
@ApplicationScoped
public class DaemonEventSpool {

  private static final int MAX_PER_WORKSPACE = 50;

  private final Map<String, Deque<String>> spooled = new ConcurrentHashMap<>();

  public void add(String repoId, String workspaceId, String message) {
    Deque<String> queue =
        spooled.computeIfAbsent(key(repoId, workspaceId), k -> new ArrayDeque<>());
    synchronized (queue) {
      queue.addLast(message);
      while (queue.size() > MAX_PER_WORKSPACE) {
        queue.removeFirst();
      }
    }
  }

  /** Removes and returns everything spooled for the workspace, oldest first. */
  public List<String> drain(String repoId, String workspaceId) {
    Deque<String> queue = spooled.remove(key(repoId, workspaceId));
    if (queue == null) {
      return List.of();
    }
    synchronized (queue) {
      return List.copyOf(queue);
    }
  }

  private static String key(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }
}
