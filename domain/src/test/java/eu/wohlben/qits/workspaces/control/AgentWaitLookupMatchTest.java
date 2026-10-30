package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelection.Condition;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.events.AgentWaitChanged;
import eu.wohlben.qits.workspaces.events.AgentWaitClosed;
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
 * A looked-up target state closes the wait (qits-1668): a wait on a subject that reached what it
 * waits for longer ago than the look-back reaches is MATCHED by its lookup — at declaration, and on
 * a later lookup — with a wake that names no event; a final state the wait does not wait for leaves
 * it OPEN; a desk's wait records its subject, is looked up under its own project and announces its
 * close. The event log ({@link FakeRecentEvents}) is left empty, so the look-back replays nothing
 * and only the lookup can fire a wait here.
 */
@QuarkusTest
public class AgentWaitLookupMatchTest {

  @Inject AgentWaits waits;
  @Inject FakeWaitSubjectResolver resolver;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject FakeAgentWaitingReporter reporter;
  @Inject RecordingLifecycleAnnouncer announced;

  /** {@code qits wait ci-run}'s shape: the run ending either way. */
  private static EventSelection runEnds(String runId) {
    return new EventSelection(
        "BuildStatusChanged",
        List.of("SUCCESS", "FAILED").stream()
            .map(status -> List.of(Condition.exact("runId", runId), Condition.exact("status", status)))
            .toList());
  }

  /** {@code qits wait release-request}'s shape: one group per end state, each naming the request. */
  private static EventSelection requestEnds(String qualifiedId) {
    return new EventSelection(
        "ReleaseRequestStateChanged",
        List.of("FINALIZED", "REJECTED").stream()
            .map(
                state ->
                    List.of(
                        Condition.exact("releaseRequestQualifiedId", qualifiedId),
                        Condition.exact("state", state)))
            .toList());
  }

  private UUID agent() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "lookup-wrapper";
              row.workId = "w-lookup-" + id.toString().substring(0, 8);
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

  private List<AgentWaitClosed> closesOf(String waitId) {
    return announced.of(AgentWaitClosed.class, e -> e.waitId().equals(waitId));
  }

  @Test
  void aRunThatSucceededBeforeTheDeclarationMatchesTheWaitAtOnce() {
    UUID id = agent();
    String run = UUID.randomUUID().toString();
    // An hour before the declaration: far beyond the look-back.
    Instant finishedAt = Instant.now().minus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    resolver.answer(run, new WaitSubjectResolver.Resolved(run, null, "SUCCESS", finishedAt, null));
    try {
      AgentWaitDto wait = waits.create(id, "run done", runEnds(run), Duration.ofHours(1));

      assertEquals(AgentWaitState.MATCHED, wait.state());
      assertNull(wait.matchedEventId(), "no event matched it");
      assertNotNull(wait.closedAt());
      assertEquals("SUCCESS", wait.subjectState());

      String turn = row(id).pendingText;
      assertNotNull(turn);
      String since = AgentAttention.HH_MM.format(finishedAt);
      assertTrue(turn.startsWith("Your wait \"run done\" matched when its subject was looked up"), turn);
      assertTrue(
          turn.contains("run " + run.substring(0, 8) + " is already SUCCESS since " + since + " UTC"), turn);
      assertFalse(turn.contains(run.substring(9)), "the run is named by its first eight characters: " + turn);
      assertEquals(turn.indexOf("Your wait"), turn.lastIndexOf("Your wait"), "one wake: " + turn);

      List<AgentWaitClosed> closes = closesOf(wait.id());
      assertEquals(1, closes.size());
      assertEquals(AgentWaitClosed.MATCHED, closes.get(0).state());
      assertNull(closes.get(0).matchedEventId());
      List<AgentWaitChanged> changed = announced.of(AgentWaitChanged.class, e -> e.waitId().equals(wait.id()));
      assertEquals(1, changed.size());
      assertEquals("SUCCESS", changed.get(0).subjectState());

      List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor(row(id).workId);
      FakeAgentWaitingReporter.Report last = reports.get(reports.size() - 1);
      assertFalse(last.waiting());
      assertEquals(AgentWaits.CAUSE_MATCHED, last.cause());

      // A later lookup of a closed wait closes nothing again.
      assertFalse(waits.resolveSubject(UUID.fromString(wait.id())));
      assertEquals(1, closesOf(wait.id()).size());
      assertEquals(turn, row(id).pendingText);
    } finally {
      resolver.forget(run);
    }
  }

  @Test
  void aLaterLookupThatFindsATargetStateMatchesTheWait() {
    UUID id = agent();
    String run = UUID.randomUUID().toString();
    Instant startedAt = Instant.parse("2026-10-29T08:00:00Z");
    resolver.answer(run, new WaitSubjectResolver.Resolved(run, null, "RUNNING", startedAt, null));
    try {
      AgentWaitDto wait = waits.create(id, null, runEnds(run), null);
      assertEquals(AgentWaitState.OPEN, wait.state());
      assertEquals("RUNNING", wait.subjectState());
      assertNull(row(id).pendingText);
      assertTrue(closesOf(wait.id()).isEmpty());

      // A resolved wait is not among a pass's (resolveUnresolved): its next lookup is a direct one.
      Instant finishedAt = Instant.parse("2026-10-29T08:20:00Z");
      resolver.answer(run, new WaitSubjectResolver.Resolved(run, null, "FAILED", finishedAt, null));
      assertTrue(waits.resolveSubject(UUID.fromString(wait.id())));

      AgentWait closed = waitRow(wait.id());
      assertEquals(AgentWaitState.MATCHED, closed.state);
      assertNull(closed.matchedEventId);
      assertEquals("FAILED", closed.subjectState);
      String turn = row(id).pendingText;
      assertTrue(turn.contains("is already FAILED since 08:20 UTC"), turn);
      assertEquals(1, closesOf(wait.id()).size());
    } finally {
      resolver.forget(run);
    }
  }

