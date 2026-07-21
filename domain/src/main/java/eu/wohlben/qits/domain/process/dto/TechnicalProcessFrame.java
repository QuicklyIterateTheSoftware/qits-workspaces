package eu.wohlben.qits.domain.process.dto;

/**
 * One frame of a technical process's replayable SSE stream. Every frame names the {@code segment}
 * it belongs to (null for the terminal {@code done} and the {@code ping} heartbeat) and exactly one
 * payload: a {@code line} for {@code line} frames, a {@code status} ({@code ok}/{@code failed}) for
 * {@code segment-settled} and {@code done} frames. A failed {@code segment-settled} may carry an
 * optional {@code hint} classifying the failure for the UI (the vocabulary below) plus a {@code
 * hintTarget} — the identifier the UI acts on (e.g. the repository id to sign into, which for a
 * submodule child is <em>not</em> the root repo); everywhere else both are null. {@code seq} is a
 * per-subscription monotonic ordinal — a reconnect replays the buffered history with fresh
 * ordinals, so clients rebuild their view from scratch on every (re)connect rather than diffing.
 *
 * <p>Kinds and statuses are plain strings (not enums) because this record is the wire contract of a
 * raw {@code EventSource} consumer, not a generated-client model — the constants below are the
 * complete vocabulary, and a new nullable field is a backward-compatible addition.
 */
public record TechnicalProcessFrame(
    String segment,
    String kind,
    long seq,
    String line,
    String status,
    String hint,
    String hintTarget) {

  public static final String KIND_SEGMENT_OPEN = "segment-open";
  public static final String KIND_LINE = "line";
  public static final String KIND_SEGMENT_SETTLED = "segment-settled";
  public static final String KIND_DONE = "done";
  public static final String KIND_PING = "ping";

  public static final String STATUS_OK = "ok";
  public static final String STATUS_FAILED = "failed";

  /** The failed verb hit the remote's auth wall — the UI offers the sign-in terminal. */
  public static final String HINT_REMOTE_AUTH = "remote-auth";

  public static TechnicalProcessFrame segmentOpen(String segment, long seq) {
    return new TechnicalProcessFrame(segment, KIND_SEGMENT_OPEN, seq, null, null, null, null);
  }

  public static TechnicalProcessFrame line(String segment, long seq, String line) {
    return new TechnicalProcessFrame(segment, KIND_LINE, seq, line, null, null, null);
  }

  public static TechnicalProcessFrame segmentSettled(String segment, long seq, boolean ok) {
    return segmentSettled(segment, seq, ok, null, null);
  }

  public static TechnicalProcessFrame segmentSettled(
      String segment, long seq, boolean ok, String hint, String hintTarget) {
    return new TechnicalProcessFrame(
        segment, KIND_SEGMENT_SETTLED, seq, null, ok ? STATUS_OK : STATUS_FAILED, hint, hintTarget);
  }

  public static TechnicalProcessFrame done(long seq, boolean ok) {
    return new TechnicalProcessFrame(
        null, KIND_DONE, seq, null, ok ? STATUS_OK : STATUS_FAILED, null, null);
  }

  public static TechnicalProcessFrame ping(long seq) {
    return new TechnicalProcessFrame(null, KIND_PING, seq, null, null, null, null);
  }
}
