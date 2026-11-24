package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.events.AgentWaitClosed;
import eu.wohlben.qits.workspaces.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A project agent's declared waits (qits-2596, epic qits-2520) on a real database: one work item per
 * wait, the agent holder's limits counted per (holder, item), and the very matcher, sweep and lookups
 * the other holders have closing them — each close recorded and announced as {@link
 * ProjectAgentWaitClosed} instead of a wake or a turn, and the item relayed as waiting on its open
 * waits, or no longer waiting once none is left.
 */
@QuarkusTest
public class ProjectAgentWaitsTest {

  @Inject AgentWaits waits;
  @Inject AgentWaitRepository waitRows;
  @Inject AgentService agentService;
  @Inject RecordingLifecycleAnnouncer announced;
  @Inject FakeAgentWaitingReporter reporter;
  @Inject FakeWaitSubjectResolver resolver;

  private static final String SUBJECT = "tok-project-agent-qits-developer";

  /** One holder's item: a fresh project, item uuid and qualified id per test. */
  private record Item(UUID project, UUID workId, String qualifiedId) {
    static Item fresh() {
      return new Item(UUID.randomUUID(), UUID.randomUUID(), "qits-" + (100 + (int) (Math.random() * 1_000_000)));
    }
  }

  private AgentWaitDto declare(Item item, String label, EventSelection selection, Duration timeout) {
    return waits.createForProjectAgent(
        SUBJECT, item.project(), item.workId(), item.qualifiedId(), label, selection, timeout);
  }

  private static EventSelection built(String runId) {
    return EventSelection.of(
        "ProjectAgentTestBuildFinished", Condition.exact("runId", runId), Condition.exact("status", "SUCCESS"));
  }

  private AgentWait row(String id) {
    return QuarkusTransaction.requiringNew().call(() -> waitRows.findById(UUID.fromString(id)));
  }

  private List<ProjectAgentWaitClosed> closedOf(String waitId) {
    return announced.of(ProjectAgentWaitClosed.class, e -> e.waitId().toString().equals(waitId));
  }

  private FakeAgentWaitingReporter.Report lastRelay(Item item) {
    List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor(item.workId().toString());
    assertFalse(reports.isEmpty(), "the item was relayed");
    return reports.get(reports.size() - 1);
  }

  /** The test's sweep, under the scheduler's pass lock (qits-2103), as {@code DeskWaitsTest}'s. */
  private void sweep() {
    agentService.betweenPasses(
        () -> {
          waits.sweep();
          return null;
        });
  }

  @Test
  void aDeclarationHoldsTheWaitForItsItemAndRelaysTheItemAsWaitingOnIt() {
    Item item = Item.fresh();
    AgentWaitDto wait = declare(item, "run 7 green", built("run-7-" + item.workId()), Duration.ofHours(2));

    assertEquals(AgentWaitState.OPEN, wait.state());
    assertNull(wait.agentId());
    assertEquals(item.workId().toString(), wait.workId());
    assertEquals(item.qualifiedId(), wait.workQualifiedId());
    AgentWait row = row(wait.id());
    assertEquals(SUBJECT, row.projectAgentSubject);
    assertEquals(item.project(), row.projectAgentProjectId);
    assertEquals(item.workId(), row.workId);
    assertEquals(item.qualifiedId(), row.workQualifiedId);
    assertEquals(SUBJECT, row.waiterSubject);
    assertNull(row.agentId);
    assertNull(row.deskProjectId);
    assertTrue(waits.selects("ProjectAgentTestBuildFinished"));

    FakeAgentWaitingReporter.Report relay = lastRelay(item);
    assertTrue(relay.waiting());
    assertEquals(AgentWaits.CAUSE_WAIT, relay.cause());
    assertEquals("run 7 green", relay.waitingOn());
    assertNull(relay.sessionId());

    // A second wait on the item: both labels, oldest first.
    declare(item, "rr 3 final", built("run-8-" + item.workId()), null);
    assertEquals("run 7 green; rr 3 final", lastRelay(item).waitingOn());

    assertEquals(2, waits.listForProjectAgent(SUBJECT, item.project(), item.workId(), AgentWaitState.OPEN).size());
    assertEquals(2, waits.listForProjectAgent(SUBJECT, item.project(), null, null).size());
    assertEquals(List.of(), waits.listForProjectAgent("tok-project-agent-other", item.project(), null, null));
    assertEquals(List.of(), waits.listForProjectAgent(SUBJECT, UUID.randomUUID(), null, null));
    assertThrows(
        NotFoundException.class,
        () -> waits.getForProjectAgent("tok-project-agent-other", item.project(), UUID.fromString(wait.id())));
  }

