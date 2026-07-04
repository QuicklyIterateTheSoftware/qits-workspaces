package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import java.util.Optional;

/**
 * Classifies a batch of daemon log lines into an error finding (or nothing). The production
 * implementation calls a cheap model over the Anthropic API; tests fake this interface, and the
 * MODEL observer degrades to a no-op when the implementation reports itself disabled (no API key).
 */
public interface LogClassifier {

  /** Whether classification calls can be made at all (an API key is configured). */
  boolean enabled();

  /**
   * Classify {@code logBatch}; empty when the batch is unremarkable. {@code promptOverride}
   * replaces the built-in classification prompt when non-null (a per-observer customization).
   */
  Optional<Classification> classify(String promptOverride, String logBatch);

  /**
   * The classifier's verdict. {@code firstLineOffset} is the 0-based index of the first problematic
   * line within the submitted batch, so the event excerpt can start at the evidence.
   */
  record Classification(
      DaemonEventSeverity severity, String errorType, String summary, int firstLineOffset) {}
}
