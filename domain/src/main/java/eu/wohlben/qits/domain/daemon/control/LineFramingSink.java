package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;
import java.util.regex.Pattern;

/**
 * Base for daemon observers: adapts the registry's raw-chunk {@link CommandOutputSink} fan-out to
 * per-line consumption. PTY output is byte-framed and full of ANSI escapes, so chunks are
 * reassembled into lines and stripped of escape sequences before {@link #onLine}. Always open —
 * observers live exactly as long as the session that fans out to them.
 */
abstract class LineFramingSink implements CommandOutputSink {

  private static final Pattern ANSI_ESCAPES = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

  /** A runaway no-newline line is force-framed at this size so the buffer stays bounded. */
  private static final int MAX_LINE_CHARS = 16 * 1024;

  private final StringBuilder partial = new StringBuilder();

  @Override
  public synchronized void write(String data) {
    for (int i = 0; i < data.length(); i++) {
      char ch = data.charAt(i);
      if (ch == '\n') {
        completeLine();
      } else {
        partial.append(ch);
        if (partial.length() >= MAX_LINE_CHARS) {
          completeLine();
        }
      }
    }
  }

  @Override
  public boolean isOpen() {
    return true;
  }

  private void completeLine() {
    String line = partial.toString();
    partial.setLength(0);
    if (line.endsWith("\r")) {
      line = line.substring(0, line.length() - 1);
    }
    line = ANSI_ESCAPES.matcher(line).replaceAll("");
    if (!line.isBlank()) {
      onLine(line);
    }
  }

  /** One clean output line (non-blank, ANSI-stripped). Called under the sink's own monitor. */
  protected abstract void onLine(String line);
}
