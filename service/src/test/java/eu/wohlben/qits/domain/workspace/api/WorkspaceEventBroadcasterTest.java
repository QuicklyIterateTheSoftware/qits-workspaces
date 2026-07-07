package eu.wohlben.qits.domain.workspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint;
import eu.wohlben.qits.domain.workspace.control.WorkspaceChangeHint.Topic;
import io.smallrye.mutiny.helpers.test.AssertSubscriber;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit test of the broadcaster's routing, debounce and channel lifecycle — no Quarkus needed
 * (the debounce window is set directly and {@link #onHint} driven by hand). The CDI async wiring
 * that feeds real hints in is covered by {@link WorkspaceChangeHintBusTest}.
 */
class WorkspaceEventBroadcasterTest {

  private WorkspaceEventBroadcaster broadcaster;

  @BeforeEach
  void setUp() {
    broadcaster = new WorkspaceEventBroadcaster();
    broadcaster.debounceMillis = 100;
  }

  private void fire(String repoId, String workspaceId, Topic topic) {
    broadcaster.onHint(new WorkspaceChangeHint(repoId, workspaceId, topic));
  }

  @Test
  void deliversTheHyphenatedTopicNameToTheWorkspaceChannel() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", "wt-1", Topic.DAEMON_EVENTS);

    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals("daemon-events", sub.getItems().get(0));
  }

  @Test
  void aHintForOneWorkspaceDoesNotReachAnother() {
    AssertSubscriber<String> a =
        broadcaster
            .subscribe("repo-1", "wt-a")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    AssertSubscriber<String> b =
        broadcaster
            .subscribe("repo-1", "wt-b")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", "wt-a", Topic.DAEMONS);

    a.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, a.getItems().size());
    b.assertHasNotReceivedAnyItem();
  }

  @Test
  void debounceCollapsesABurstToAtMostLeadingPlusTrailing() throws InterruptedException {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    for (int i = 0; i < 8; i++) {
      fire("repo-1", "wt-1", Topic.TELEMETRY);
    }

    // Leading edge is immediate; the burst coalesces into one trailing after the window.
    sub.awaitItems(2, Duration.ofSeconds(2));
    Thread.sleep(300); // well past two debounce windows — no further emits should arrive
    assertEquals(2, sub.getItems().size());
    assertTrue(sub.getItems().stream().allMatch("telemetry"::equals));
  }

  @Test
  void distinctTopicsForTheSameWorkspaceEachEmitTheirLeadingHint() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", "wt-1", Topic.DAEMONS);
    fire("repo-1", "wt-1", Topic.COMMANDS);

    sub.awaitItems(2, Duration.ofSeconds(2));
    assertTrue(sub.getItems().contains("daemons"));
    assertTrue(sub.getItems().contains("commands"));
  }

  @Test
  void theChannelIsDroppedWhenItsLastSubscriberCancels() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    fire("repo-1", "wt-1", Topic.DAEMONS);
    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, broadcaster.openChannelCount());

    sub.cancel();

    assertEquals(0, broadcaster.openChannelCount());
  }

  @Test
  void hintsForAWorkspaceWithNoSubscribersAreSafelyDropped() {
    // No subscriber for wt-gone: firing must not throw and must open no channel.
    fire("repo-1", "wt-gone", Topic.DAEMONS);
    assertEquals(0, broadcaster.openChannelCount());
  }
}
