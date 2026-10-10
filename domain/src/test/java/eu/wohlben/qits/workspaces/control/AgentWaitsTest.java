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
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentWaits} (qits-1153) on a real database: declaring, matching an event, the timeout and
 * the cancel sweeps, the cap, the own-agent rule (W4) and "waiting on" (W5). Agents are inserted as
 * rows with no slot, so nothing is said to a daemon and a wake stays in {@code pending_text}.
 */
@QuarkusTest
public class AgentWaitsTest {

  @Inject AgentWaits waits;
  @Inject AgentService service;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject FakeAgentWaitingReporter reporter;
  @Inject FakeAgentDaemon daemon;
  @Inject FakeRepositoryLookup repositories;

  private static EventSelection verified() {
    return EventSelection.of(
        "EntityTransitioned",
        Condition.exact("entities[].entityId", "e-1"),
        Condition.exact("entities[].status", "VERIFIED"));
  }

  @BeforeEach
  void setUp() {
    daemon.reset();
  }

  private UUID agent(String workId) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "waits-wrapper";
              row.workId = workId + "-" + id.toString().substring(0, 8);
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              agents.persist(row);
            });
    return id;
  }

  private AgentWorktree row(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
  }

  private AgentWait waitRow(String id) {
    return QuarkusTransaction.requiringNew().call(() -> waitRows.findById(UUID.fromString(id)));
  }

  @Test
  void aMatchingEventClosesTheWaitAndGivesTheAgentATurn() {
    UUID id = agent("w-match");
    AgentWaitDto wait = waits.create(id, "ticket e-1 verified", verified(), null);
    assertEquals(AgentWaitState.OPEN, wait.state());
    assertTrue(waits.selects("EntityTransitioned"));
    assertEquals("ticket e-1 verified", service.get(id).waitingOn());

    // Another entity, then the same entity in another status: no match.
    assertEquals(
        0,
        waits.onEvent(
            "ev-1",
            "EntityTransitioned",
            Instant.now(),
            "{\"entities\":[{\"entityId\":\"e-2\",\"status\":\"VERIFIED\"}]}"));
    assertEquals(
        0,
        waits.onEvent(
            "ev-2",
            "EntityTransitioned",
            Instant.now(),
            "{\"entities\":[{\"entityId\":\"e-1\",\"status\":\"REFINED\"}]}"));
    assertNull(row(id).pendingText);

    String payload = "{\"entities\":[{\"entityId\":\"e-1\",\"status\":\"VERIFIED\"}]}";
    assertEquals(1, waits.onEvent("ev-3", "EntityTransitioned", Instant.parse("2026-10-10T10:00:00Z"), payload));

    AgentWait closed = waitRow(wait.id());
    assertEquals(AgentWaitState.MATCHED, closed.state);
    assertEquals("ev-3", closed.matchedEventId);
    assertNotNull(closed.closedAt);
    String turn = row(id).pendingText;
    assertTrue(turn.contains("\"ticket e-1 verified\" matched: EntityTransitioned"), turn);
    assertTrue(turn.contains("2026-10-10T10:00:00Z"), turn);
    assertTrue(turn.contains(payload), turn);
    assertNull(service.get(id).waitingOn());

    // A second delivery of the same event changes nothing.
    assertEquals(0, waits.onEvent("ev-3", "EntityTransitioned", Instant.now(), payload));
    FakeAgentWaitingReporter.Report last =
        reporter.reportsFor(row(id).workId).get(reporter.reportsFor(row(id).workId).size() - 1);
    assertFalse(last.waiting());
    assertEquals(AgentWaits.CAUSE_MATCHED, last.cause());
  }

  @Test
  void aTimedOutWaitWakesTheAgentAndANoLongerActiveAgentsWaitsAreCancelled() {
    UUID timed = agent("w-timeout");
    AgentWaitDto wait = waits.create(timed, null, verified(), Duration.ofHours(2));
    assertNotNull(wait.expiresAt());
    assertTrue(wait.label().startsWith("EntityTransitioned when"), wait.label());
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                waitRows.update(
                    "createdAt = ?1, expiresAt = ?2 where id = ?3",
                    Instant.now().minus(Duration.ofHours(2)).minusSeconds(1),
                    Instant.now().minusSeconds(1),
                    UUID.fromString(wait.id())));

    UUID obsolete = agent("w-obsolete");
    AgentWaitDto dropped = waits.create(obsolete, "ci run 7", EventSelection.of("BuildStatusChanged"), null);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(obsolete).state = eu.wohlben.qits.workspaces.entity.AgentState.OBSOLETE);

    waits.sweep();

    assertEquals(AgentWaitState.TIMED_OUT, waitRow(wait.id()).state);
    assertTrue(row(timed).pendingText.contains("timed out after 2h"), row(timed).pendingText);
    assertEquals(AgentWaitState.CANCELLED, waitRow(dropped.id()).state);
    assertNull(row(obsolete).pendingText, "a cancelled wait gives no turn");
  }

  @Test
  void anAgentHoldsAtMostEightOpenWaitsAndACancelIsIdempotent() {
    UUID id = agent("w-cap");
    AgentWaitDto first = null;
    for (int i = 0; i < AgentWaits.MAX_OPEN_PER_AGENT; i++) {
      AgentWaitDto made = waits.create(id, "w" + i, EventSelection.of("BuildStatusChanged"), null);
      first = first == null ? made : first;
    }
    ConflictException full =
        assertThrows(
            ConflictException.class,
            () -> waits.create(id, "one more", EventSelection.of("BuildStatusChanged"), null));
    assertTrue(full.getMessage().contains("8 open waits"), full.getMessage());

    UUID firstId = UUID.fromString(first.id());
    assertEquals(AgentWaitState.CANCELLED, waits.cancel(id, firstId).state());
    assertEquals(AgentWaitState.CANCELLED, waits.cancel(id, firstId).state());
    assertDoesNotThrow(() -> waits.create(id, "now it fits", EventSelection.of("BuildStatusChanged"), null));
    assertEquals(9, waits.list(id, null).size());
    assertEquals(8, waits.list(id, AgentWaitState.OPEN).size());
  }

  @Test
  void onlyTheAgentsOwnCredentialMayActOnItsWaits() {
    UUID id = agent("w-own");
    assertDoesNotThrow(() -> waits.requireOwnAgent(id, "tok-workspace-agent-" + id));
    assertThrows(ForbiddenException.class, () -> waits.requireOwnAgent(id, "tok-workspace-agent-other"));
    assertThrows(ForbiddenException.class, () -> waits.requireOwnAgent(id, null));
  }

  @Test
  void aWaitingAgentWithAnOpenWaitIsReportedAsWaitingOnIt() {
    UUID id = agent("w-relay");
    String workId = row(id).workId;
    waits.create(id, "release-request 42", EventSelection.of("ReleaseRequestStateChanged"), null);

    service.onActivity(id.toString(), Boolean.TRUE, "s-1", "Stop", System.currentTimeMillis());

    FakeAgentWaitingReporter.Report report = reporter.reportsFor(workId).get(0);
    assertTrue(report.waiting());
    assertEquals(AgentWaits.CAUSE_WAIT, report.cause());
    assertEquals("release-request 42", report.waitingOn());
  }

  @Test
  void anAgentWaitingOnAWaitYieldsAtOnceWhenAnotherAgentWantsTheSlot() {
    String repoId = "waits-admin-" + UUID.randomUUID().toString().substring(0, 8);
    repositories.register(repoId, "main");
    AgentService.Dispatch dispatched =
        service.dispatch(
            new AgentService.AgentRequest(
                repoId,
                "ticket/w-yield",
                "w-yield-" + UUID.randomUUID(),
                null,
                null,
                null,
                true,
                null,
                null,
                null));
    UUID id = UUID.fromString(dispatched.agent().agentId());
    Long slot = dispatched.agent().workspaceRowId();
    UUID queued = UUID.randomUUID();
    AgentWorktree placed = row(id);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              // A yielded agent of the same slot with something to hear wants it back.
              AgentWorktree other = new AgentWorktree();
              other.id = queued;
              other.repositoryId = placed.repositoryId;
              other.workId = "w-yield-other-" + queued;
              other.wrapperBranch = "ticket/w-yield-other";
              other.admin = true;
              other.workspaceId = slot;
              other.runState = AgentRunState.YIELDED;
              other.pendingText = "your answer";
              other.createdAt = Instant.now();
              other.gitRefs = "[]";
              agents.persist(other);
              // Waiting for input just now: well inside the yield time.
              agents.findById(id).waitingSince = Instant.now();
            });
    waits.create(id, "ci run 9", EventSelection.of("BuildStatusChanged"), null);

    service.yieldBlocked();

    assertEquals(AgentRunState.YIELDED, row(id).runState, "slot " + slot);
  }

  @Test
  void theWakeQuotesABoundedPayload() {
    String turn =
        AgentWaits.matchedTurn("l", "E", "id", Instant.EPOCH, "{\"x\":\"" + "a".repeat(5000) + "\"}");
    assertTrue(turn.length() < 1200, String.valueOf(turn.length()));
    assertTrue(turn.endsWith("…"));
  }

  @Test
  void anUnknownAgentIsNotFound() {
    assertThrows(
        eu.wohlben.qits.workspaces.error.NotFoundException.class,
        () -> waits.list(UUID.randomUUID(), null));
    assertEquals(List.of(), waits.list(agent("w-empty"), null));
  }
}
