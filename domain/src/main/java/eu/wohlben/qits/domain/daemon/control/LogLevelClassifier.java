package eu.wohlben.qits.domain.daemon.control;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The default {@link LogClassifier}: purely local, deterministic classification off the severity
 * vocabulary logs already carry — level tokens (FATAL/ERROR/WARN…), exception class names, and
 * stack-trace openers. No API calls, no configuration, no cost. Routine INFO/DEBUG output is
 * deliberately not a finding: the full stream is persisted as the command's log regardless;
 * observers only exist to surface problems.
 */
@ApplicationScoped
public class LogLevelClassifier implements LogClassifier {

  /**
   * Word-bounded level tokens so "Found 0 errors" (plural) and "error-page.html" (hyphenated path
   * segments are still word boundaries, accepted false positive) behave sanely; exception class
   * names and the Python traceback opener are the strongest signals.
   */
  private static final Pattern ERROR_SIGNAL =
      Pattern.compile(
          "\\b[A-Z][A-Za-z0-9]*(?:Exception|Error)\\b"
              + "|Traceback \\(most recent call last\\)"
              + "|(?i:\\b(?:fatal|error|severe|panic|critical)\\b)");

  private static final Pattern WARNING_SIGNAL =
      Pattern.compile(
          "(?i:\\b(?:warn|warning|deprecated|deprecation)\\b)"
              // CamelCase compounds like DeprecationWarning defeat word boundaries; match the
              // class-name shape explicitly.
              + "|\\b[A-Z][A-Za-z0-9]*Warning\\b");

  /** Exception/error class name (e.g. NullPointerException, TypeError) for the errorType. */
  private static final Pattern EXCEPTION_NAME =
      Pattern.compile("\\b([A-Z][A-Za-z0-9]*(?:Exception|Error))\\b");

  private static final int MAX_SUMMARY_CHARS = 200;

  @Override
  public Optional<Classification> classify(String logBatch) {
    String[] lines = logBatch.split("\n", -1);
    int firstWarning = -1;
    for (int i = 0; i < lines.length; i++) {
      if (ERROR_SIGNAL.matcher(lines[i]).find()) {
        return Optional.of(classification(DaemonEventSeverity.ERROR, lines[i], i));
      }
      if (firstWarning < 0 && WARNING_SIGNAL.matcher(lines[i]).find()) {
        firstWarning = i;
      }
    }
    if (firstWarning >= 0) {
      return Optional.of(
          classification(DaemonEventSeverity.WARNING, lines[firstWarning], firstWarning));
    }
    return Optional.empty();
  }

  private static Classification classification(
      DaemonEventSeverity severity, String line, int offset) {
    Matcher exception = EXCEPTION_NAME.matcher(line);
    String errorType =
        exception.find()
            ? exception.group(1)
            : (severity == DaemonEventSeverity.ERROR ? "error-log" : "warning-log");
    String summary = line.strip();
    if (summary.length() > MAX_SUMMARY_CHARS) {
      summary = summary.substring(0, MAX_SUMMARY_CHARS);
    }
    return new Classification(severity, errorType, summary, offset);
  }
}