  @Test
  void aPassThatLooksUpAFailedLookupAgainMatchesTheWait() {
    UUID id = agent();
    String run = UUID.randomUUID().toString();
    resolver.fail(run, "qits-ci did not answer.");
    try {
      AgentWaitDto wait = waits.create(id, null, runEnds(run), null);
      assertEquals(AgentWaitState.OPEN, wait.state());
      assertEquals("qits-ci did not answer.", wait.subjectError());

      // The pass (subject-retry-every is PT0S here) asks again, and the run has ended.
      resolver.answer(run, new WaitSubjectResolver.Resolved(run, null, "SUCCESS", null, null));
      waits.resolveUnresolved();

      AgentWait closed = waitRow(wait.id());
      assertEquals(AgentWaitState.MATCHED, closed.state);
      assertNull(closed.subjectError);
      String turn = row(id).pendingText;
      // No stateAt from the provider: no "since".
      assertTrue(turn.contains("is already SUCCESS, as looked up."), turn);
      assertEquals(1, closesOf(wait.id()).size());
    } finally {
      resolver.forget(run);
    }
  }

  @Test
  void aFinalStateTheWaitDoesNotWaitForLeavesItOpen() {
    UUID id = agent();
    String item = "qits-" + UUID.randomUUID();
    EventSelection verified =
        EventSelection.of(
            "EntityTransitioned",
            Condition.exact("entities[].entityId", item),
            Condition.exact("entities[].status", "VERIFIED"));
    resolver.answer(
        item, new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), item, "DROPPED", Instant.now(), null));
    String entity = "e-" + UUID.randomUUID();
    // Comments have no state: whatever a lookup says, it never closes their wait.
    resolver.answer(
        entity, new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), null, "VERIFIED", Instant.now(), null));
    try {
      AgentWaitDto wait = waits.create(id, null, verified, null);
      assertEquals("WORK_ITEM", wait.subjectKind());
      assertEquals(AgentWaitState.OPEN, wait.state());
      assertEquals("DROPPED", wait.subjectState());

      AgentWaitDto comments =
          waits.create(id, null, EventSelection.of("EntityCommented", Condition.exact("entityId", entity)), null);
      assertEquals("COMMENTS", comments.subjectKind());
      assertEquals(AgentWaitState.OPEN, comments.state());

      assertNull(row(id).pendingText);
      assertTrue(closesOf(wait.id()).isEmpty());
      assertTrue(closesOf(comments.id()).isEmpty());
    } finally {
      resolver.forget(item);
      resolver.forget(entity);
    }
  }

  @Test
  void aDeskWaitOnAFinalizedRequestMatchesAtOnceAndAnnouncesItsClose() {
    UUID project = UUID.randomUUID();
    String request = "qits-x-rr-" + UUID.randomUUID();
    Instant finalizedAt = Instant.parse("2026-10-29T07:05:00Z");
    resolver.answer(
        request,
        new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), request, "FINALIZED", finalizedAt, null));
    try {
      AgentWaitDto wait = waits.createForDesk(project, "rr ends", requestEnds(request), null, null);

      assertEquals(AgentWaitState.MATCHED, wait.state());
      assertNull(wait.matchedEventId());
      AgentWait row = waitRow(wait.id());
      assertEquals("RELEASE_REQUEST", row.subjectKind);
      assertEquals(request, row.subjectRef);
      assertEquals("[\"FINALIZED\",\"REJECTED\"]", row.targetStates);
      assertEquals("FINALIZED", row.subjectState);
      assertNotNull(row.closedEventId);
      // Under the desk's own project: there is no agent, and no repository, to find one from.
      assertEquals(List.of("RELEASE_REQUEST " + request + " " + project), resolver.lookupsOf(request));

      List<DeskWaitClosed> events = announced.of(DeskWaitClosed.class, e -> e.waitId().equals(wait.id()));
      assertEquals(1, events.size());
      DeskWaitClosed event = events.get(0);
      assertEquals("MATCHED", event.state());
      assertNull(event.matchedEventId());
      assertEquals(project.toString(), event.projectId());
      assertTrue(
          event.turn().contains(request + " is already FINALIZED since 07:05 UTC"), event.turn());
      // A desk announces none of the agent wait events.
      assertTrue(announced.of(AgentWaitChanged.class, e -> e.waitId().equals(wait.id())).isEmpty());
      assertTrue(closesOf(wait.id()).isEmpty());
    } finally {
      resolver.forget(request);
    }
  }

  @Test
  void aDeskWaitWhoseRequestIsStillOpenStaysOpen() {
    UUID project = UUID.randomUUID();
    String request = "qits-x-rr-" + UUID.randomUUID();
    resolver.answer(
        request,
        new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), request, "PENDING", Instant.now(), null));
    try {
      AgentWaitDto wait = waits.createForDesk(project, null, requestEnds(request), null, null);
      assertEquals(AgentWaitState.OPEN, wait.state());
      assertEquals("PENDING", wait.subjectState());
      assertTrue(announced.of(DeskWaitClosed.class, e -> e.waitId().equals(wait.id())).isEmpty());
      assertTrue(announced.of(AgentWaitChanged.class, e -> e.waitId().equals(wait.id())).isEmpty());
    } finally {
      resolver.forget(request);
    }
  }
}
