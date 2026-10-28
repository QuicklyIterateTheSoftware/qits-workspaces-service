package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.events.DeskWaitClosed;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A project front desk's declared waits (qits-1618) on a real database: the same limits as an
 * agent's, a match or timeout closed with the agent's turn and announced as {@link DeskWaitClosed}
 * instead of a wake, the announcement marked once taken and published again by the sweep while it
 * is not, and a desk wait never cancelled for want of an ACTIVE agent. {@link
 * RecordingLifecycleAnnouncer} takes every event as it is announced, so the mark is synchronous here.
 */
@QuarkusTest
public class DeskWaitsTest {

  @Inject AgentWaits waits;
  @Inject AgentWaitRepository waitRows;
  @Inject AgentWorktreeRepository agents;
  @Inject RecordingLifecycleAnnouncer announced;

  private static EventSelection finalized(String id) {
    return EventSelection.of("DeskTestRequestFinalized", Condition.exact("requestId", id));
  }

  private AgentWait waitRow(String id) {
    return QuarkusTransaction.requiringNew().call(() -> waitRows.findById(UUID.fromString(id)));
  }

  private List<DeskWaitClosed> closedOf(String waitId) {
    return announced.of(DeskWaitClosed.class, e -> e.waitId().equals(waitId));
  }

