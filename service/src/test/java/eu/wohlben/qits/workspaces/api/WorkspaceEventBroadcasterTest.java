package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint.Topic;
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

    fire("repo-1", "wt-1", Topic.SERVICE_EVENTS);

    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals("service-events", sub.getItems().get(0));
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

    fire("repo-1", "wt-a", Topic.SERVICES);

    a.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, a.getItems().size());
    b.assertHasNotReceivedAnyItem();
  }

  @Test
  void theGlobalChannelIsIsolatedFromWorkspaceAndRepositoryChannels() {
    // (null, null) is the global channel's key — a workspace or repository hint must not leak into
    // it, and a global hint must not leak out of it (the keys "repoId/wt", "repoId/null" and
    // "null/null" can never collide).
    AssertSubscriber<String> global =
        broadcaster
            .subscribe(null, null)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    AssertSubscriber<String> workspace =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    AssertSubscriber<String> repository =
        broadcaster
            .subscribe("repo-1", null)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", "wt-1", Topic.AGENT_ACTIVITY);
    fire("repo-1", null, Topic.AGENT_ACTIVITY);
    fire(null, null, Topic.AGENT_ACTIVITY);

    global.awaitItems(1, Duration.ofSeconds(2));
    workspace.awaitItems(1, Duration.ofSeconds(2));
    repository.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, global.getItems().size());
    assertEquals("agent-activity", global.getItems().get(0));
    assertEquals(1, workspace.getItems().size());
    assertEquals(1, repository.getItems().size());
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

    fire("repo-1", "wt-1", Topic.SERVICES);
    fire("repo-1", "wt-1", Topic.COMMANDS);

    sub.awaitItems(2, Duration.ofSeconds(2));
    assertTrue(sub.getItems().contains("services"));
    assertTrue(sub.getItems().contains("commands"));
  }

  @Test
  void theChannelIsDroppedWhenItsLastSubscriberCancels() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", "wt-1")
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    fire("repo-1", "wt-1", Topic.SERVICES);
    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, broadcaster.openChannelCount());

    sub.cancel();

    assertEquals(0, broadcaster.openChannelCount());
  }

  @Test
  void hintsForAWorkspaceWithNoSubscribersAreSafelyDropped() {
    // No subscriber for wt-gone: firing must not throw and must open no channel.
    fire("repo-1", "wt-gone", Topic.SERVICES);
    assertEquals(0, broadcaster.openChannelCount());
  }
}
