package eu.wohlben.qits.workspaces.api;

import java.util.Base64;

/**
 * Real image bytes for the attachment tests. Real, not plausible: the service sniffs the magic bytes
 * and stores what it finds rather than what the request claims, so a fixture of made-up bytes would
 * exercise the rejection path and never the acceptance one.
 */
final class PromptAttachmentFixtures {

  /** A 1×1 transparent PNG — the smallest thing that starts with the PNG signature and is one. */
  static final String ONE_PIXEL_PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5Erk"
          + "Jggg==";

  /** A 1×1 JPEG, so the sniffer's second signature is covered by something and not only by PNG. */
  static final String ONE_PIXEL_JPEG =
      "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIx"
          + "wcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAA"
          + "AAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==";

  /** Bytes that are neither: a GIF, which the sniffer must refuse whatever the request claims. */
  static final String ONE_PIXEL_GIF =
      Base64.getEncoder()
          .encodeToString(
              new byte[] {
                'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, ',', 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 'D', 1, 0, ';'
              });

  private PromptAttachmentFixtures() {}
}
