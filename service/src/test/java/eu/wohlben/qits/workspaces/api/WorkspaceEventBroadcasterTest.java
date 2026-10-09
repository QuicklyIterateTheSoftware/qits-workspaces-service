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

  private void fire(String repoId, Long workspaceRowId, Topic topic) {
    broadcaster.onHint(new WorkspaceChangeHint(repoId, workspaceRowId, topic));
  }

  @Test
  void deliversTheHyphenatedTopicNameToTheWorkspaceChannel() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", 1L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", 1L, Topic.PROMPT_ATTACHMENTS);

    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals("prompt-attachments", sub.getItems().get(0));
  }

  @Test
  void aHintForOneWorkspaceDoesNotReachAnother() {
    AssertSubscriber<String> a =
        broadcaster
            .subscribe("repo-1", 101L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    AssertSubscriber<String> b =
        broadcaster
            .subscribe("repo-1", 102L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", 101L, Topic.FILES);

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
            .subscribe("repo-1", 1L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    AssertSubscriber<String> repository =
        broadcaster
            .subscribe("repo-1", null)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", 1L, Topic.AGENT_ACTIVITY);
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
            .subscribe("repo-1", 1L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    for (int i = 0; i < 8; i++) {
      fire("repo-1", 1L, Topic.TELEMETRY);
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
            .subscribe("repo-1", 1L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));

    fire("repo-1", 1L, Topic.BOOTSTRAP);
    fire("repo-1", 1L, Topic.COMMANDS);

    sub.awaitItems(2, Duration.ofSeconds(2));
    assertTrue(sub.getItems().contains("bootstrap"));
    assertTrue(sub.getItems().contains("commands"));
  }

  @Test
  void theChannelIsDroppedWhenItsLastSubscriberCancels() {
    AssertSubscriber<String> sub =
        broadcaster
            .subscribe("repo-1", 1L)
            .subscribe()
            .withSubscriber(AssertSubscriber.create(Long.MAX_VALUE));
    fire("repo-1", 1L, Topic.FILES);
    sub.awaitItems(1, Duration.ofSeconds(2));
    assertEquals(1, broadcaster.openChannelCount());

    sub.cancel();

    assertEquals(0, broadcaster.openChannelCount());
  }

  @Test
  void hintsForAWorkspaceWithNoSubscribersAreSafelyDropped() {
    // No subscriber for wt-gone: firing must not throw and must open no channel.
    fire("repo-1", 999L, Topic.FILES);
    assertEquals(0, broadcaster.openChannelCount());
  }
}
