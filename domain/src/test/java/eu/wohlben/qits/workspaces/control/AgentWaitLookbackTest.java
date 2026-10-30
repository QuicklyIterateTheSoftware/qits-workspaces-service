package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The look-back (qits-1668): a wait declared after its event happened fires at once; an event older
 * than {@code qits.workspaces.waits.lookback} (15 minutes) does not fire it; a log that cannot be
 * read leaves the wait OPEN.
 *
 * <p>And only what still holds fires it (qits-1677): of the events before the declaration, only the
 * latest about the wait's stateful subject may match; a comment, or an event of a selection naming
 * no subject, must not be older than the wait — on the look-back and on the live path alike.
 */
@QuarkusTest
public class AgentWaitLookbackTest {

  /** A run's status: the look-back replays only the latest of its events (qits-1677). */
  private static final String EVENT = "BuildStatusChanged";

  private static final String REQUEST = "ReleaseRequestStateChanged";

  private static final String COMMENTED = "EntityCommented";

  /** A name no subject is parsed from: only events at or after the wait count (qits-1677). */
  private static final String OTHER = "LookbackTestOtherHappened";

  @Inject AgentWaits waits;
  @Inject FakeWaitSubjectResolver resolver;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject FakeRecentEvents log;

  @AfterEach
  void tearDown() {
    log.reset();
  }

  private static EventSelection failed(String runId) {
    return EventSelection.of(EVENT, Condition.exact("runId", runId), Condition.exact("status", "FAILED"));
  }

  /** A unique run id: the wait table is shared with every other test of the module. */
  private static String run(String name) {
    return name + "-" + UUID.randomUUID();
  }

  /** {@code qits wait release-request}'s shape: one group per end state, each naming the request. */
  private static EventSelection requestEnds(String qualifiedId) {
    return new EventSelection(
        REQUEST,
        List.of("FINALIZED", "REJECTED", "FAILED", "WITHDRAWN", "OBSOLETE").stream()
            .map(
                state ->
                    List.of(
                        Condition.exact("releaseRequestQualifiedId", qualifiedId),
                        Condition.exact("state", state)))
            .toList());
  }

  private static String requestPayload(String qualifiedId, String state) {
    return "{\"releaseRequestQualifiedId\":\"" + qualifiedId + "\",\"state\":\"" + state + "\"}";
  }

  private static String payload(String runId, String status) {
    return "{\"runId\":\"" + runId + "\",\"status\":\"" + status + "\"}";
  }

