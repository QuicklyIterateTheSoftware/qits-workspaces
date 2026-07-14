package eu.wohlben.qits.domain.capture.control;

import eu.wohlben.qits.domain.capture.dto.CaptureContent;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Renders a capture payload into the workspace goal (preamble) markdown: a header with the receive
 * instant, the where/environment provenance lines, the registered app state as fenced JSON, and the
 * style-frozen DOM in a collapsed {@code <details>} block. Both humans (the workspace page) and
 * agents ({@code PromptRefinementService} interpolates the preamble) consume the result, so the
 * document must stay well-formed markdown no matter what the payload contains — fences are sized
 * strictly longer than any backtick run in their body, and the DOM is byte-capped <em>before</em>
 * the closing fence is appended so truncation can never split it.
 */
@ApplicationScoped
public class CaptureGoalRenderer {

  /**
   * Cap on the DOM embedded in the goal, in UTF-8 bytes. The preamble is a {@code @Lob} edited in a
   * textarea and echoed into agent prompts — a multi-MB DOM would poison both.
   */
  @ConfigProperty(name = "qits.capture.goal-dom-max-bytes", defaultValue = "262144")
  int goalDomMaxBytes = 262_144;

  static final DateTimeFormatter HEADER_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

  public String render(CaptureContent content, Instant receivedAt) {
    StringBuilder goal = new StringBuilder();
    goal.append("# Captured from the running app — ")
        .append(HEADER_TIMESTAMP.format(receivedAt))
        .append("\n\n");

    whereLine(content.page()).ifPresent(line -> goal.append(line).append("\n"));
    goal.append(provenanceLine(content)).append("\n");

    if (content.stateJson() != null) {
      goal.append("\n## App state at capture\n\n")
          .append(fencedBlock("json", content.stateJson()))
          .append("\n");
    }

    if (content.selection() != null && content.selection().html() != null) {
      CaptureContent.Selection sel = content.selection();
      goal.append("\n## Selected component (style-frozen)\n\n")
          .append(selectionProvenance(sel))
          .append("\n\n")
          .append(frozenSection(sel.html(), sel.clientTruncated(), sel.bytes(), "selection"));
    }

    goal.append("\n## Rendered DOM (style-frozen)\n\n").append(domSection(content.dom()));
    return goal.toString();
  }

  private static String selectionProvenance(CaptureContent.Selection sel) {
    StringBuilder line = new StringBuilder("**Picked**: ");
    line.append(sel.tag() == null ? "—" : codeSpan(sel.tag()));
    if (sel.component() != null) {
      line.append(" in ").append(codeSpan(sel.component()));
    }
    if (sel.selector() != null) {
      line.append(" — ").append(codeSpan(sel.selector()));
    }
    return line.toString();
  }

  private static java.util.Optional<String> whereLine(CaptureContent.Page page) {
    if (page == null) {
      return java.util.Optional.empty();
    }
    List<String> parts = new ArrayList<>();
    if (page.appPath() != null) {
      parts.add(codeSpan(page.appPath()));
    }
    if (page.routePattern() != null) {
      parts.add("(route " + codeSpan(page.routePattern()) + ")");
    }
    StringBuilder line = new StringBuilder("**Where**: ");
    line.append(parts.isEmpty() ? "—" : String.join(" ", parts));
    if (page.url() != null) {
      line.append(" — ").append(page.url());
    }
    return java.util.Optional.of(line.toString());
  }

  private static String provenanceLine(CaptureContent content) {
    List<String> segments = new ArrayList<>();
    segments.add(
        "**Source workspace**: "
            + (content.sourceWorkspaceId() == null ? "—" : codeSpan(content.sourceWorkspaceId())));
    viewportSegment(content.environment()).ifPresent(segments::add);
    if (content.capturedAt() != null) {
      segments.add("**Captured**: " + content.capturedAt());
    }
    return String.join(" · ", segments);
  }

