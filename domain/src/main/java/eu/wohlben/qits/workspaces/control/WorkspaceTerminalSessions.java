package eu.wohlben.qits.workspaces.control;

import java.util.Map;

/**
 * An ephemeral interactive PTY inside a workspace container, owned by the caller that opened it.
 *
 * <p>The one thing this context needs of the command context, narrowed to it: the service terminal
 * ({@code ServiceTerminalSocket}) attaches a browser xterm to a running dev server's tmux session,
 * and needs a process with a real TTY plus input and resize. It deliberately does <em>not</em> want
 * the command context's durable side — no {@code command} row, no persisted log, no exit
 * bookkeeping — which is why the monorepo's call passed no-op exit and log listeners; those two
 * parameters are absent here rather than being no-ops the implementer must ignore.
 *
 * <p><strong>Optional</strong> ({@code Instance<T>}), like the workspace-daemon SPIs: an
 * application that ships no command context still runs workspaces and services perfectly well — the
 * live log follower, which is where a service's output actually comes from, does not go through
 * here. Absent means the service terminal socket refuses the upgrade with a message rather than
 * opening a dead terminal.
 */
public interface WorkspaceTerminalSessions {

  /**
   * Start {@code script} in {@code container} under a PTY, streaming its output to {@code sink}
   * until {@link #close}. {@code sessionId} is the caller's handle for the other three methods.
   */
  void open(
      String sessionId,
      String container,
      String script,
      Map<String, String> environment,
      CommandOutputSink sink);

  /** Write keystrokes to the session's PTY; false when it is not (or no longer) running. */
  boolean input(String sessionId, byte[] data);

  /** Resize the session's PTY; false when it is not (or no longer) running. */
  boolean resize(String sessionId, int cols, int rows);

  /** Kill the session's process group. Detaching a tmux client leaves the session running. */
  boolean close(String sessionId);

  /** Whether the session is still alive. */
  boolean isRunning(String sessionId);
}