  @Test
  void aMatchingEventClosesItMatchedAnnouncesTheFactsAndRelaysTheItemNoLongerWaiting() {
    Item item = Item.fresh();
    String run = "run-" + item.workId();
    AgentWaitDto wait = declare(item, "run green", built(run), null);
    Instant at = Instant.parse("2036-10-26T10:00:00Z");
    String payload = "{\"runId\":\"" + run + "\",\"status\":\"SUCCESS\"}";

    assertEquals(0, waits.onEvent("ev-other", "ProjectAgentTestBuildFinished", at, "{\"runId\":\"x\",\"status\":\"SUCCESS\"}"));
    assertEquals(1, waits.onEvent("ev-pa-1", "ProjectAgentTestBuildFinished", at, payload));

    AgentWait row = row(wait.id());
    assertEquals(AgentWaitState.MATCHED, row.state);
    assertEquals("ev-pa-1", row.matchedEventId);
    assertNotNull(row.closedEventId);
    assertNull(row.closedTurn, "no turn is rendered here");
    assertNotNull(row.announcedAt, "the bus took it, so the row is marked");

    List<ProjectAgentWaitClosed> events = closedOf(wait.id());
    assertEquals(1, events.size());
    ProjectAgentWaitClosed event = events.get(0);
    assertEquals(row.closedEventId, event.eventId());
    assertEquals(item.project().toString(), event.projectId());
    assertEquals(SUBJECT, event.agentSubject());
    assertEquals(item.workId().toString(), event.workId());
    assertEquals(item.qualifiedId(), event.workQualifiedId());
    assertEquals("run green", event.label());
    assertEquals(ProjectAgentWaitClosed.MATCHED, event.state());
    assertEquals("ProjectAgentTestBuildFinished", event.eventName());
    assertEquals("ev-pa-1", event.matchedEventId());
    assertNull(event.reason());
    assertEquals(row.closedAt, event.occurredAt());
    assertTrue(
        announced.of(AgentWaitClosed.class, e -> e.waitId().equals(wait.id())).isEmpty(),
        "no agent's wait event");

    FakeAgentWaitingReporter.Report relay = lastRelay(item);
    assertFalse(relay.waiting());
    assertEquals(AgentWaits.CAUSE_MATCHED, relay.cause());
    assertNull(relay.waitingOn());

    // A repeat delivery finds it closed and announces nothing more.
    assertEquals(0, waits.onEvent("ev-pa-1", "ProjectAgentTestBuildFinished", at, payload));
    assertEquals(1, closedOf(wait.id()).size());
  }

  @Test
  void aMatchLeavesTheItemWaitingOnWhatStaysOpen() {
    Item item = Item.fresh();
    String run = "run-" + item.workId();
    declare(item, "first", built(run), null);
    declare(item, "second", built(run + "-b"), null);

    waits.onEvent(
        "ev-pa-2", "ProjectAgentTestBuildFinished", Instant.parse("2036-10-26T10:00:00Z"),
        "{\"runId\":\"" + run + "\",\"status\":\"SUCCESS\"}");

    FakeAgentWaitingReporter.Report relay = lastRelay(item);
    assertTrue(relay.waiting());
    assertEquals(AgentWaits.CAUSE_WAIT, relay.cause());
    assertEquals("second", relay.waitingOn());
  }

  @Test
  void aTimeoutSweepClosesItTimedOut() {
    Item item = Item.fresh();
    AgentWaitDto wait = declare(item, "deploy again", null, Duration.ofMinutes(30));
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                waitRows.update(
                    "createdAt = ?1, expiresAt = ?2 where id = ?3",
                    Instant.now().minus(Duration.ofMinutes(31)),
                    Instant.now().minusSeconds(1),
                    UUID.fromString(wait.id())));

    sweep();

