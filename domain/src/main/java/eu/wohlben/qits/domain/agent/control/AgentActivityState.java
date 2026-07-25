package eu.wohlben.qits.domain.agent.control;

/**
 * A coding agent's coarse, live lifecycle state, derived from its {@code claude}/{@code kimi}
 * lifecycle hooks (docs/epics/qits-coding-agents/ agent-activity tracking). The in-container {@code
 * workspace-daemon} maps each hook event to one of these and relays it over its dial-home socket;
 * the host caches it and surfaces it as the Agents-tab "cooking / idle / waiting" chip.
 *
 * <p>The names mirror {@code DaemonProtocol.AgentState}'s wire-string constants exactly — the
 * protocol carries a plain String (keeping the framework-free protocol module free of this {@code
 * domain} enum), and the host parses it back with a tolerant {@code valueOf}.
 */
public enum AgentActivityState {
  /** Session established, or a turn finished and control yielded back to the user. */
  IDLE,
  /** A prompt was submitted — the agent is generating a response. */
  BUSY,
  /** The agent is blocked on the user (permission prompt / idle input). */
  WAITING,
  /** The session is over. */
  ENDED
}
