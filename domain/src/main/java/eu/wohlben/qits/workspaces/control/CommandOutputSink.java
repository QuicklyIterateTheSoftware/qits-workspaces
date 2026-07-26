package eu.wohlben.qits.workspaces.control;

/**
 * A destination for terminal output — whoever runs a process on this context's behalf fans every
 * chunk out to the sinks attached to it.
 *
 * <p>An <em>outbound</em> shape, not a service this context calls: the classes here that implement
 * it ({@code SegmentLineSink}, the {@code LineFramingSink} family, the websocket's connection sink)
 * are handed <em>to</em> the command context through {@link WorkspaceTerminalSessions}. It is
 * declared here rather than imported because the command context lives in another repository and
 * this jar must compile without it; the two declarations are the same two methods, and an
 * application assembling both adapts one to the other in a lambda.
 *
 * <p>Framework-free on purpose (no websockets.next type), so the sinks can live in {@code domain}.
 */
public interface CommandOutputSink {

  /**
   * Forward a chunk of already terminal-encoded output to the client (written verbatim to xterm).
   */
  void write(String data);

  /** Whether this sink can still receive output; the producer prunes sinks that report false. */
  boolean isOpen();
}
