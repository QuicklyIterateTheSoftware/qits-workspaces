package eu.wohlben.qits.domain.daemon.control;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Keeps the last lines of a daemon's output so a crash event can carry the evidence ("here's the
 * stacktrace") without a DB read on the exit path.
 */
final class TailSink extends LineFramingSink {

  private static final int MAX_LINES = 40;
  private static final int MAX_EXCERPT_CHARS = 2000;

  private final Deque<String> lines = new ArrayDeque<>();

  @Override
  protected synchronized void onLine(String line) {
    lines.addLast(line);
    while (lines.size() > MAX_LINES) {
      lines.removeFirst();
    }
  }

  /** The recent output, oldest first, capped; null when nothing was captured. */
  synchronized String excerpt() {
    if (lines.isEmpty()) {
      return null;
    }
    String joined = String.join("\n", lines);
    return joined.length() <= MAX_EXCERPT_CHARS
        ? joined
        : joined.substring(joined.length() - MAX_EXCERPT_CHARS);
  }
}
