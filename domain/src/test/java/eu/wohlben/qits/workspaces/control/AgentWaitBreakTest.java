package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import eu.wohlben.qits.workspaces.events.AgentWaitClosed;
import eu.wohlben.qits.workspaces.events.DeskWaitClosed;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A wait that can no longer be satisfied is closed BROKEN (qits-1961), and every wait ends: an
 * agent's and a desk's broken by their lookup — final, stuck past the grace, a 404, a branch the
 * ledger does not follow — with the platform's turn; the 8-hour ceiling and the 24-hour cap; and the
 * re-check that reads an OPEN wait's subject again. The replays of qits-1952 are here: a BRANCH wait
 * on a maintenance branch with no ledger row, and an {@code --until live} wait with no early groups
 * on a branch FAILED in a REJECTED request.
 */
@QuarkusTest
public class AgentWaitBreakTest {

  @Inject AgentWaits waits;
  @Inject FakeWaitSubjectResolver resolver;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentWaitRepository waitRows;
  @Inject FakeAgentWaitingReporter reporter;
  @Inject RecordingLifecycleAnnouncer announced;

  private UUID agent() {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = "break-wrapper";
              row.workId = "w-break-" + id.toString().substring(0, 8);
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

  private void update(String waitId, Consumer<AgentWait> change) {
    QuarkusTransaction.requiringNew().run(() -> change.accept(waitRows.findById(UUID.fromString(waitId))));
  }

  /** {@code created_at} is not updatable through the entity: a bulk update moves it. */
  private void declaredAt(String waitId, Instant createdAt) {
    QuarkusTransaction.requiringNew()
        .run(() -> waitRows.update("createdAt = ?1 where id = ?2", createdAt, UUID.fromString(waitId)));
  }

  private List<AgentWaitClosed> closesOf(String waitId) {
    return announced.of(AgentWaitClosed.class, e -> e.waitId().equals(waitId));
  }

  private static EventSelection itemVerified(String item) {
    return EventSelection.of(
        "EntityTransitioned",
        Condition.exact("entities[].entityId", item),
        Condition.exact("entities[].status", "VERIFIED"));
  }

  private static EventSelection requestFinalized(String qualifiedId) {
    return EventSelection.of(
        "ReleaseRequestStateChanged",
        Condition.exact("releaseRequestQualifiedId", qualifiedId),
        Condition.exact("state", "FINALIZED"));
  }

  /** {@code qits wait branch <branch> --until live}: everywhere LIVE, and the early step groups. */
  private static EventSelection untilLive(String branch, boolean earlyGroups) {
    List<List<Condition>> groups = new ArrayList<>();
    groups.add(List.of(Condition.exact("branch", branch), Condition.exact("everywhere", "LIVE")));
    if (earlyGroups) {
      for (String step : List.of("FAILED", "CONFLICTED", "WITHDRAWN")) {
        groups.add(List.of(Condition.exact("branch", branch), Condition.exact("step", step)));
      }
    }
    return new EventSelection("BranchDeliveryChanged", groups);
  }

  @Test
  void aFinalSubjectThatIsNoTargetBreaksTheAgentsWaitAtDeclarationWithTheTurn() {
    UUID id = agent();
    String item = "qits-" + UUID.randomUUID();
    Instant droppedAt = Instant.parse("2026-11-08T09:41:00Z");
    resolver.answer(
        item, new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), item, "DROPPED", droppedAt, null));
    try {
      AgentWaitDto wait = waits.create(id, "item verified", itemVerified(item), null);

      assertEquals(AgentWaitState.BROKEN, wait.state());
      String reason = item + " is DROPPED since 09:41 UTC";
      assertEquals(reason, wait.brokenReason());
      assertTrue(wait.closedAt() != null);
      assertNull(wait.matchedEventId());
      assertEquals(
          "Your wait \"item verified\" can no longer be satisfied and is closed (BROKEN): "
              + reason
              + ". It waited for VERIFIED. Re-plan: fix and push, or declare a wait on what you need now.",
          row(id).pendingText);

      List<AgentWaitClosed> closes = closesOf(wait.id());
      assertEquals(1, closes.size());
      assertEquals(AgentWaitClosed.BROKEN, closes.get(0).state());
      assertEquals("item verified", closes.get(0).label());
      assertEquals(reason, closes.get(0).reason());

      List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor(row(id).workId);
      assertEquals(AgentWaits.CAUSE_BROKEN, reports.get(reports.size() - 1).cause());
      assertFalse(reports.get(reports.size() - 1).waiting());
      // The list door's state filter takes it.
      assertEquals(List.of(wait.id()), waits.list(id, AgentWaitState.BROKEN).stream().map(AgentWaitDto::id).toList());
    } finally {
      resolver.forget(item);
    }
  }

  @Test
  void aStuckRequestBreaksTheWaitOnlyOnceItStoodThirtyMinutes() {
    UUID id = agent();
    String request = "qits-x-rr-" + UUID.randomUUID();
    Instant rejectedAt = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
    resolver.answer(
        request,
        new WaitSubjectResolver.Resolved(
            UUID.randomUUID().toString(), request, "REJECTED", rejectedAt, null, "Automation golden-recordings failed", null));
    try {
      AgentWaitDto wait = waits.create(id, null, requestFinalized(request), null);
      assertEquals(AgentWaitState.OPEN, wait.state(), "a REJECTED request may reopen on a push");
      assertEquals("REJECTED", wait.subjectState());

      // Forty minutes on, as the row knows it, and the re-check reads it again: still REJECTED.
      update(
          wait.id(),
          w -> {
            w.subjectStateAt = Instant.now().minus(Duration.ofMinutes(40));
            w.subjectResolvedAt = Instant.now().minus(Duration.ofDays(1));
          });
      waits.resolveUnresolved();

      AgentWait broken = waitRow(wait.id());
      assertEquals(AgentWaitState.BROKEN, broken.state);
      assertTrue(broken.brokenReason.startsWith(request + " is REJECTED since "), broken.brokenReason);
      assertTrue(
          broken.brokenReason.endsWith(": \"Automation golden-recordings failed\""), broken.brokenReason);
      assertTrue(row(id).pendingText.contains("It waited for FINALIZED."), row(id).pendingText);
    } finally {
      resolver.forget(request);
    }
  }

  @Test
  void a404BreaksTheWaitAndAnyOtherFailureLeavesItOpen() {
    UUID id = agent();
    String gone = "qits-" + UUID.randomUUID();
    String down = "qits-" + UUID.randomUUID();
    resolver.notFound(gone, "qits-projects knows no work item " + gone + ".");
    resolver.fail(down, "qits-projects answered 503 for the work item " + down + ".");
    try {
      AgentWaitDto missing = waits.create(id, "gone verified", itemVerified(gone), null);
      assertEquals(AgentWaitState.BROKEN, missing.state());
      assertEquals(gone + " does not exist: qits-projects knows no work item " + gone, missing.brokenReason());
      assertTrue(row(id).pendingText.contains("Re-plan: check the id you waited on"), row(id).pendingText);

      AgentWaitDto unanswered = waits.create(id, "down verified", itemVerified(down), null);
      assertEquals(AgentWaitState.OPEN, unanswered.state(), "a 5xx is retried, never a break");
      assertNull(unanswered.brokenReason());
    } finally {
      resolver.forget(gone);
      resolver.forget(down);
    }
  }

  /** qits-1952: a BRANCH wait on a maintenance branch, which the delivery ledger never follows. */
  @Test
  void aBranchTheLedgerDoesNotFollowBreaksThirtyMinutesAfterTheDeclaration() {
    UUID id = agent();
    String branch = "maintenance/x-" + UUID.randomUUID();
    resolver.answer(branch, WaitSubjectResolver.Resolved.ofBranch(new WaitSubjectResolver.Branch(null, List.of())));
    try {
      AgentWaitDto wait = waits.create(id, "branch live everywhere", untilLive(branch, true), null);
      assertEquals(AgentWaitState.OPEN, wait.state(), "the ledger may not have written it yet");
      assertTrue(resolver.lookupsOf(branch).get(0).startsWith("BRANCH " + branch), resolver.lookupsOf(branch).toString());

      // A re-check within RECHECK_EVERY does not ask again.
      waits.resolveUnresolved();
      assertEquals(1, resolver.askedFor(branch));

      // Thirty-one minutes after the declaration, the re-check finds it still unfollowed.
      declaredAt(wait.id(), Instant.now().minus(Duration.ofMinutes(31)));
      update(wait.id(), w -> w.subjectResolvedAt = Instant.now().minus(Duration.ofDays(1)));
      waits.resolveUnresolved();
      AgentWait broken = waitRow(wait.id());
      assertEquals(AgentWaitState.BROKEN, broken.state);
      assertEquals(
          "branch " + branch + " has no delivery 31 min after the wait was declared: the delivery"
              + " ledger does not follow it, so no BranchDeliveryChanged is coming",
          broken.brokenReason);
      String turn = row(id).pendingText;
      assertTrue(turn.contains("It waited for LIVE or FAILED or CONFLICTED or WITHDRAWN."), turn);
      assertTrue(turn.contains("Re-plan: wait on a branch the ledger follows"), turn);
    } finally {
      resolver.forget(branch);
    }
  }

  /**
   * qits-1952: {@code --until live} with no early groups, on a branch FAILED in a REJECTED request.
   * {@code everywhere} stays JOINED, but the failed row stood for the grace: the wait breaks and the
   * turn names the run.
   */
  @Test
  void anEverywhereWaitWithNoEarlyGroupsBreaksOnAFailedRowNamingTheRun() {
    UUID id = agent();
    String branch = "ticket/qits-" + UUID.randomUUID();
    Instant failedAt = Instant.now().minus(Duration.ofMinutes(45)).truncatedTo(ChronoUnit.SECONDS);
    resolver.answer(
        branch,
        WaitSubjectResolver.Resolved.ofBranch(
            new WaitSubjectResolver.Branch(
                "JOINED",
                List.of(
                    new WaitSubjectResolver.Delivery(
                        "qits-ci-service", "FAILED", "qits-ci-service-rr-7", "run-1952", failedAt)))));
    try {
      AgentWaitDto wait = waits.create(id, null, untilLive(branch, false), null);

      assertEquals(AgentWaitState.BROKEN, wait.state());
      assertEquals("JOINED", wait.subjectState(), "its state is everywhere's");
      String reason =
          "branch " + branch + " in qits-ci-service is FAILED since "
              + AgentAttention.HH_MM.format(failedAt)
              + " UTC in qits-ci-service-rr-7: run run-1952";
      assertEquals(reason, wait.brokenReason());
      assertTrue(row(id).pendingText.contains("(BROKEN): " + reason + ". It waited for LIVE."), row(id).pendingText);
    } finally {
      resolver.forget(branch);
    }
  }

  @Test
  void aDesksWaitBreaksAndItsAnnouncementCarriesTheTurn() {
    UUID project = UUID.randomUUID();
    String request = "qits-x-rr-" + UUID.randomUUID();
    resolver.answer(
        request,
        new WaitSubjectResolver.Resolved(
            UUID.randomUUID().toString(), request, "WITHDRAWN", Instant.parse("2026-11-08T07:05:00Z"), null));
    try {
      AgentWaitDto wait = waits.createForDesk(project, "rr final", requestFinalized(request), null, null);
      assertEquals(AgentWaitState.BROKEN, wait.state());
      assertEquals(request + " is WITHDRAWN since 07:05 UTC", wait.brokenReason());

      List<DeskWaitClosed> events = announced.of(DeskWaitClosed.class, e -> e.waitId().equals(wait.id()));
      assertEquals(1, events.size());
      assertEquals(DeskWaitClosed.BROKEN, events.get(0).state());
      assertEquals(
          "Your wait \"rr final\" can no longer be satisfied and is closed (BROKEN): "
              + request
              + " is WITHDRAWN since 07:05 UTC. It waited for FINALIZED. Re-plan: fix and push, or"
              + " declare a wait on what you need now.",
          events.get(0).turn());
      assertTrue(closesOf(wait.id()).isEmpty(), "a desk announces no agent wait event");
    } finally {
      resolver.forget(request);
    }
  }

  @Test
  void commentsAndOtherWaitsNeverBreakOnTheirState() {
    UUID id = agent();
    String entity = "e-" + UUID.randomUUID();
    resolver.answer(
        entity, new WaitSubjectResolver.Resolved(UUID.randomUUID().toString(), null, "DONE", Instant.now(), null));
    try {
      AgentWaitDto comments =
          waits.create(id, null, EventSelection.of("EntityCommented", Condition.exact("entityId", entity)), null);
      assertEquals(AgentWaitState.OPEN, comments.state());
      AgentWaitDto other = waits.create(id, null, EventSelection.of("SCMRelease"), null);
      assertEquals(AgentWaitState.OPEN, other.state());
    } finally {
      resolver.forget(entity);
    }
  }

  // --- the ceiling --------------------------------------------------------------------------------

  @Test
  void aWaitWithNoTimeoutGetsTheCeilingAndItsTimeoutSaysSo() {
    UUID id = agent();
    AgentWaitDto wait = waits.create(id, "anything", EventSelection.of("SCMRelease"), null);
    assertEquals(AgentWaits.DEFAULT_TIMEOUT, Duration.between(wait.createdAt(), wait.expiresAt()));
    assertTrue(waitRow(wait.id()).expiryIsCeiling);
    AgentWaitDto asked = waits.create(id, "asked", EventSelection.of("SCMRelease"), Duration.ofHours(24));
    assertEquals(Duration.ofHours(24), Duration.between(asked.createdAt(), asked.expiresAt()));
    assertFalse(waitRow(asked.id()).expiryIsCeiling);

    BadRequestException tooLong =
        assertThrows(
            BadRequestException.class,
            () -> waits.create(id, "x", EventSelection.of("SCMRelease"), Duration.ofHours(25)));
    assertTrue(tooLong.getMessage().contains("PT24H (24 hours, the platform's cap)"), tooLong.getMessage());

    declaredAt(wait.id(), Instant.now().minus(AgentWaits.DEFAULT_TIMEOUT).minusSeconds(1));
    update(wait.id(), w -> w.expiresAt = Instant.now().minusSeconds(1));
    waits.sweep();
    assertEquals(AgentWaitState.TIMED_OUT, waitRow(wait.id()).state);
    assertTrue(
        row(id).pendingText.contains(
            "\"anything\" timed out after 8h, the platform's ceiling for a wait that asks for no"
                + " timeout (or for more than 24h): no SCMRelease event matched it."),
        row(id).pendingText);
  }

  @Test
  void aTimeoutTheWaiterAskedForIsNotCalledTheCeiling() {
    AgentWait wait = new AgentWait();
    wait.label = "rr";
    wait.eventName = "ReleaseRequestStateChanged";
    wait.createdAt = Instant.parse("2026-11-08T08:00:00Z");
    wait.expiresAt = wait.createdAt.plus(Duration.ofHours(2));
    assertEquals(
        "Your wait \"rr\" timed out after 2h: no ReleaseRequestStateChanged event matched it. It is"
            + " closed; declare a new one if you still need to wait.",
        AgentWaits.timedOutTurn(wait));
  }
}