  private void expire(String waitId) {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                waitRows.update(
                    "createdAt = ?1, expiresAt = ?2 where id = ?3",
                    Instant.now().minus(Duration.ofHours(2)).minusSeconds(1),
                    Instant.now().minusSeconds(1),
                    UUID.fromString(waitId)));
  }

  @Test
  void aDeskDeclaresListsAndCancelsItsWaits() {
    UUID project = UUID.randomUUID();
    AgentWaitDto wait = waits.createForDesk(project, "rr 1 final", finalized("rr-1"), Duration.ofHours(2));

    assertEquals(AgentWaitState.OPEN, wait.state());
    assertNull(wait.agentId());
    assertNotNull(wait.expiresAt());
    assertTrue(waits.selects("DeskTestRequestFinalized"));
    assertEquals(1, waits.listForDesk(project, AgentWaitState.OPEN).size());
    assertEquals(List.of(), waits.listForDesk(UUID.randomUUID(), null));
    assertThrows(
        NotFoundException.class, () -> waits.cancelForDesk(UUID.randomUUID(), UUID.fromString(wait.id())));

    UUID id = UUID.fromString(wait.id());
    assertEquals(AgentWaitState.CANCELLED, waits.cancelForDesk(project, id).state());
    assertEquals(AgentWaitState.CANCELLED, waits.cancelForDesk(project, id).state());
    assertEquals(List.of(), closedOf(wait.id()), "a cancel announces nothing");
    assertNull(waitRow(wait.id()).closedEventId);
  }

  @Test
  void aDeskHoldsAtMostEightOpenWaitsAndATimeoutOfAtMostThirtyDays() {
    UUID project = UUID.randomUUID();
    for (int i = 0; i < AgentWaits.MAX_OPEN_PER_AGENT; i++) {
      waits.createForDesk(project, "w" + i, EventSelection.of("DeskTestBuildStatusChanged"), null);
    }
    ConflictException full =
        assertThrows(
            ConflictException.class,
            () -> waits.createForDesk(project, "one more", EventSelection.of("DeskTestBuildStatusChanged"), null));
    assertTrue(full.getMessage().contains("8 open waits"), full.getMessage());
    // Another project's desk is counted on its own.
    assertDoesNotThrow(
        () -> waits.createForDesk(UUID.randomUUID(), "x", EventSelection.of("DeskTestBuildStatusChanged"), null));

    assertThrows(
        BadRequestException.class,
        () -> waits.createForDesk(UUID.randomUUID(), "x", EventSelection.of("X"), Duration.ofDays(31)));
    assertThrows(
        BadRequestException.class,
        () -> waits.createForDesk(UUID.randomUUID(), "x", EventSelection.of("X"), Duration.ZERO));
    assertThrows(BadRequestException.class, () -> waits.createForDesk(UUID.randomUUID(), "x", null, null));
  }

  @Test
  void aMatchClosesTheDeskWaitAndAnnouncesTheAgentsTurn() {
    UUID project = UUID.randomUUID();
    String request = "rr-" + UUID.randomUUID();
    AgentWaitDto wait = waits.createForDesk(project, "rr final", finalized(request), null);
    // Dated after the wait: an older event would skip it (qits-1677).
    Instant at = Instant.parse("2036-10-26T10:00:00Z");
    String payload = "{\"requestId\":\"" + request + "\"}";

    assertEquals(0, waits.onEvent("ev-other", "DeskTestRequestFinalized", at, "{\"requestId\":\"rr-x\"}"));
    assertEquals(1, waits.onEvent("ev-desk-1", "DeskTestRequestFinalized", at, payload));

    AgentWait row = waitRow(wait.id());
    assertEquals(AgentWaitState.MATCHED, row.state);
    assertEquals("ev-desk-1", row.matchedEventId);
    assertNotNull(row.closedEventId);
    assertNotNull(row.announcedAt, "the bus took it, so the row is marked");

    List<DeskWaitClosed> events = closedOf(wait.id());
    assertEquals(1, events.size());
    DeskWaitClosed event = events.get(0);
    assertEquals(row.closedEventId, event.eventId());
    assertEquals(project.toString(), event.projectId());
    assertEquals("rr final", event.label());
    assertEquals("MATCHED", event.state());
    assertEquals("DeskTestRequestFinalized", event.eventName());
    assertEquals("ev-desk-1", event.matchedEventId());
    assertEquals(row.closedAt.toString(), event.closedAt());
    assertEquals(row.closedAt, event.occurredAt());
    assertEquals(
        AgentWaits.matchedTurn(
            "rr final",
            finalized(request),
            "ev-desk-1",
            at,
            eu.wohlben.qits.eventstream.selection.EventSelectionEvaluator.parsePayload(payload)),
        event.turn());
    assertEquals(row.closedTurn, event.turn());
    assertTrue(event.turn().startsWith("Your wait \"rr final\" matched: DeskTestRequestFinalized at 2036-10-26T10:00:00Z"));

    // A repeat delivery finds the wait closed and announces nothing more.
    assertEquals(0, waits.onEvent("ev-desk-1", "DeskTestRequestFinalized", at, payload));
    assertEquals(1, closedOf(wait.id()).size());
  }

  @Test
  void aTimeoutClosesTheDeskWaitAndAnnouncesIt() {
    UUID project = UUID.randomUUID();
    AgentWaitDto wait =
        waits.createForDesk(project, "ci run 9", EventSelection.of("DeskTestBuildStatusChanged"), Duration.ofHours(2));
    expire(wait.id());

    waits.sweep();

    AgentWait row = waitRow(wait.id());
    assertEquals(AgentWaitState.TIMED_OUT, row.state);
    List<DeskWaitClosed> events = closedOf(wait.id());
    assertEquals(1, events.size());
    DeskWaitClosed event = events.get(0);
    assertEquals("TIMED_OUT", event.state());
    assertNull(event.matchedEventId());
    assertEquals(
        "Your wait \"ci run 9\" timed out after 2h: no DeskTestBuildStatusChanged event matched it. It is"
            + " closed; declare a new one if you still need to wait.",
        event.turn());
    assertEquals(row.closedEventId, event.eventId());
    assertNotNull(row.announcedAt);
  }

  @Test
  void theSweepNeverCancelsADeskWait() {
    AgentWaitDto wait =
        waits.createForDesk(UUID.randomUUID(), "open", EventSelection.of("DeskTestBuildStatusChanged"), null);

    waits.sweep();
    waits.sweep();

    assertEquals(AgentWaitState.OPEN, waitRow(wait.id()).state);
    assertEquals(List.of(), closedOf(wait.id()));
  }

  @Test
  void anAnnouncementTheBusNeverTookIsPublishedAgainUnderTheSameId() {
    AgentWaitDto wait =
        waits.createForDesk(UUID.randomUUID(), "again", EventSelection.of("DeskTestBuildStatusChanged"), Duration.ofHours(2));
    expire(wait.id());
    waits.sweep();
    UUID eventId = waitRow(wait.id()).closedEventId;
    // As if the process stopped before the bus took it.
    QuarkusTransaction.requiringNew()
        .run(() -> waitRows.update("announcedAt = null where id = ?1", UUID.fromString(wait.id())));

    waits.sweep();

    List<DeskWaitClosed> events = closedOf(wait.id());
    assertEquals(2, events.size());
    assertEquals(eventId, events.get(1).eventId());
    assertEquals(events.get(0), events.get(1), "the same facts, under the same id");
    assertNotNull(waitRow(wait.id()).announcedAt);

    waits.sweep();
    assertEquals(2, closedOf(wait.id()).size(), "a marked row is not published again");
  }

  @Test
  void anAgentsWaitIsNeitherAnnouncedNorSeenByTheDesk() {
    UUID agentId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = agentId;
              row.repositoryId = "desk-waits-wrapper";
              row.workId = "w-" + agentId.toString().substring(0, 8);
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + agentId;
              agents.persist(row);
            });
    String request = "rr-" + UUID.randomUUID();
    AgentWaitDto wait = waits.create(agentId, "agent's", finalized(request), null);
    assertThrows(
        NotFoundException.class, () -> waits.getForDesk(UUID.randomUUID(), UUID.fromString(wait.id())));

    assertEquals(
        1,
        waits.onEvent(
            "ev-agent-1", "DeskTestRequestFinalized", Instant.now(), "{\"requestId\":\"" + request + "\"}"));

    AgentWait row = waitRow(wait.id());
    assertEquals(AgentWaitState.MATCHED, row.state);
    assertNull(row.closedEventId);
    assertNull(row.closedTurn);
    assertEquals(List.of(), closedOf(wait.id()));
    String pending = QuarkusTransaction.requiringNew().call(() -> agents.findById(agentId).pendingText);
    assertTrue(pending.startsWith("Your wait \"agent's\" matched: DeskTestRequestFinalized"), pending);
  }
}
