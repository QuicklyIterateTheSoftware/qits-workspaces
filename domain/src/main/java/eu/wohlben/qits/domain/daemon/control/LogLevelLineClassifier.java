package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.command.control.LogLineClassifier;
import eu.wohlben.qits.domain.command.entity.LogSeverity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Backs the command area's per-line severity stamping with the same local vocabulary the LOG_LEVEL
 * observer uses ({@link LogLevelClassifier}), so {@code ?severity=} filters on the command log and
 * observer findings agree on what counts as an error. Raw captured lines still carry ANSI escapes;
 * they are stripped before classification.
 */
@ApplicationScoped
public class LogLevelLineClassifier implements LogLineClassifier {

  @Inject LogLevelClassifier classifier;

  @Override
  public Optional<LogSeverity> classify(String rawLine) {
    String cleaned = LineFramingSink.stripAnsi(rawLine);
    if (cleaned.isBlank()) {
      return Optional.empty();
    }
    return classifier
        .classify(cleaned)
        .map(classification -> LogSeverity.valueOf(classification.severity().name()));
  }
}
