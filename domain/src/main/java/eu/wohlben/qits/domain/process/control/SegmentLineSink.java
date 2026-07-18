package eu.wohlben.qits.domain.process.control;

import eu.wohlben.qits.domain.command.control.CommandOutputSink;

/**
 * Adapts a command's raw chunked output (the daemon follower pipeline fans chunks out to {@link
 * CommandOutputSink}s) into per-line appends on one {@link TechnicalProcess} segment. Frames on
 * {@code '\n'} with a trailing {@code '\r'} stripped, like {@code CommandSession}'s audit-log
 * framer. Reports closed once its segment settled (or the process ended), so the fan-out prunes it
 * and a chatty daemon stops feeding an already-decided segment.
 */
public final class SegmentLineSink implements CommandOutputSink {

  private final TechnicalProcess process;
  private final String segment;
  private final StringBuilder pending = new StringBuilder();

  public SegmentLineSink(TechnicalProcess process, String segment) {
    this.process = process;
    this.segment = segment;
  }

  @Override
  public synchronized void write(String data) {
    for (int i = 0; i < data.length(); i++) {
      char ch = data.charAt(i);
      if (ch == '\n') {
        String line = pending.toString();
        if (line.endsWith("\r")) {
          line = line.substring(0, line.length() - 1);
        }
        process.appendLine(segment, line);
        pending.setLength(0);
      } else {
        pending.append(ch);
      }
    }
  }

  @Override
  public boolean isOpen() {
    return !process.isTerminal() && !process.isSegmentSettled(segment);
  }
}
