package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.persistence.AgentBranchRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentService} (qits-1152) on a real database: dispatch and placement, the scheduler's
 * start, turn, yield and resume, a release making an agent obsolete, and the cleanup sweep (D4–D7).
 * The daemon is {@link FakeAgentDaemon}; admin agents are used where a slot is needed, because a
 * DIRECT admin workspace needs no runner.
 */
@QuarkusTest
public class AgentServiceTest {

  @Inject FakeAgentWaitingReporter reporter;

  @Inject AgentService service;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentBranchRepository branches;
  @Inject WorkspaceRepository workspaces;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentDaemon daemon;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeCredentialCommissioner commissioner;

  private String repoId;

  @BeforeEach
  void setUp() {
    daemon.reset();
    commissioner.reset();
    repoId = "agents-" + UUID.randomUUID().toString().substring(0, 8);
    repositories.register(repoId, "main");
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of("example.test", List.of("registry.dev.localhost:8080"));
          }
        },
        WorkspaceAddressPlanes.class);
  }

  @AfterEach
  void tearDown() {
    commissioner.reset();
  }

  private AgentService.Dispatch dispatch(String workId, boolean admin, String instruction) {
    return service.dispatch(
        new AgentService.AgentRequest(
            repoId,
            "ticket/" + workId,
            workId,
            workId,
            workId,
            null,
            admin,
            null,
            instruction,
            new EntityFacts("A title", "REFINED", false)));
  }

  private AgentWorktree row(String agentId) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(UUID.fromString(agentId)));
  }

  private Workspace workspace(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.findById(id));
  }

  @Test
  void aRegularAgentWithNoRunnerQueuesAndARepressAnswersIt() {
    AgentService.Dispatch first = dispatch("w-queued", false, "go");

    assertTrue(first.fresh());
    assertEquals(AgentService.AgentLaunch.QUEUED, first.agentLaunch());
    assertNull(first.agent().workspaceRowId(), "no runner slot: no workspace");
    assertEquals(AgentRunState.QUEUED, first.agent().runState());
    assertEquals(
        List.of("refs/heads/ticket/w-queued", "refs/heads/ticket/w-queued-*"),
        GitRefs.read(row(first.agent().agentId()).gitRefs));

    AgentService.Dispatch again = dispatch("w-queued", false, "go");
    assertFalse(again.fresh());
    assertEquals(first.agent().agentId(), again.agent().agentId());
  }

  @Test
  void anAgentNeedsAWorkItemAndABranchOtherThanTheDefault() {
    assertThrows(BadRequestException.class, () -> dispatch(" ", false, null));
    assertThrows(
        BadRequestException.class,
        () ->
            service.dispatch(
                new AgentService.AgentRequest(
                    repoId, "main", "w-main", null, null, null, false, null, null, null)));
  }

  @Test
  void anAdminAgentGetsAnAdminSlotAndIsStartedWithItsCredentialOnceTheDaemonAnswers() {
    AgentService.Dispatch dispatched = dispatch("w-admin", true, "first turn");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();

    assertNotNull(slot, "an admin agent always gets a DIRECT slot");
    assertEquals(AgentService.AgentLaunch.SCHEDULED, dispatched.agentLaunch());
    Workspace ws = workspace(slot);
    assertTrue(ws.admin);
    assertEquals(WorkspacePlacement.DIRECT, ws.placement);
    assertNull(ws.branch, "a slot names no branch");
    assertNotNull(dispatched.agentIdentity(), "the agent holds a token of its own");
    assertEquals(1, commissioner.agentTokensMinted().size());
    assertEquals(
        List.of("refs/heads/ticket/w-admin", "refs/heads/ticket/w-admin-*"),
        commissioner.agentTokensMinted().get(0).gitRefs());

    liveness.markLive(slot);
    service.reconcile();

    AgentDaemon.Start start =
        daemon.starts().stream().filter(s -> s.agentId().equals(agentId)).findFirst().orElseThrow();
    assertEquals("first turn", start.instruction());
    assertEquals("ticket/w-admin", start.wrapperBranch());
    assertEquals(row(agentId).commissionedToken, start.env().get("QITS_TOKEN"));
    AgentWorktree after = row(agentId);
    assertNull(after.pendingText, "the instruction was handed over");
    assertEquals("session-" + agentId, after.sessionId);
    assertTrue(service.isStarted(UUID.fromString(agentId)));

    // A daemon restart: the agent is started again, resuming its session.
    service.onDaemonConnection(slot);
    service.reconcile();
    assertEquals("session-" + agentId, daemon.starts().get(daemon.starts().size() - 1).sessionId());
    liveness.markDead(slot);
  }

  @Test
  void aDeliveryReachesARunningAgentAtItsNextTurn() {
    AgentService.Dispatch dispatched = dispatch("w-turn", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();

    AgentService.Delivery delivery = service.deliver("w-turn", "next phase", false);
    assertEquals(agentId, delivery.agentId());
    assertTrue(delivery.delivered());
    service.reconcile();

    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " next phase"));
    assertNull(row(agentId).pendingText);
    liveness.markDead(slot);
  }

  @Test
  void aDeliveryReachesAnAgentAReleaseMadeObsolete() {
    // qits-248: a release took one of the agent's branches mid-item, and the next phase's turn was
    // answered "nobody works on it" while the agent still ran.
    AgentService.Dispatch dispatched = dispatch("w-obsolete", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);
    liveness.markLive(slot);
    service.reconcile();
    QuarkusTransaction.requiringNew()
        .run(() -> branches.upsert(id, "service-repo", "ticket/w-obsolete-fix", "abc", Instant.now()));
    service.resolveReleasedBranch("service-repo", "ticket/w-obsolete-fix", "2026.1.1", "abc", null);
    assertEquals(AgentState.OBSOLETE, row(agentId).state);

    AgentService.Delivery delivery = service.deliver("w-obsolete", "verify it", false);
    assertEquals(agentId, delivery.agentId());
    assertTrue(delivery.delivered());
    service.reconcile();

    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " verify it"));
    liveness.markDead(slot);
  }

  @Test
  void aDeliveryForAnItemNobodyWorksOnSaysSoAndCreatesNothing() {
    AgentService.Delivery delivery = service.deliver("w-nobody", "hello", false);
    assertNull(delivery.agentId());
    assertFalse(delivery.delivered() || delivery.launched() || delivery.resumed());
  }

  @Test
  void aWaitingAgentYieldsToAQueuedOneAndResumesWhenSpokenTo() {
    AgentService.Dispatch first = dispatch("w-first", true, null);
    String firstId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();

    // A second admin agent of the same wrapper, queued behind a full capacity is not possible for
    // DIRECT, so it is placed in a new admin slot; queue one by hand to stand for a full runner.
    UUID queued = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree waiting = new AgentWorktree();
              waiting.id = queued;
              waiting.repositoryId = repoId;
              waiting.workId = "w-second";
              waiting.wrapperBranch = "ticket/w-second";
              waiting.admin = true;
              waiting.createdAt = Instant.now();
              waiting.gitRefs = "[]";
              agents.persist(waiting);
            });
    // The first agent has waited for input since long before the yield time.
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(firstId)).waitingSince = Instant.now().minusSeconds(3600));

    service.yieldBlocked();

    assertTrue(daemon.callsFor(firstId).contains("yield " + slot + " " + firstId));
    assertEquals(AgentRunState.YIELDED, row(firstId).runState);

    // Spoken to, the yielded agent resumes once its slot is free again.
    AgentService.Delivery delivery = service.deliver("w-first", "your answer", false);
    assertTrue(delivery.resumed());
    liveness.markDead(slot);
  }

  @Test
  void aReleasedBranchMakesItsAgentsObsoleteAndTheSweepRemovesThemOnceVerifiedAndIdle() {
    AgentService.Dispatch dispatched = dispatch("w-release", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);
    QuarkusTransaction.requiringNew()
        .run(() -> branches.upsert(id, "service-repo", "ticket/w-release-fix", "abc", Instant.now()));

    AgentService.Resolution resolution =
        service.resolveReleasedBranch("service-repo", "ticket/w-release-fix", "2026.1.1", "abc", null);
    assertTrue(resolution.resolved());
    assertEquals(List.of(agentId), resolution.agentIds());
    assertEquals(AgentState.OBSOLETE, row(agentId).state);

    // Not VERIFIED yet: nothing is removed.
    liveness.markLive(slot);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));
    service.cleanUp();
    assertEquals(AgentState.OBSOLETE, row(agentId).state);

    service.markEntity("w-release", new EntityFacts("A title", "VERIFIED", false), null);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));
    service.cleanUp();

    AgentWorktree removed = row(agentId);
    assertEquals(AgentState.REMOVED, removed.state);
    assertNull(removed.commissionedTokenId, "the credential went with it");
    assertTrue(daemon.callsFor(agentId).contains("remove " + slot + " " + agentId + " force=false"));

    service.removeEmptyWorkspaces();
    assertEquals(WorkspaceStatus.ABANDONED, workspace(slot).status, "the empty slot goes too (D2)");
    liveness.markDead(slot);
  }

  @Test
  void leftoverWorkIsHandedBackToTheAgentNeverDeleted() {
    AgentService.Dispatch dispatched = dispatch("w-dirty", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);
    liveness.markLive(slot);
    service.reconcile();
    daemon.leftover(
        agentId,
        new AgentDaemon.CleanupCheck(
            false,
            List.of("qits-ci-service"),
            List.of(new AgentDaemon.CleanupCheck.Unpushed("qits-ci-service", null, 2))));
    service.resolveReleasedBranch(repoId, "ticket/w-dirty", "2026.1.1", "abc", null);
    service.markEntity("w-dirty", new EntityFacts(null, "VERIFIED", false), null);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));

    service.cleanUp();

    AgentWorktree prompted = row(agentId);
    assertEquals(AgentState.OBSOLETE, prompted.state, "nothing is removed");
    assertNotNull(prompted.cleanupPromptedAt);
    // The prompt is its next turn: still pending, or already said by a background pass.
    String said =
        prompted.pendingText != null
            ? prompted.pendingText
            : daemon.callsFor(agentId).stream()
                .filter(c -> c.startsWith("turn "))
                .reduce((a, b) -> b)
                .orElse("");
    assertTrue(said.contains(AgentService.CLEANUP_PROMPT), said);
    assertTrue(said.contains("2 unpushed commit(s) in qits-ci-service on a detached HEAD"), said);
    liveness.markDead(slot);
  }

  @Test
  void aPersonsDiscardRefusesLeftoverWorkUnlessForced() {
    AgentService.Dispatch dispatched = dispatch("w-discard", true, null);
    String agentId = dispatched.agent().agentId();
    daemon.leftover(
        agentId, new AgentDaemon.CleanupCheck(false, List.of("wrapper"), List.of()));

    ConflictException refused =
        assertThrows(ConflictException.class, () -> service.discard(UUID.fromString(agentId), false));
    assertTrue(refused.getMessage().contains("uncommitted changes in wrapper"));

    AgentDto discarded = service.discard(UUID.fromString(agentId), true);
    assertEquals(AgentState.REMOVED, discarded.state());
  }

  @Test
  void aTurnTypedIntoAWaitingAgentTellsQitsProjectsItNoLongerWaits() {
    // qits-1198: the agent waited, a phase turn was typed into it, and qits-projects kept it
    // blocked while it worked, because the turn cleared the flag here without saying so.
    AgentService.Dispatch dispatched = dispatch("w-blocked", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    service.onActivity(agentId, Boolean.TRUE, "s-1", "Stop", System.currentTimeMillis());

    service.deliver("w-blocked", "implement it", false);
    service.reconcile();
    service.onActivity(agentId, Boolean.FALSE, "s-1", "UserPromptSubmit", System.currentTimeMillis());

    List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor("w-blocked");
    assertFalse(reports.isEmpty());
    assertFalse(reports.get(reports.size() - 1).waiting(), "the last word qits-projects heard");
    liveness.markDead(slot);
  }

  @Test
  void aPushReportedWhileQitsProjectsDoesNotAnswerIsRecordedOnALaterPass() {
    // qits-168: the push of qits-ci-service came while the name lookup failed, and was dropped.
    AgentService.Dispatch dispatched = dispatch("w-push", true, null);
    UUID id = UUID.fromString(dispatched.agent().agentId());
    String wrapperName = FakeRepositoryLookup.nameOf(repoId);
    try {
      repositories.findOutage(true);
      service.onBranchPushed(id.toString(), wrapperName, "ticket/w-push", "abc");
      assertTrue(QuarkusTransaction.requiringNew().call(() -> branches.findByAgent(id)).isEmpty());
    } finally {
      repositories.findOutage(false);
    }

    service.reconcile();

    assertEquals(
        List.of(id),
        QuarkusTransaction.requiringNew().call(() -> branches.agentsOn(repoId, "ticket/w-push")));
  }

  @Test
  void anActivityFrameStoresTheSessionAndTheWaitingFlip() {
    AgentService.Dispatch dispatched = dispatch("w-activity", false, null);
    String agentId = dispatched.agent().agentId();

    service.onActivity(agentId, Boolean.TRUE, "s-9", "Stop", System.currentTimeMillis());
    AgentWorktree waiting = row(agentId);
    assertEquals("s-9", waiting.sessionId);
    assertNotNull(waiting.waitingSince);

    service.onActivity(agentId, Boolean.FALSE, null, "UserPromptSubmit", System.currentTimeMillis());
    assertNull(row(agentId).waitingSince);
  }
}
