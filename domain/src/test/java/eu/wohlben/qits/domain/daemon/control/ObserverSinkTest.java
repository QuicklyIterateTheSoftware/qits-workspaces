package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the observer sinks: line framing over raw PTY chunks, the PATTERN observer
 * (match, severity, throttle), the LOG_LEVEL observer's batching (quiet logs produce nothing, an
 * error batch produces one finding carrying the offset-based excerpt), and the local log-level
 * classifier itself.
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
    sink.write("\u001B[31mred error\u001B[0m\n");

    assertEquals(List.of("hello", "world", "red error"), sink.lines);
  }

  @Test
  public void patternObserverEmitsAndThrottles() {
    List<ObserverFinding> findings = new ArrayList<>();
    PatternLogObserver observer =
        new PatternLogObserver(
            Pattern.compile("ERROR"), DaemonEventSeverity.WARNING, findings::add);

    observer.write("all fine\nERROR: broke once\nERROR: broke twice\n");

    assertEquals(1, findings.size(), "second match within the throttle window is dropped");
    ObserverFinding finding = findings.get(0);
    assertEquals(DaemonEventSeverity.WARNING, finding.severity());
    assertEquals("ERROR: broke once", finding.summary());
  }

  @Test
  public void logLevelObserverIgnoresQuietLogsAndClassifiesErrorBatches() throws Exception {
    CountDownLatch findingLatch = new CountDownLatch(1);
    List<ObserverFinding> findings = new ArrayList<>();
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      LogLevelObserver observer =
          new LogLevelObserver(
              new LogLevelClassifier(),
              scheduler,
              finding -> {
                findings.add(finding);
                findingLatch.countDown();
              });

      // Quiet, healthy output: buffered, debounced, classified as unremarkable — no finding.
      observer.write("GET / 200 5ms\nGET /favicon.ico 200 1ms\n");
      Thread.sleep(2500);
      assertEquals(0, findings.size(), "quiet logs must not produce findings");

      // An error batch: one finding for the whole burst, excerpt starting at the evidence.
      observer.write("serving request\nTraceback (most recent call last):\n");
      observer.write("ZeroDivisionError: division by zero\n");
      assertTrue(findingLatch.await(10, TimeUnit.SECONDS), "finding should arrive after debounce");
      assertEquals(1, findings.size(), "one finding per batch");
      ObserverFinding finding = findings.get(0);
      assertEquals(DaemonEventSeverity.ERROR, finding.severity());
      assertTrue(
          finding.logExcerpt().startsWith("Traceback"),
          "excerpt starts at the classified offset: " + finding.logExcerpt());
    } finally {
      scheduler.shutdownNow();
    }
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
    assertEquals(DaemonEventSeverity.ERROR, npe.severity());
    assertEquals("NullPointerException", npe.errorType());
    assertEquals(1, npe.firstLineOffset(), "offset points at the exception line");

    // Level tokens classify too; ERROR beats an earlier WARNING in the same batch.
    var levelToken =
        classifier.classify("WARN slow query (1.2s)\nERROR: connection refused").orElseThrow();
    assertEquals(DaemonEventSeverity.ERROR, levelToken.severity());
    assertEquals("error-log", levelToken.errorType());
    assertEquals(1, levelToken.firstLineOffset());

    var warning = classifier.classify("DeprecationWarning: DEP0123 something old").orElseThrow();
    assertEquals(DaemonEventSeverity.WARNING, warning.severity());
  }
}