  private UUID agent() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "lookback-wrapper";
              row.workId = "w-lookback-" + id.toString().substring(0, 8);
              row.wrapperBranch = "ticket/" + row.workId;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              agents.persist(row);
            });
    return id;
  }

  private AgentWait waitRow(String id) {
    return QuarkusTransaction.requiringNew().call(() -> waitRows.findById(UUID.fromString(id)));
  }

  @Test
  void aWaitDeclaredAfterItsEventFiresAtOnce() {
    UUID id = agent();
    String run = run("run-1");
    Instant failedAt = Instant.now().minusSeconds(31);
    log.add("ev-other", EVENT, failedAt.minusSeconds(5), payload(run("run-0"), "FAILED"));
    log.add("ev-failed", EVENT, failedAt, payload(run, "FAILED"));
    // Another run's later event does not supersede this one's (qits-1677).
    log.add("ev-later", EVENT, failedAt.plusSeconds(5), payload(run("run-0"), "RUNNING"));

    AgentWaitDto wait = waits.create(id, "run-1 failed", failed(run), Duration.ofHours(1));

    assertEquals(AgentWaitState.MATCHED, wait.state());
    assertEquals("ev-failed", waitRow(wait.id()).matchedEventId);
    String turn = QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText);
    assertTrue(turn.contains("\"run-1 failed\" matched: " + EVENT), turn);
    Duration askedBefore = Duration.between(log.asked().get(0), wait.createdAt());
    assertTrue(Duration.ofMinutes(15).minusMillis(1).compareTo(askedBefore) <= 0, askedBefore::toString);
    assertTrue(askedBefore.compareTo(Duration.ofMinutes(15).plusMillis(1)) <= 0, askedBefore::toString);
  }

  @Test
  void anEventOlderThanTheLookbackDoesNotFireTheWait() {
    UUID id = agent();
    String run = run("run-2");
    log.add("ev-old", EVENT, Instant.now().minus(Duration.ofMinutes(16)), payload(run, "FAILED"));

    AgentWaitDto wait = waits.create(id, null, failed(run), Duration.ofHours(1));

    assertEquals(AgentWaitState.OPEN, wait.state());
    assertNull(QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText));
    // A later event still matches it, as before.
    assertEquals(1, waits.onEvent("ev-new", EVENT, Instant.now(), payload(run, "FAILED")));
    assertEquals(AgentWaitState.MATCHED, waitRow(wait.id()).state);
  }

  @Test
  void aDeskWaitDeclaredAfterItsEventFiresAtOnce() {
    UUID project = UUID.randomUUID();
    String run = run("run-3");
    log.add("ev-desk", EVENT, Instant.now().minusSeconds(10), payload(run, "FAILED"));

    AgentWaitDto wait = waits.createForDesk(project, "run-3 failed", failed(run), null, null);

    assertEquals(AgentWaitState.MATCHED, wait.state());
    assertEquals("ev-desk", waitRow(wait.id()).matchedEventId);
  }

  @Test
  void aLogThatCannotBeReadLeavesTheWaitOpen() {
    UUID id = agent();
    log.failWith(new IllegalStateException("qits-events answered 503"));

    AgentWaitDto wait = waits.create(id, null, failed(run("run-4")), null);

    assertEquals(AgentWaitState.OPEN, wait.state());
  }

  // --- qits-1677: only what still holds ----------------------------------------------------------

  @Test
  void aRequestThatMovedOnFromItsEndStateDoesNotFireTheWait() {
    UUID id = agent();
    String request = "qits-x-rr-" + UUID.randomUUID();
    Instant now = Instant.now();
    log.add("rr-rejected", REQUEST, now.minusSeconds(60), requestPayload(request, "REJECTED"));
    log.add("rr-pending", REQUEST, now.minusSeconds(30), requestPayload(request, "PENDING"));

    AgentWaitDto agentWait = waits.create(id, null, requestEnds(request), Duration.ofHours(1));
    AgentWaitDto deskWait = waits.createForDesk(UUID.randomUUID(), null, requestEnds(request), null, null);

    assertEquals("RELEASE_REQUEST", waitRow(agentWait.id()).subjectKind);
    assertEquals(AgentWaitState.OPEN, agentWait.state());
    assertEquals(AgentWaitState.OPEN, deskWait.state());
    assertNull(QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText));
  }

  @Test
  void theLatestEventFindsTheSubjectByTheIdItsLookupResolved() {
    UUID id = agent();
    String request = "qits-x-rr-" + UUID.randomUUID();
    String uuid = UUID.randomUUID().toString();
    resolver.answer(request, new WaitSubjectResolver.Resolved(uuid, request, null, null, null));
    try {
      Instant now = Instant.now();
      log.add("rr-rejected", REQUEST, now.minusSeconds(60), requestPayload(request, "REJECTED"));
      // The newest event names the request by its uuid only, and supersedes the rejection.
      log.add(
          "rr-pending",
          REQUEST,
          now.minusSeconds(30),
          "{\"releaseRequestId\":\"" + uuid + "\",\"state\":\"PENDING\"}");

      AgentWaitDto wait = waits.create(id, null, requestEnds(request), Duration.ofHours(1));

      assertEquals(uuid, waitRow(wait.id()).subjectId);
      assertEquals(AgentWaitState.OPEN, wait.state());
    } finally {
      resolver.forget(request);
    }
  }

  @Test
  void aCommentFromBeforeTheWaitDoesNotFireIt() {
    UUID id = agent();
    String entity = "e-" + UUID.randomUUID();
    log.add("c-old", COMMENTED, Instant.now().minusSeconds(3), "{\"entityId\":\"" + entity + "\"}");
    EventSelection commented = EventSelection.of(COMMENTED, Condition.exact("entityId", entity));

    AgentWaitDto agentWait = waits.create(id, null, commented, Duration.ofHours(1));
    AgentWaitDto deskWait = waits.createForDesk(UUID.randomUUID(), null, commented, null, null);

    assertEquals("COMMENTS", waitRow(agentWait.id()).subjectKind);
    assertEquals(AgentWaitState.OPEN, agentWait.state());
    assertEquals(AgentWaitState.OPEN, deskWait.state());
    // The next comment does.
    assertEquals(
        2, waits.onEvent("c-new", COMMENTED, Instant.now(), "{\"entityId\":\"" + entity + "\"}"));
    assertEquals(AgentWaitState.MATCHED, waitRow(agentWait.id()).state);
    assertEquals(AgentWaitState.MATCHED, waitRow(deskWait.id()).state);
  }

  @Test
  void aLookedBackCommentFiresOnlyTheWaitsThatDidNotWriteIt() {
    UUID id = agent();
    String subject = "tok-workspace-agent-" + id;
    String desk = "tok-desk-" + UUID.randomUUID();
    String entity = "e-" + UUID.randomUUID();
    // Logged at the declaration's own instant or later, as a comment written right after it would be.
    Instant soon = Instant.now().plusSeconds(5);
    log.add(
        "c-agent", COMMENTED, soon, "{\"entityId\":\"" + entity + "\",\"changedBy\":\"" + subject + "\"}");
    log.add(
        "c-desk", COMMENTED, soon, "{\"entityId\":\"" + entity + "\",\"changedBy\":\"" + desk + "\"}");
    EventSelection commented = EventSelection.of(COMMENTED, Condition.exact("entityId", entity));

    AgentWaitDto agentWait = waits.create(id, null, commented, Duration.ofHours(1));
    AgentWaitDto deskWait = waits.createForDesk(UUID.randomUUID(), null, commented, null, desk);

    // Each skips its own comment (qits-1759) and takes the other's.
    assertEquals(AgentWaitState.MATCHED, agentWait.state());
    assertEquals("c-desk", waitRow(agentWait.id()).matchedEventId);
    assertEquals(AgentWaitState.MATCHED, deskWait.state());
    assertEquals("c-agent", waitRow(deskWait.id()).matchedEventId);
  }

  @Test
  void theWaitersOwnLookedBackCommentLeavesItsWaitOpen() {
    UUID id = agent();
    String subject = "tok-workspace-agent-" + id;
    String entity = "e-" + UUID.randomUUID();
    log.add(
        "c-own",
        COMMENTED,
        Instant.now().plusSeconds(5),
        "{\"entityId\":\"" + entity + "\",\"changedBy\":\"" + subject + "\"}");

    AgentWaitDto wait =
        waits.create(id, null, EventSelection.of(COMMENTED, Condition.exact("entityId", entity)), Duration.ofHours(1));

    assertEquals(AgentWaitState.OPEN, wait.state());
    assertNull(QuarkusTransaction.requiringNew().call(() -> agents.findById(id).pendingText));
  }

  @Test
  void aSelectionNamingNoSubjectReplaysNothingOlderThanTheWait() {
    UUID id = agent();
    String state = "RELEASED-" + UUID.randomUUID();
    log.add(
        "rr-old",
        REQUEST,
        Instant.now().minus(Duration.ofMinutes(10)),
        "{\"releaseRequestQualifiedId\":\"qits-x-rr-any\",\"state\":\"" + state + "\"}");

    AgentWaitDto wait =
        waits.create(id, null, EventSelection.of(REQUEST, Condition.exact("state", state)), Duration.ofHours(1));

    assertEquals("OTHER", waitRow(wait.id()).subjectKind);
    assertEquals(AgentWaitState.OPEN, wait.state());
  }

  @Test
  void aRunsLatestEarlierEventFiresTheWaitAtOnce() {
    UUID id = agent();
    String run = run("run-5");
    Instant now = Instant.now();
    log.add("r-running", EVENT, now.minusSeconds(120), payload(run, "RUNNING"));
    log.add("r-failed", EVENT, now.minusSeconds(60), payload(run, "FAILED"));

    AgentWaitDto agentWait = waits.create(id, null, failed(run), Duration.ofHours(1));
    AgentWaitDto deskWait = waits.createForDesk(UUID.randomUUID(), null, failed(run), null, null);

    assertEquals(AgentWaitState.MATCHED, agentWait.state());
    assertEquals("r-failed", waitRow(agentWait.id()).matchedEventId);
    assertEquals(AgentWaitState.MATCHED, deskWait.state());
    assertEquals("r-failed", waitRow(deskWait.id()).matchedEventId);
  }

  @Test
  void aDeliveredEventOlderThanTheWaitDoesNotMatchIt() {
    UUID id = agent();
    String key = "k-" + UUID.randomUUID();
    String payload = "{\"key\":\"" + key + "\"}";
    AgentWaitDto wait =
        waits.create(id, null, EventSelection.of(OTHER, Condition.exact("key", key)), Duration.ofHours(1));
    Instant createdAt = waitRow(wait.id()).createdAt;

    assertEquals(0, waits.onEvent("o-old", OTHER, createdAt.minusSeconds(1), payload));
    assertEquals(AgentWaitState.OPEN, waitRow(wait.id()).state);
    // The wait's own instant is not before it.
    assertEquals(1, waits.onEvent("o-new", OTHER, createdAt, payload));
    assertEquals(AgentWaitState.MATCHED, waitRow(wait.id()).state);
    assertEquals("o-new", waitRow(wait.id()).matchedEventId);
  }

  @Test
  void aDeliveredEventAfterTheWaitMatchesIt() {
    UUID id = agent();
    String key = "k-" + UUID.randomUUID();
    AgentWaitDto wait =
        waits.create(id, null, EventSelection.of(OTHER, Condition.exact("key", key)), Duration.ofHours(1));

    assertEquals(
        1,
        waits.onEvent("o-after", OTHER, waitRow(wait.id()).createdAt.plusSeconds(1), "{\"key\":\"" + key + "\"}"));
    assertEquals(AgentWaitState.MATCHED, waitRow(wait.id()).state);
  }

  @Test
  void anEventWithinTheWaitsOwnMicrosecondIsNotBeforeIt() {
    Instant createdAt = Instant.parse("2026-10-28T10:00:00.000001700Z");
    assertTrue(AgentWaits.occurredBefore(Instant.parse("2026-10-28T10:00:00.000000999Z"), createdAt));
    assertTrue(!AgentWaits.occurredBefore(Instant.parse("2026-10-28T10:00:00.000001000Z"), createdAt));
    assertTrue(!AgentWaits.occurredBefore(Instant.parse("2026-10-28T10:00:00.000001700Z"), createdAt));
    // The row's rounded microsecond is no later than an event at the declaration's own instant.
    assertTrue(
        !AgentWaits.occurredBefore(
            Instant.parse("2026-10-28T10:00:00.000001700Z"), Instant.parse("2026-10-28T10:00:00.000002Z")));
    assertTrue(!AgentWaits.occurredBefore(null, createdAt));
  }
}
