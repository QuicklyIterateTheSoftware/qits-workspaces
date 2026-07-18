package eu.wohlben.qits.domain.process.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.process.dto.TechnicalProcessFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The framework-free process core: replay-then-live ordering, the two-part done predicate
 * (provision + declared daemon set), failure settling, and the head+tail buffer bound.
 */
class TechnicalProcessTest {

  /** Records every frame; the plain-JUnit stand-in for the SSE adapter. */
  private static final class RecordingListener implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();
    boolean done;

    @Override
    public void onFrame(TechnicalProcessFrame frame) {
      frames.add(frame);
    }

    @Override
    public void onDone() {
      done = true;
    }

    @Override
    public boolean isOpen() {
      return true;
    }
  }

  private final List<TechnicalProcess> completed = new ArrayList<>();

  private TechnicalProcess process() {
    return new TechnicalProcess("p-1", "repo-1", "ws-1", completed::add);
  }

  private static List<String> kinds(RecordingListener listener) {
    return listener.frames.stream().map(TechnicalProcessFrame::kind).toList();
  }

  @Test
  void aLateSubscriberReplaysBufferedSegmentsThenReceivesLiveFramesThenDone() {
    TechnicalProcess process = process();
    process.openSegment("docker-run");
    process.appendLine("docker-run", "created");
    process.settleSegment("docker-run", true);
    process.openSegment("clone");
    process.appendLine("clone", "Cloning…");

    RecordingListener late = new RecordingListener();
    process.attach(late);
    assertEquals(
        List.of("segment-open", "line", "segment-settled", "segment-open", "line"), kinds(late));

    process.appendLine("clone", "done.");
    process.settleSegment("clone", true);
    process.finishProvision(true);
    process.expectDaemons(List.of());

    assertEquals(
        List.of(
            "segment-open",
            "line",
            "segment-settled",
            "segment-open",
            "line",
            "line",
            "segment-settled",
            "done"),
        kinds(late));
    assertTrue(late.done);
    assertEquals("ok", late.frames.get(late.frames.size() - 1).status());
    assertEquals(List.of("p-1"), completed.stream().map(TechnicalProcess::id).toList());
  }

  @Test
  void doneWaitsForEveryExpectedDaemonSegmentToSettle() {
    TechnicalProcess process = process();
    process.finishProvision(true);
    assertFalse(process.isTerminal(), "no daemon declaration yet — must stay open");

    process.expectDaemons(List.of("web", "worker"));
    assertFalse(process.isTerminal());

    process.settleSegment(TechnicalProcess.daemonSegment("web"), true);
    assertFalse(process.isTerminal());

    process.settleSegment(TechnicalProcess.daemonSegment("worker"), true);
    assertTrue(process.isTerminal());
  }

  @Test
  void aCrashedDaemonYieldsDoneFailed() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.finishProvision(true);
    process.expectDaemons(List.of("web"));
    process.settleSegment(TechnicalProcess.daemonSegment("web"), false);

    assertTrue(process.isTerminal());
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
  }

  @Test
  void failProvisionSettlesTheOpenSegmentWithTheMessageAndEndsImmediately() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("clone");
    process.failProvision("Clone into container failed: boom");

    assertTrue(process.isTerminal(), "provision failure ends without any daemon phase");
    TechnicalProcessFrame settle =
        listener.frames.stream()
            .filter(f -> "segment-settled".equals(f.kind()))
            .findFirst()
            .orElseThrow();
    assertEquals("clone", settle.segment());
    assertEquals("failed", settle.status());
    assertTrue(
        listener.frames.stream()
            .anyMatch(f -> "line".equals(f.kind()) && f.line().contains("boom")));
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
  }

  @Test
  void aTerminalProcessReplaysEverythingAndCompletesImmediately() {
    TechnicalProcess process = process();
    process.completeNoOp("container-start", "already running");

    RecordingListener late = new RecordingListener();
    process.attach(late);
    assertTrue(late.done);
    assertEquals(List.of("segment-open", "line", "segment-settled", "done"), kinds(late));
  }

  @Test
  void settleIsIdempotentAndLinesToSettledSegmentsAreDropped() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("s");
    process.settleSegment("s", true);
    process.settleSegment("s", false);
    process.appendLine("s", "late line");

    long settles = listener.frames.stream().filter(f -> "segment-settled".equals(f.kind())).count();
    long lines = listener.frames.stream().filter(f -> "line".equals(f.kind())).count();
    assertEquals(1, settles, "first verdict wins");
    assertEquals(0, lines, "a settled segment accepts no more lines");
  }

  @Test
  void theBufferKeepsHeadAndTailAndMarksTheElidedMiddleOnReplay() {
    TechnicalProcess process = process();
    process.openSegment("clone");
    // Well past 256 KB total so the rolling tail must evict: 40k lines à ~10 bytes.
    for (int i = 0; i < 40_000; i++) {
      process.appendLine("clone", "line-" + i);
    }

    RecordingListener late = new RecordingListener();
    process.attach(late);
    List<String> lines =
        late.frames.stream()
            .filter(f -> "line".equals(f.kind()))
            .map(TechnicalProcessFrame::line)
            .toList();
    assertEquals("line-0", lines.get(0), "the head survives verbatim");
    assertEquals("line-39999", lines.get(lines.size() - 1), "the tail ends at the newest line");
    assertTrue(
        lines.stream().anyMatch(l -> l.contains("elided")),
        "the evicted middle is marked in the replay");
    assertTrue(lines.size() < 40_000, "the replay is bounded");
  }

  @Test
  void forceFinishEmitsDoneFailedWithoutSettlingOpenSegments() {
    TechnicalProcess process = process();
    RecordingListener listener = new RecordingListener();
    process.attach(listener);
    process.openSegment("docker-run");
    process.forceFinish();

    assertTrue(process.isTerminal());
    assertEquals("done", listener.frames.get(listener.frames.size() - 1).kind());
    assertEquals("failed", listener.frames.get(listener.frames.size() - 1).status());
    assertEquals(
        0,
        listener.frames.stream().filter(f -> "segment-settled".equals(f.kind())).count(),
        "open segments stay unsettled — the backstop only ends the stream");
  }
}
