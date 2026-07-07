package eu.wohlben.qits.domain.workspace.api;

import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Test bean that observes {@link WorkspaceChangeHint}s off the same CDI async bus the production
 * SSE broadcaster uses, so a test can assert a producer fired the right topic. Async delivery means
 * tests must {@link #poll} (block briefly) rather than read synchronously.
 */
@ApplicationScoped
public class HintCollector {

  private final BlockingQueue<WorkspaceChangeHint> hints = new LinkedBlockingQueue<>();

  void onHint(@ObservesAsync WorkspaceChangeHint hint) {
    hints.add(hint);
  }

  /** Drop anything queued from prior test activity. */
  public void clear() {
    hints.clear();
  }

  /** Wait up to {@code timeoutMs} for the next hint, or null if none arrives. */
  public WorkspaceChangeHint poll(long timeoutMs) throws InterruptedException {
    return hints.poll(timeoutMs, TimeUnit.MILLISECONDS);
  }
}
