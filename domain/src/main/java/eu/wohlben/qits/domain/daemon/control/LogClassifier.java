package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import java.util.Optional;

/**
 * Classifies a batch of daemon log lines into an error/warning finding (or nothing). The default
 * implementation ({@link LogLevelClassifier}) works purely locally off the lines' own severity
 * tokens; the interface is the extension point a future model-backed classifier would implement —
 * deliberately not built today: qits makes no outbound API calls from log observation.
 */
public interface LogClassifier {

  /** Classify {@code logBatch} (newline-joined lines); empty when the batch is unremarkable. */
  Optional<Classification> classify(String logBatch);

  /**
   * The classifier's verdict. {@code firstLineOffset} is the 0-based index of the first problematic
   * line within the submitted batch, so the event excerpt can start at the evidence.
   */
  record Classification(
      DaemonEventSeverity severity, String errorType, String summary, int firstLineOffset) {}
}
