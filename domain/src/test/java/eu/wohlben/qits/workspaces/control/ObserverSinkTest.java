package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.ServiceEventSeverity;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the shared log pipeline still in use after the log-observer subsystem was
 * removed: line framing over raw PTY chunks (the base of the ready-pattern and crash-excerpt
 * sinks), and the local log-level classifier that grades the general command audit log.
 */
public class ObserverSinkTest {

  private static final class CapturingLines extends LineFramingSink {
    final List<String> lines = new ArrayList<>();

    @Override
    protected void onLine(String line) {
      lines.add(line);
    }
  }

  @Test
  public void framesChunksIntoCleanLines() {
    CapturingLines sink = new CapturingLines();

    sink.write("hel");
    sink.write("lo\r\nwo");
    sink.write("rld\n");
    sink.write("[31mred error[0m\n");

    assertEquals(List.of("hello", "world", "red error"), sink.lines);
  }

  @Test
  public void logLevelClassifierReadsTheSeverityVocabularyLogsCarry() {
    LogLevelClassifier classifier = new LogLevelClassifier();

    // Routine output — including "0 errors" wording — is unremarkable.
    assertTrue(classifier.classify("GET /api/users 200 12ms").isEmpty());
    assertTrue(classifier.classify("Found 0 errors. Watching for file changes.").isEmpty());
    assertTrue(classifier.classify("webpack compiled successfully in 421 ms").isEmpty());

    // An exception class name is the strongest signal and becomes the errorType.
    var npe =
        classifier
            .classify("request in\njava.lang.NullPointerException: s is null\n\tat Api.java:42")
            .orElseThrow();
    assertEquals(ServiceEventSeverity.ERROR, npe.severity());
    assertEquals("NullPointerException", npe.errorType());
    assertEquals(1, npe.firstLineOffset(), "offset points at the exception line");

    // Level tokens classify too; ERROR beats an earlier WARNING in the same batch.
    var levelToken =
        classifier.classify("WARN slow query (1.2s)\nERROR: connection refused").orElseThrow();
    assertEquals(ServiceEventSeverity.ERROR, levelToken.severity());
    assertEquals("error-log", levelToken.errorType());
    assertEquals(1, levelToken.firstLineOffset());

    var warning = classifier.classify("DeprecationWarning: DEP0123 something old").orElseThrow();
    assertEquals(ServiceEventSeverity.WARNING, warning.severity());

    // A line's explicit level wins over an incidental "error" keyword in its message: Quarkus'
    // telemetry-export line is WARNING-level and mentions "Full error message", but it must NOT be
    // escalated to ERROR.
    var telemetry =
        classifier
            .classify(
                "2026-07-05 12:51:29,399 WARNING [io.quarkus.opentelemetry.runtime.exporter.otlp"
                    + ".sender.VertxHttpSender] (vert.x-eventloop-thread-8) Failed to export."
                    + " Full error message: Connection refused: localhost/127.0.0.1:4317")
            .orElseThrow();
    assertEquals(ServiceEventSeverity.WARNING, telemetry.severity(), "declared WARNING wins");

    // An explicit INFO level keeps routine output quiet even when it name-drops an exception.
    assertTrue(
        classifier.classify("INFO [app] retry succeeded after a TimeoutException").isEmpty(),
        "an explicit sub-warning level is not a finding");
  }
}
