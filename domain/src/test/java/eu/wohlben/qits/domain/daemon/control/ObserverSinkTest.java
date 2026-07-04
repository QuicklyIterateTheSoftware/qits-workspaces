package eu.wohlben.qits.domain.daemon.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.daemon.entity.DaemonEventSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the observer sinks: line framing over raw PTY chunks, the PATTERN observer
 * (match, severity, throttle), and the MODEL observer's gating — quiet logs never reach the
 * classifier, an error batch produces exactly one call and one finding carrying the excerpt.
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
  public void modelObserverGatesQuietLogsAndClassifiesErrorBatches() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch findingLatch = new CountDownLatch(1);
    List<ObserverFinding> findings = new ArrayList<>();
    LogClassifier classifier =
        new LogClassifier() {
          @Override
          public boolean enabled() {
            return true;
          }

          @Override
          public Optional<Classification> classify(String promptOverride, String logBatch) {
            calls.incrementAndGet();
            assertTrue(logBatch.contains("Traceback"), "batch carries the lines: " + logBatch);
            return Optional.of(
                new Classification(
                    DaemonEventSeverity.ERROR, "ZeroDivisionError", "division by zero", 1));
          }
        };
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ModelLogObserver observer =
          new ModelLogObserver(
              null,
              classifier,
              scheduler,
              finding -> {
                findings.add(finding);
                findingLatch.countDown();
              });

      // Quiet, healthy output: buffered, debounced, then dropped by the pre-filter — no call.
      observer.write("GET / 200 5ms\nGET /favicon.ico 200 1ms\n");
      Thread.sleep(2500);
      assertEquals(0, calls.get(), "quiet logs must never reach the model");

      // An error batch: one call for the whole batch, one finding with the offset-based excerpt.
      observer.write("serving request\nTraceback (most recent call last):\n");
      observer.write("ZeroDivisionError: division by zero\n");
      assertTrue(findingLatch.await(10, TimeUnit.SECONDS), "finding should arrive after debounce");
      assertEquals(1, calls.get(), "one classification call per batch");
      ObserverFinding finding = findings.get(0);
      assertEquals(DaemonEventSeverity.ERROR, finding.severity());
      assertEquals("ZeroDivisionError", finding.errorType());
      assertTrue(
          finding.logExcerpt().startsWith("Traceback"),
          "excerpt starts at the reported offset: " + finding.logExcerpt());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void modelObserverIsInertWithoutAnApiKey() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    LogClassifier disabled =
        new LogClassifier() {
          @Override
          public boolean enabled() {
            return false;
          }

          @Override
          public Optional<Classification> classify(String promptOverride, String logBatch) {
            calls.incrementAndGet();
            return Optional.empty();
          }
        };
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ModelLogObserver observer = new ModelLogObserver(null, disabled, scheduler, finding -> {});
      observer.write("Traceback (most recent call last): error error error\n");
      Thread.sleep(2500);
      assertEquals(0, calls.get());
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  public void classifierReplyParsingIsStrictButLenient() {
    assertTrue(AnthropicLogClassifier.parse("NONE").isEmpty());
    assertTrue(AnthropicLogClassifier.parse("").isEmpty());
    assertTrue(AnthropicLogClassifier.parse("garbage without pipes").isEmpty());
    assertTrue(AnthropicLogClassifier.parse("BOGUS|type|summary|0").isEmpty());

    var parsed =
        AnthropicLogClassifier.parse("ERROR|port-in-use|Port 3000 is already in use|2")
            .orElseThrow();
    assertEquals(DaemonEventSeverity.ERROR, parsed.severity());
    assertEquals("port-in-use", parsed.errorType());
    assertEquals("Port 3000 is already in use", parsed.summary());
    assertEquals(2, parsed.firstLineOffset());

    var malformedOffset =
        AnthropicLogClassifier.parse("WARNING|deprecation|Something old|not-a-number")
            .orElseThrow();
    assertEquals(0, malformedOffset.firstLineOffset(), "bad offset defaults to batch start");
  }
}
