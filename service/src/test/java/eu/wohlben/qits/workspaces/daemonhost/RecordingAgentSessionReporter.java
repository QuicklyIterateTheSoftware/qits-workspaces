package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.control.AgentSessionReporter;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every {@link AgentSessionReporter} call, so tests can assert that the registry routes a
 * {@code SessionStart} frame to the port.
 *
 * <p>The monorepo asserted this by reading back a persisted lineage row through {@code
 * CommandService}. That row belongs to the commands context; what this context is actually
 * responsible for is calling the port with the frame's three fields, which is what this records.
 */
@ApplicationScoped
public class RecordingAgentSessionReporter implements AgentSessionReporter {

  /** One recorded call. */
  public record Report(String commandId, String sessionId, String transcriptPath) {}

  private final List<Report> reports = new CopyOnWriteArrayList<>();

  @Override
  public void reportAgentSession(String commandId, String sessionId, String transcriptPath) {
    reports.add(new Report(commandId, sessionId, transcriptPath));
  }

  public List<Report> reports() {
    return List.copyOf(reports);
  }

  public boolean recorded(String commandId, String sessionId) {
    return reports.stream()
        .anyMatch(r -> commandId.equals(r.commandId()) && sessionId.equals(r.sessionId()));
  }

  public void clear() {
    reports.clear();
  }
}
