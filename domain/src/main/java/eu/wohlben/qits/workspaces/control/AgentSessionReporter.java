package eu.wohlben.qits.workspaces.control;

/**
 * Records the coding-agent session a command started, for the session lineage.
 *
 * <p>The workspace registry learns of a new session incidentally: the daemon relays the agent's
 * {@code SessionStart} hook over the control socket, and this context is the first to see it. The
 * lineage itself belongs to the commands context, so the fact is handed over through this port
 * rather than written here.
 *
 * <p>Injected as {@code Instance<AgentSessionReporter>}. Absent means the report is dropped — the
 * registry's own agent-activity rollup, which drives the workspace's activity badge, is a separate
 * sink and is unaffected.
 */
public interface AgentSessionReporter {

  /**
   * Report that {@code commandId} started agent session {@code sessionId}. Implementations must
   * tolerate duplicates: the daemon re-reports activity on every reconnect.
   */
  void reportAgentSession(String commandId, String sessionId, String transcriptPath);
}
