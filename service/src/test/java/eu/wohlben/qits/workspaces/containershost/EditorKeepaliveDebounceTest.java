package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The debounce, alone: <b>one touch per interval, not one per request</b>.
 *
 * <p>Plain JUnit and a supplied clock, because that claim is about a compare-and-set and nothing
 * else — no database, no container, no waiting for a real interval to pass. The wiring around it is
 * {@link EditorKeepaliveTest}'s.
 */
class EditorKeepaliveDebounceTest {

  private static final long INTERVAL = 30_000L;

  @Test
  void theFirstCallClaimsAndTheRestOfTheWindowDoesNot() {
    Map<Long, Long> touches = new ConcurrentHashMap<>();

    assertTrue(EditorKeepalive.claim(touches, 1L, 1_000L, INTERVAL), "nothing known yet");
    assertFalse(EditorKeepalive.claim(touches, 1L, 1_001L, INTERVAL));
    assertFalse(EditorKeepalive.claim(touches, 1L, 30_999L, INTERVAL), "one millisecond short");
    assertTrue(EditorKeepalive.claim(touches, 1L, 31_000L, INTERVAL), "the window has passed");
    assertFalse(EditorKeepalive.claim(touches, 1L, 31_001L, INTERVAL), "and starts again");
  }

  @Test
  void oneWorkspacesWindowIsNotAnothers() {
    // An editor session in one project must not silence the keepalive of another.
    Map<Long, Long> touches = new ConcurrentHashMap<>();

    assertTrue(EditorKeepalive.claim(touches, 1L, 1_000L, INTERVAL));
    assertTrue(EditorKeepalive.claim(touches, 2L, 1_000L, INTERVAL));
    assertFalse(EditorKeepalive.claim(touches, 1L, 1_500L, INTERVAL));
  }

  @Test
  void exactlyOneOfAFloodClaimsIt() throws Exception {
    // The reason the claim is a `compute` and not a get-then-put. An editor session is a stream of
    // frames arriving on several threads; a read-then-write would let two of them see the same
    // stale timestamp and both touch, which is the defect this whole class exists to prevent.
    Map<Long, Long> touches = new ConcurrentHashMap<>();
    int threads = 16;
    AtomicInteger claimed = new AtomicInteger();
    CountDownLatch go = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int i = 0; i < threads; i++) {
        pool.submit(
            () -> {
              try {
                go.await();
                if (EditorKeepalive.claim(touches, 7L, 5_000L, INTERVAL)) {
                  claimed.incrementAndGet();
                }
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      go.countDown();
      assertTrue(done.await(10, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, claimed.get(), "one touch, however many callers said the same thing at once");
  }
}
