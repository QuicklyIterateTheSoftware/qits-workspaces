package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.VersionBumpException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The one technique both bumpers share: locate a value's <b>character span</b> with a streaming
 * parser, then replace exactly those characters in the original text.
 *
 * <p><b>Never a DOM round-trip.</b> Serializing a parsed pom reformats the whole file and turns a
 * one-line version bump into an unreviewable diff; rewriting a {@code package-lock.json} through a
 * tree would reorder and reflow thousands of lines, and the committed {@code resolved} URLs that the
 * pipelines rewrite between {@code localhost:8081} and the qits-net origin are exactly the kind of
 * content that must survive untouched. A splice preserves formatting, comments and key order
 * absolutely, because it only ever touches the bytes between two offsets.
 *
 * <p>This class is deliberately format-blind. What it guarantees is arithmetic: the spans are
 * disjoint, in bounds, and applied right-to-left so earlier offsets stay valid.
 */
public final class TextSplice {

  private TextSplice() {}

  /**
   * A half-open character range {@code [start, end)} into some original text.
   *
   * @param start index of the first character replaced, inclusive
   * @param end index one past the last character replaced
   */
  public record Span(int start, int end) {

    public Span {
      if (start < 0 || end < start) {
        throw new VersionBumpException("nonsensical span [" + start + "," + end + ")");
      }
    }

    public int length() {
      return end - start;
    }
  }

  /**
   * Replace every span with the same text, leaving every other character of {@code original}
   * byte-identical.
   *
   * <p>Spans may arrive in any order; overlapping or out-of-bounds spans are a programming error in
   * a locator and fail loudly rather than producing a plausible-looking corrupt file.
   */
  public static String replaceAll(String original, List<Span> spans, String replacement) {
    if (spans.isEmpty()) {
      return original;
    }
    List<Span> ordered = new ArrayList<>(spans);
    ordered.sort(Comparator.comparingInt(Span::start));

    int previousEnd = -1;
    for (Span span : ordered) {
      if (span.end() > original.length()) {
        throw new VersionBumpException(
            "span [" + span.start() + "," + span.end() + ") runs past the end of a "
                + original.length()
                + "-character document");
      }
      if (span.start() < previousEnd) {
        throw new VersionBumpException(
            "overlapping spans: [" + span.start() + "," + span.end() + ") starts inside a span"
                + " ending at " + previousEnd);
      }
      previousEnd = span.end();
    }

    StringBuilder out = new StringBuilder(original.length());
    int cursor = 0;
    for (Span span : ordered) {
      out.append(original, cursor, span.start()).append(replacement);
      cursor = span.end();
    }
    out.append(original, cursor, original.length());
    return out.toString();
  }
}