    assertEquals(AgentWaitState.TIMED_OUT, row(wait.id()).state);
    List<ProjectAgentWaitClosed> events = closedOf(wait.id());
    assertEquals(1, events.size());
    assertEquals(ProjectAgentWaitClosed.TIMED_OUT, events.get(0).state());
    assertNull(events.get(0).eventName(), "a timer names no event");
    assertNull(events.get(0).matchedEventId());
    FakeAgentWaitingReporter.Report relay = lastRelay(item);
    assertFalse(relay.waiting());
    assertEquals(AgentWaits.CAUSE_TIMED_OUT, relay.cause());
  }

  @Test
  void aLookupThatCanNoLongerMatchClosesItBrokenWithItsReason() {
    Item item = Item.fresh();
    String request = "qits-pa-rr-" + UUID.randomUUID();
    resolver.answer(
        request,
        new WaitSubjectResolver.Resolved(
            UUID.randomUUID().toString(), request, "WITHDRAWN", Instant.parse("2026-11-08T07:05:00Z"), null));
    try {
      AgentWaitDto wait =
          declare(
              item,
              "rr final",
              EventSelection.of(
                  "ReleaseRequestStateChanged",
                  Condition.exact("releaseRequestQualifiedId", request),
                  Condition.exact("state", "FINALIZED")),
              null);

      assertEquals(AgentWaitState.BROKEN, wait.state());
      String reason = request + " is WITHDRAWN since 07:05 UTC";
      assertEquals(reason, wait.brokenReason());
      // A release request is read under the holder's own project.
      assertTrue(
          resolver.lookupsOf(request).contains("RELEASE_REQUEST " + request + " " + item.project()),
          resolver.lookupsOf(request).toString());
      List<ProjectAgentWaitClosed> events = closedOf(wait.id());
      assertEquals(1, events.size());
      assertEquals(ProjectAgentWaitClosed.BROKEN, events.get(0).state());
      assertEquals(reason, events.get(0).reason());
      FakeAgentWaitingReporter.Report relay = lastRelay(item);
      assertFalse(relay.waiting());
      assertEquals(AgentWaits.CAUSE_BROKEN, relay.cause());
    } finally {
      resolver.forget(request);
    }
  }

  @Test
  void aCancelClosesItCancelledAndAnnouncesItForTheRecord() {
    Item item = Item.fresh();
    AgentWaitDto wait = declare(item, "run x", built("run-x-" + item.workId()), null);
    UUID id = UUID.fromString(wait.id());

    assertThrows(
        NotFoundException.class, () -> waits.cancelForProjectAgent("tok-project-agent-other", item.project(), id));
    assertThrows(NotFoundException.class, () -> waits.cancelForProjectAgent(SUBJECT, UUID.randomUUID(), id));

    assertEquals(AgentWaitState.CANCELLED, waits.cancelForProjectAgent(SUBJECT, item.project(), id).state());
    assertEquals(AgentWaitState.CANCELLED, waits.cancelForProjectAgent(SUBJECT, item.project(), id).state());

    List<ProjectAgentWaitClosed> events = closedOf(wait.id());
    assertEquals(1, events.size(), "announced once, a repeated cancel says nothing");
    assertEquals(ProjectAgentWaitClosed.CANCELLED, events.get(0).state());
    assertNotNull(row(wait.id()).announcedAt);
    FakeAgentWaitingReporter.Report relay = lastRelay(item);
    assertFalse(relay.waiting());
    assertEquals(AgentWaits.CAUSE_CANCELLED, relay.cause());
    assertNull(relay.waitingOn());
  }

  @Test
  void aNinthOpenWaitOnOneItemIsRefusedWhileAnotherItemStillTakesOne() {
    Item item = Item.fresh();
    for (int i = 0; i < AgentWaits.MAX_OPEN_PER_AGENT; i++) {
      declare(item, "w" + i, EventSelection.of("ProjectAgentTestOther"), null);
    }
    ConflictException full =
        assertThrows(
            ConflictException.class,
            () -> declare(item, "one more", EventSelection.of("ProjectAgentTestOther"), null));
    assertTrue(full.getMessage().contains("8 open waits on " + item.qualifiedId()), full.getMessage());

    Item other = new Item(item.project(), UUID.randomUUID(), item.qualifiedId() + "1");
    assertDoesNotThrow(() -> declare(other, "elsewhere", EventSelection.of("ProjectAgentTestOther"), null));
  }

  @Test
  void anUnannouncedCloseIsPublishedAgainByTheSweepUnderTheSameId() {
    Item item = Item.fresh();
    AgentWaitDto wait = declare(item, "run y", built("run-y-" + item.workId()), null);
    waits.cancelForProjectAgent(SUBJECT, item.project(), UUID.fromString(wait.id()));
    UUID first = closedOf(wait.id()).get(0).eventId();
    // The bus never took it.
    QuarkusTransaction.requiringNew()
        .run(() -> waitRows.update("announcedAt = null where id = ?1", UUID.fromString(wait.id())));

    sweep();

    List<ProjectAgentWaitClosed> events = closedOf(wait.id());
    assertEquals(2, events.size());
    assertEquals(first, events.get(1).eventId());
    assertNotNull(row(wait.id()).announcedAt);
  }
}
