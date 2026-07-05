package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.command.entity.LogChannel;
import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the observer pipeline: line framing over raw PTY chunks, the process-output
 * tap (channel filter, ANSI stripping, sequence-preserving positions), the PATTERN observer (match,
 * severity, throttle, anchor), the LOG_LEVEL observer's batching (quiet logs produce nothing, an
 * error batch produces one finding carrying the offset-based excerpt and its anchor range), and the
 * local log-level classifier itself.
 */
public class ObserverSinkTest {

  private static ObservedLine out(long position, String content) {
    return new ObservedLine(ObservedLine.PROCESS_OUTPUT, position, null, content);
  }

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
  public void processOutputTapForwardsOutputLinesWithTheirPersistedSequence() {
    List<ObservedLine> observed = new ArrayList<>();
    ProcessOutputTap tap = new ProcessOutputTap(List.of(observed::add));

    tap.append("cmd-1", 0, LogChannel.OUTPUT, "\u001B[32mready\u001B[0m", Instant.now());
    tap.append("cmd-1", 1, LogChannel.STDIN, "typed input", Instant.now());
    tap.append("cmd-1", 2, LogChannel.OUTPUT, "   ", Instant.now());
    tap.append("cmd-1", 3, LogChannel.OUTPUT, "second", Instant.now());

    assertEquals(2, observed.size(), "STDIN and blank lines are not observed");
    assertEquals("ready", observed.get(0).content(), "ANSI escapes are stripped");
    assertEquals(0, observed.get(0).position(), "position is the command_log_line seq");
    assertEquals(ObservedLine.PROCESS_OUTPUT, observed.get(0).source());
    assertNull(observed.get(0).sourceEpoch(), "process output has no rotation epoch");
    assertEquals(3, observed.get(1).position(), "skipped lines still consume their seq");
  }

  @Test
  public void patternObserverEmitsAnchoredFindingAndThrottles() {
    List<ObserverFinding> findings = new ArrayList<>();
    PatternLogObserver observer =
        new PatternLogObserver(
            Pattern.compile("ERROR"), DaemonEventSeverity.WARNING, findings::add);

    observer.onLine(out(7, "all fine"));
    observer.onLine(out(8, "ERROR: broke once"));
    observer.onLine(out(9, "ERROR: broke twice"));

    assertEquals(1, findings.size(), "second match within the throttle window is dropped");
    ObserverFinding finding = findings.get(0);
    assertEquals(DaemonEventSeverity.WARNING, finding.severity());
    assertEquals("ERROR: broke once", finding.summary());
    assertEquals(ObservedLine.PROCESS_OUTPUT, finding.source());
    assertEquals(8, finding.anchorFrom(), "the anchor is the matched line's position");
    assertEquals(8, finding.anchorTo());
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
      observer.onLine(out(0, "GET / 200 5ms"));
      observer.onLine(out(1, "GET /favicon.ico 200 1ms"));
      Thread.sleep(2500);
      assertEquals(0, findings.size(), "quiet logs must not produce findings");

      // An error batch: one finding for the whole burst, excerpt starting at the evidence and the
      // anchor bounding exactly the excerpt's lines.
      observer.onLine(out(2, "serving request"));
      observer.onLine(out(3, "Traceback (most recent call last):"));
      observer.onLine(out(4, "ZeroDivisionError: division by zero"));
      assertTrue(findingLatch.await(10, TimeUnit.SECONDS), "finding should arrive after debounce");
      assertEquals(1, findings.size(), "one finding per batch");
      ObserverFinding finding = findings.get(0);
      assertEquals(DaemonEventSeverity.ERROR, finding.severity());
      assertTrue(
          finding.logExcerpt().startsWith("Traceback"),
          "excerpt starts at the classified offset: " + finding.logExcerpt());
      assertEquals(ObservedLine.PROCESS_OUTPUT, finding.source());
      assertEquals(3, finding.anchorFrom(), "anchor starts at the classified line");
      assertEquals(4, finding.anchorTo(), "anchor ends at the batch's last line");
      assertEquals(
          "Traceback (most recent call last):\nZeroDivisionError: division by zero",
          finding.logExcerpt(),
          "the excerpt equals the anchored lines' content");
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

    // A line's explicit level wins over an incidental "error" keyword in its message: Quarkus'
    // telemetry-export line is WARNING-level and mentions "Full error message", but it must NOT be
    // escalated to ERROR (which would flip a healthy dev-server daemon to DEGRADED).
    var telemetry =
        classifier
            .classify(
                "2026-07-05 12:51:29,399 WARNING [io.quarkus.opentelemetry.runtime.exporter.otlp"
                    + ".sender.VertxHttpSender] (vert.x-eventloop-thread-8) Failed to export."
                    + " Full error message: Connection refused: localhost/127.0.0.1:4317")
            .orElseThrow();
    assertEquals(DaemonEventSeverity.WARNING, telemetry.severity(), "declared WARNING wins");

    // An explicit INFO level keeps routine output quiet even when it name-drops an exception.
    assertTrue(
        classifier.classify("INFO [app] retry succeeded after a TimeoutException").isEmpty(),
        "an explicit sub-warning level is not a finding");
  }
}