  private static java.util.Optional<String> viewportSegment(CaptureContent.Environment env) {
    if (env == null || env.viewportWidth() == null || env.viewportHeight() == null) {
      return java.util.Optional.empty();
    }
    StringBuilder s =
        new StringBuilder("**Viewport**: ")
            .append(env.viewportWidth())
            .append("×")
            .append(env.viewportHeight());
    if (env.devicePixelRatio() != null) {
      s.append(" @").append(compactNumber(env.devicePixelRatio())).append("x");
    }
    if (env.prefersColorScheme() != null) {
      s.append(", ").append(env.prefersColorScheme());
    }
    return java.util.Optional.of(s.toString());
  }

  private String domSection(CaptureContent.Dom dom) {
    if (dom == null || dom.html() == null) {
      return "*No DOM captured.*\n";
    }
    return frozenSection(dom.html(), dom.clientTruncated(), dom.bytes(), "DOM");
  }

  /**
   * A style-frozen HTML fragment in a collapsed {@code <details>} block, byte-capped
   * <em>before</em> the closing fence is appended so truncation can never split it. Shared by the
   * whole-page DOM and the picked component; {@code noun} names which in the truncation marker.
   */
  private String frozenSection(
      String html, boolean clientTruncated, long originalBytes, String noun) {
    String embedded = utf8Truncate(html, goalDomMaxBytes);
    boolean truncatedHere = embedded.length() < html.length();
    int shownBytes = embedded.getBytes(StandardCharsets.UTF_8).length;

    StringBuilder summary = new StringBuilder("~").append(kiB(shownBytes)).append(" kB");
    if (truncatedHere) {
      summary.append(", truncated at ").append(kiB(goalDomMaxBytes)).append(" kB");
    }
    if (clientTruncated) {
      summary.append(" (already truncated client-side)");
    }

    StringBuilder section = new StringBuilder();
    section
        .append("<details><summary>")
        .append(summary)
        .append("</summary>\n\n")
        .append(fencedBlock("html", embedded))
        .append("\n");
    if (truncatedHere) {
      section
          .append("\n*Truncated at ")
          .append(goalDomMaxBytes)
          .append(" bytes (")
          .append(noun)
          .append(" was ")
          .append(originalBytes)
          .append(" bytes; config `qits.capture.goal-dom-max-bytes`).*\n");
    }
    section.append("</details>\n");
    return section.toString();
  }

  /**
   * Fences {@code body} with strictly more backticks than its longest backtick run (CommonMark
   * closes a fence only on a run at least as long as the opener), so the body can never close the
   * block early.
   */
  static String fencedBlock(String infoString, String body) {
    String fence = "`".repeat(Math.max(3, longestBacktickRun(body) + 1));
    return fence + infoString + "\n" + body + "\n" + fence + "\n";
  }

  static int longestBacktickRun(String s) {
    int longest = 0;
    int run = 0;
    for (int i = 0; i < s.length(); i++) {
      run = s.charAt(i) == '`' ? run + 1 : 0;
      longest = Math.max(longest, run);
    }
    return longest;
  }

  /**
   * Truncates to at most {@code maxBytes} UTF-8 bytes without splitting a code point: walks code
   * points accumulating their encoded length and stops before the first one that would overflow.
   */
  static String utf8Truncate(String s, int maxBytes) {
    int bytes = 0;
    int i = 0;
    while (i < s.length()) {
      int cp = s.codePointAt(i);
      int cpBytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (bytes + cpBytes > maxBytes) {
        return s.substring(0, i);
      }
      bytes += cpBytes;
      i += Character.charCount(cp);
    }
    return s;
  }

  /** Inline code span; falls back to the bare value when it contains a backtick itself. */
  static String codeSpan(String s) {
    return s.contains("`") ? s : "`" + s + "`";
  }

  private static String compactNumber(double d) {
    return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
  }

  private static long kiB(long bytes) {
    return Math.round(bytes / 1024.0);
  }
}
