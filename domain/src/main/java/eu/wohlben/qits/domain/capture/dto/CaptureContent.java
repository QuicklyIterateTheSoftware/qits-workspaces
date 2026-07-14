package eu.wohlben.qits.domain.capture.dto;

/**
 * The content of an SPA capture snapshot, handed from the {@code service} boundary (which parses
 * the wire JSON) into the domain. Framework-free: no Jackson types cross this seam — arbitrary app
 * state arrives pre-serialized as {@code stateJson}. Every field is nullable; the goal renderer is
 * tolerant of sparse payloads.
 *
 * @param capturedAt the client's own ISO-8601 capture stamp, rendered verbatim as provenance
 * @param sourceWorkspaceId the workspace the capturing app ran in; null for an app deployed outside
 *     qits that only knows its repository
 * @param stateJson the app's registered state, pretty-printed JSON; null when the payload carries
 *     no state
 */
public record CaptureContent(
    String capturedAt,
    String sourceWorkspaceId,
    Page page,
    Environment environment,
    Dom dom,
    String stateJson) {

  public record Page(String url, String appPath, String routePattern, String title) {}

  public record Environment(
      Integer viewportWidth,
      Integer viewportHeight,
      Double devicePixelRatio,
      String userAgent,
      String prefersColorScheme) {}

  /**
   * @param clientTruncated whether the capture side already truncated the DOM at its own cap
   * @param bytes the DOM's size as measured by the client (pre-truncation provenance)
   */
  public record Dom(String html, boolean clientTruncated, long bytes) {}
}
