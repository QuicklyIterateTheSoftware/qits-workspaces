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

  /**
   * An explicit, standalone UPPERCASE log-level token — the severity field a structured log line
   * already carries (Quarkus/JBoss {@code INFO/WARNING/ERROR}, JUL {@code SEVERE/FINE}, syslog
   * {@code CRITICAL/NOTICE}…). Case-sensitive on purpose: only a real level field counts, never a
   * lowercase "error"/"warn" appearing incidentally in a message.
   */
  private static final Pattern LEVEL_TOKEN =
      Pattern.compile(
          "\\b(ERROR|SEVERE|FATAL|CRITICAL|PANIC"
              + "|WARN|WARNING|NOTICE"
              + "|INFO|CONFIG|DEBUG|TRACE|FINE|FINER|FINEST)\\b");

  /** Exception/error class name (e.g. NullPointerException, TypeError) for the errorType. */
  private static final Pattern EXCEPTION_NAME =
      Pattern.compile("\\b([A-Z][A-Za-z0-9]*(?:Exception|Error))\\b");

  private static final int MAX_SUMMARY_CHARS = 200;

  @Override
  public Optional<Classification> classify(String logBatch) {
    String[] lines = logBatch.split("\n", -1);
    int firstWarning = -1;
    for (int i = 0; i < lines.length; i++) {
      DaemonEventSeverity severity = classifyLine(lines[i]);
      if (severity == DaemonEventSeverity.ERROR) {
        return Optional.of(classification(DaemonEventSeverity.ERROR, lines[i], i));
      }
      if (severity == DaemonEventSeverity.WARNING && firstWarning < 0) {
        firstWarning = i;
      }
    }
    if (firstWarning >= 0) {
      return Optional.of(
          classification(DaemonEventSeverity.WARNING, lines[firstWarning], firstWarning));
    }
    return Optional.empty();
  }

  /**
   * A single line's severity, or {@code null} when it is unremarkable. A line that carries an
   * explicit level token is classified by <em>that</em> level and nothing else — the log framework
   * already declared how severe the line is, so an incidental "error"/exception keyword in the
   * message must not upgrade a WARNING (or INFO) line to ERROR. This is exactly the case that used
   * to flip a healthy daemon to DEGRADED, e.g. Quarkus' {@code … WARNING [io.…VertxHttpSender]
   * Failed to export … Full error message: …}. Only lines with no explicit level fall back to the
   * keyword / exception-name / traceback heuristics that catch unstructured output.
   */
  private static DaemonEventSeverity classifyLine(String line) {
    Matcher level = LEVEL_TOKEN.matcher(line);
    if (level.find()) {
      return levelSeverity(level.group(1));
    }
    if (ERROR_SIGNAL.matcher(line).find()) {
      return DaemonEventSeverity.ERROR;
    }
    if (WARNING_SIGNAL.matcher(line).find()) {
      return DaemonEventSeverity.WARNING;
    }
    return null;
  }

  /** Maps a declared level token to a finding severity; INFO and below are not findings. */
  private static DaemonEventSeverity levelSeverity(String token) {
    return switch (token) {
      case "ERROR", "SEVERE", "FATAL", "CRITICAL", "PANIC" -> DaemonEventSeverity.ERROR;
      case "WARN", "WARNING", "NOTICE" -> DaemonEventSeverity.WARNING;
      default -> null;
    };
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
