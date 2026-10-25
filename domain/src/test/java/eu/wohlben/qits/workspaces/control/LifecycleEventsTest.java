package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.events.AgentCleanupDeferred;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentPlaced;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.events.AgentRequeued;
import eu.wohlben.qits.workspaces.events.AgentResumed;
import eu.wohlben.qits.workspaces.events.AgentStartRefused;
import eu.wohlben.qits.workspaces.events.AgentStarted;
import eu.wohlben.qits.workspaces.events.AgentYielded;
import eu.wohlben.qits.workspaces.events.WorkspaceFailed;
import eu.wohlben.qits.workspaces.events.WorkspaceRemoved;
import eu.wohlben.qits.workspaces.events.WorkspaceStarted;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle events (qits-1236), as {@link AgentService}, {@link WorkspaceService} and {@link
 * TargetLeaseStates} announce them through {@link LifecycleAnnouncer}: which event each transition
 * says, with which ids and reason words, and that a throttled or repeated transition says it once.
 * {@link RecordingLifecycleAnnouncer} records; {@link AgentServiceTest}'s harness drives. The
 * scheduler also runs background passes, so every assertion selects this test's own agent or
 * workspace by id.
 */
@QuarkusTest
public class LifecycleEventsTest {

  @Inject AgentService service;
  @Inject AgentWorktreeRepository agents;
  @Inject WorkspaceRepository workspaces;
  @Inject WorkspaceService workspaceService;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentDaemon daemon;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject RecordingLifecycleAnnouncer announced;

  private String repoId;

  @BeforeEach
  void setUp() {
    daemon.reset();
    commissioner.reset();
    repoId = "events-" + UUID.randomUUID().toString().substring(0, 8);
    repositories.register(repoId, "main");
    planeIs(WorkspaceAddressPlane.of("example.test", List.of("registry.dev.localhost:8080")));
  }

  @AfterEach
  void tearDown() {
    List<UUID> left =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    agents.list("repositoryId = ?1 and state <> ?2", repoId, AgentState.REMOVED)
                        .stream()
                        .map(a -> a.id)
                        .toList());
    left.forEach(id -> service.markRemoved(id, AgentRemoved.DISCARDED, false, "the test is over"));
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces.findActiveByRepositoryId(repoId).stream()
                    .filter(w -> w.placement == WorkspacePlacement.TARGET)
                    .forEach(w -> w.status = WorkspaceStatus.ABANDONED));
    commissioner.reset();
  }

  // --- placement, start, refusal ------------------------------------------------------------------

  @Test
  void aPlacedAgentIsAnnouncedPlacedThenStartedWithTheDaemonsAnswer() throws Exception {
    AgentService.Dispatch dispatched = dispatch("w-placed", "first turn");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    try {
      AgentPlaced placed = only(AgentPlaced.class, e -> e.agentId().equals(agentId));
      assertEquals("w-placed", placed.workId());
      assertEquals(slot.toString(), placed.workspaceId(), "the workspace ROW id");
      assertEquals(repoId, placed.repositoryId());

      // The DIRECT admin slot came up in the background: one WorkspaceStarted for it.
      List<WorkspaceStarted> up =
          announced.await(WorkspaceStarted.class, e -> e.workspaceId().equals(slot.toString()), 1);
      assertEquals(1, up.size(), up.toString());
      assertEquals(repoId, up.get(0).repositoryId());

      liveness.markLive(slot);
      service.reconcile();

      AgentStarted started = only(AgentStarted.class, e -> e.agentId().equals(agentId));
      assertEquals(slot.toString(), started.workspaceId());
      assertEquals("cmd-" + agentId, started.commandId());
      assertTrue(started.launched());
      assertFalse(started.resumed());

      // A daemon restart: started again, attached to the harness that still runs.
      service.onDaemonConnection(slot);
      service.reconcile();
      List<AgentStarted> both = announced.of(AgentStarted.class, e -> e.agentId().equals(agentId));
      assertEquals(2, both.size());
      assertFalse(both.get(1).launched(), "attached, not launched");
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aRefusedStartIsAnnouncedOncePerDistinctRefusal() {
    AgentService.Dispatch dispatched = dispatch("w-refused", "go");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    String detail = "fatal: 'ticket/w-refused' is already checked out at '/workspace/agents/x'";
    daemon.refuseStarts(detail);
    try {
      liveness.markLive(slot);
      service.reconcile();
      service.reconcile();

      AgentStartRefused refused = only(AgentStartRefused.class, e -> e.agentId().equals(agentId));
      assertEquals(detail, refused.detail(), "the daemon's refusal, verbatim");
      assertEquals(slot.toString(), refused.workspaceId());
      assertEquals("w-refused", refused.workId());
      assertTrue(announced.of(AgentStarted.class, e -> e.agentId().equals(agentId)).isEmpty());
    } finally {
      daemon.acceptStarts();
      liveness.markDead(slot);
    }
  }

  // --- requeue ------------------------------------------------------------------------------------

  @Test
  void anAgentTakenOutOfAWorkspaceThatNeverCameUpIsRequeuedWorkspaceFailed() {
    Long failed = slot(WorkspaceStatus.ACTIVE, WorkspaceRuntimeStatus.FAILED, true);
    UUID id = agentIn(failed, true, AgentRunState.RUNNING, a -> {});

    service.driveRunning();

    AgentRequeued requeued = only(AgentRequeued.class, e -> e.agentId().equals(id.toString()));
    assertEquals(AgentRequeued.WORKSPACE_FAILED, requeued.reason());
    assertEquals(failed.toString(), requeued.workspaceId(), "the workspace it left");
    assertTrue(requeued.detail().contains("did not come up"), requeued.detail());
    // The same sentence is its stored start refusal.
    assertEquals(
        requeued.detail(),
        only(AgentStartRefused.class, e -> e.agentId().equals(id.toString())).detail());
  }

  @Test
  void anAgentWhoseWorkspaceIsGoneIsRequeuedWorkspaceGone() {
    Long gone = slot(WorkspaceStatus.ABANDONED, WorkspaceRuntimeStatus.STOPPED, false);
    UUID id = agentIn(gone, false, AgentRunState.RUNNING, a -> {});

    service.driveRunning();

    AgentRequeued requeued = only(AgentRequeued.class, e -> e.agentId().equals(id.toString()));
    assertEquals(AgentRequeued.WORKSPACE_GONE, requeued.reason());
    assertEquals(gone.toString(), requeued.workspaceId());
    assertEquals("w-" + id, requeued.workId());
  }

  // --- yield and resume ---------------------------------------------------------------------------

  @Test
  void aYieldAndBothKindsOfResumeAreAnnounced() {
    Long slot = slot(WorkspaceStatus.ACTIVE, WorkspaceRuntimeStatus.STOPPED, false);
    UUID waiting =
        agentIn(slot, false, AgentRunState.RUNNING, a -> a.waitingSince = Instant.now().minusSeconds(3600));
    // A queued agent of the same wrapper and posture wants the slot.
    agentIn(null, false, AgentRunState.QUEUED, a -> {});

    service.yieldBlocked();

    AgentYielded yielded = only(AgentYielded.class, e -> e.agentId().equals(waiting.toString()));
    assertEquals(slot.toString(), yielded.workspaceId());
    assertEquals(repoId, yielded.repositoryId());

    // A turn for it: it resumes before anyone new is placed.
    QuarkusTransaction.requiringNew().run(() -> agents.findById(waiting).pendingText = "your answer");
    service.resumeYielded();
    AgentResumed resumed = only(AgentResumed.class, e -> e.agentId().equals(waiting.toString()));
    assertEquals(AgentResumed.TURN_PENDING, resumed.reason());
    assertEquals(slot.toString(), resumed.workspaceId());

    // A person typing into a yielded agent's terminal: it works again.
    UUID typedInto = agentIn(slot, false, AgentRunState.YIELDED, a -> {});
    service.onActivity(
        slot, typedInto.toString(), Boolean.FALSE, "s-1", "UserPromptSubmit", System.currentTimeMillis());
    AgentResumed worksAgain = only(AgentResumed.class, e -> e.agentId().equals(typedInto.toString()));
    assertEquals(AgentResumed.WORKS_AGAIN, worksAgain.reason());
  }

  // --- obsolete, deferred, removed ----------------------------------------------------------------

  @Test
  void aReleaseAnnouncesEachObsoleteAgentWithItsRefsAndNotTheResultText() {
    AgentService.Dispatch dispatched = dispatch("w-release", null);
    String agentId = dispatched.agent().agentId();
    // Closed first, so the release itself ends the agent (qits-1605).
    service.markEntity("w-release", new EntityFacts(null, "VERIFIED", false), null, null);

    service.resolveReleasedBranch(
        repoId, "ticket/w-release", "2026.1014.1", "abc123", "a sentence a person wrote");

    AgentObsolete obsolete = only(AgentObsolete.class, e -> e.agentId().equals(agentId));
    assertEquals("ticket/w-release", obsolete.branch());
    assertEquals("2026.1014.1", obsolete.version());
    assertEquals("abc123", obsolete.commitSha());
    assertEquals("w-release", obsolete.workId());
    assertEquals(dispatched.agent().workspaceRowId().toString(), obsolete.workspaceId());
    assertFalse(obsolete.toString().contains("a sentence a person wrote"));
  }

  @Test
  void aDeferredCleanupIsAnnouncedOncePerReasonWordWhateverItsDetailSays() {
    UUID id =
        agentIn(
            null,
            false,
            AgentRunState.QUEUED,
            a -> {
              a.state = AgentState.OBSOLETE;
              a.entityStatus = "IMPLEMENTED";
            });
    String agentId = id.toString();

    service.cleanUp();
    service.cleanUp();
    // The item moves on, but it is still not closed: the same deferral, though its detail differs.
    QuarkusTransaction.requiringNew().run(() -> agents.findById(id).entityStatus = "REFINED");
    service.cleanUp();

    List<AgentCleanupDeferred> deferred =
        announced.of(AgentCleanupDeferred.class, e -> e.agentId().equals(agentId));
    assertEquals(1, deferred.size(), deferred.toString());
    assertEquals(AgentCleanupDeferred.ITEM_NOT_CLOSED, deferred.get(0).reason());
    assertEquals("its item is IMPLEMENTED, not closed", deferred.get(0).detail());
    assertNull(deferred.get(0).workspaceId(), "never placed: no workspace");

    // Closed now, but it worked a moment ago: a new reason, a second event.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(id);
              row.entityStatus = "VERIFIED";
              row.lastActivityAt = Instant.now();
            });
    service.cleanUp();

    deferred = announced.of(AgentCleanupDeferred.class, e -> e.agentId().equals(agentId));
    assertEquals(2, deferred.size(), deferred.toString());
    assertEquals(AgentCleanupDeferred.RECENTLY_ACTIVE, deferred.get(1).reason());
  }

  @Test
  void anObsoleteAgentNeverPlacedIsRemovedSayingSo() {
    UUID id = agentIn(null, false, AgentRunState.QUEUED, LifecycleEventsTest::closedAndIdle);

    service.cleanUp();
    service.markRemoved(id, AgentRemoved.DISCARDED, false, "a second removal flips nothing");

    AgentRemoved removed = only(AgentRemoved.class, e -> e.agentId().equals(id.toString()));
    assertEquals(AgentRemoved.OBSOLETE_NEVER_PLACED, removed.reason());
    assertFalse(removed.harnessStopped());
    assertNull(removed.workspaceId());
    assertEquals("obsolete, and never placed", removed.detail());
  }

  @Test
  void anObsoleteAgentWhoseWorkspaceIsGoneIsRemovedSayingSo() {
    Long gone = slot(WorkspaceStatus.ABANDONED, WorkspaceRuntimeStatus.STOPPED, false);
    UUID id = agentIn(gone, false, AgentRunState.YIELDED, LifecycleEventsTest::closedAndIdle);

    service.cleanUp();

    AgentRemoved removed = only(AgentRemoved.class, e -> e.agentId().equals(id.toString()));
    assertEquals(AgentRemoved.OBSOLETE_WORKSPACE_GONE, removed.reason());
    assertFalse(removed.harnessStopped());
    assertEquals(gone.toString(), removed.workspaceId());
  }

  @Test
  void aReleasedIdleCleanAgentIsRemovedWithItsHarnessStopped() {
    String agentId = sweepable("w-clean", id -> {});
    Long slot = row(agentId).workspaceId;
    try {
      service.cleanUp();

      AgentRemoved removed = only(AgentRemoved.class, e -> e.agentId().equals(agentId));
      assertEquals(AgentRemoved.RELEASED_IDLE_CLEAN, removed.reason());
      assertTrue(removed.harnessStopped(), "the daemon answered the removal OK");
      assertEquals(slot.toString(), removed.workspaceId());

      // Its slot is empty now and goes too.
      service.removeEmptyWorkspaces();
      WorkspaceRemoved slotGone =
          only(WorkspaceRemoved.class, e -> e.workspaceId().equals(slot.toString()));
      assertEquals(WorkspaceRemoved.NO_AGENT_LEFT, slotGone.reason());
      assertEquals(repoId, slotGone.repositoryId());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aRemovalTheDaemonAnsweredUnknownStopsNoHarness() {
    String agentId =
        sweepable("w-unknown-remove", id -> daemon.removesAnswer(id, AgentDaemon.Outcome.UNKNOWN_AGENT));
    Long slot = row(agentId).workspaceId;
    try {
      service.cleanUp();

      AgentRemoved removed = only(AgentRemoved.class, e -> e.agentId().equals(agentId));
      assertEquals(AgentRemoved.RELEASED_IDLE_CLEAN, removed.reason());
      assertFalse(removed.harnessStopped(), "UNKNOWN_AGENT is not an OK");
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void anAgentItsDaemonForgotIsRemovedUnknownToDaemon() {
    String agentId = sweepable("w-forgot", daemon::forget);
    Long slot = row(agentId).workspaceId;
    try {
      service.cleanUp();

      AgentRemoved removed = only(AgentRemoved.class, e -> e.agentId().equals(agentId));
      assertEquals(AgentRemoved.UNKNOWN_TO_DAEMON, removed.reason());
      assertFalse(removed.harnessStopped());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aPersonsDiscardIsRemovedDiscardedStoppedOnlyWhenTheDaemonSaidOk() {
    AgentService.Dispatch clean = dispatch("w-discard", null);
    String cleanId = clean.agent().agentId();
    service.discard(UUID.fromString(cleanId), false);
    AgentRemoved discarded = only(AgentRemoved.class, e -> e.agentId().equals(cleanId));
    assertEquals(AgentRemoved.DISCARDED, discarded.reason());
    assertTrue(discarded.harnessStopped());

    AgentService.Dispatch forgotten = dispatch("w-discard-forgotten", null);
    String forgottenId = forgotten.agent().agentId();
    daemon.forget(forgottenId);
    service.discard(UUID.fromString(forgottenId), false);
    AgentRemoved nothingToStop = only(AgentRemoved.class, e -> e.agentId().equals(forgottenId));
    assertEquals(AgentRemoved.DISCARDED, nothingToStop.reason());
    assertFalse(nothingToStop.harnessStopped(), "the daemon was not asked to remove anything");
  }

  // --- workspaces ---------------------------------------------------------------------------------

  @Test
  void aRunnerRowThatCannotBePlacedIsAnnouncedFailedOnce() throws Exception {
    planeIs(null);
    Workspace created = workspaceService.createSlot(repoId, false);
    String rowId = created.id.toString();

    List<WorkspaceFailed> failed =
        announced.await(WorkspaceFailed.class, e -> e.workspaceId().equals(rowId), 1);
    assertEquals(1, failed.size(), failed.toString());
    assertEquals(repoId, failed.get(0).repositoryId());
    assertTrue(
        failed.get(0).runtimeError().startsWith(EdgePlaneUnconfigured.CODE),
        failed.get(0).runtimeError());

    // Asked again, the row is FAILED already: no second transition, no second event.
    workspaceService.beginEnsureContainer(created.id);
    assertEquals(1, announced.of(WorkspaceFailed.class, e -> e.workspaceId().equals(rowId)).size());
  }

  // --- helpers ------------------------------------------------------------------------------------

  /** The edge plane every placement reads; null makes it refuse, as a deployment with no domain. */
  private void planeIs(WorkspaceAddressPlane plane) {
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            if (plane == null) {
              throw new EdgePlaneUnconfigured("localhost");
            }
            return plane;
          }
        },
        WorkspaceAddressPlanes.class);
  }

  private <T extends QitsEvent> T only(Class<T> type, java.util.function.Predicate<T> which) {
    List<T> matched = announced.of(type, which);
    assertEquals(1, matched.size(), type.getSimpleName() + ": " + matched);
    return matched.get(0);
  }

  /** An admin agent: an admin agent always gets a DIRECT slot of its own, so it is placed at once. */
  private AgentService.Dispatch dispatch(String workId, String instruction) {
    AgentService.Dispatch dispatched =
        service.dispatch(
            new AgentService.AgentRequest(
                repoId,
                "ticket/" + workId,
                workId,
                workId,
                workId,
                null,
                true,
                null,
                instruction,
                new EntityFacts("A title", "REFINED", false)));
    assertNotNull(dispatched.agent().workspaceRowId());
    return dispatched;
  }

  private AgentWorktree row(String agentId) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findById(UUID.fromString(agentId)));
  }

  /** A slot of this test's wrapper, by hand: no runner holds it and nothing starts it. */
  private Long slot(WorkspaceStatus status, WorkspaceRuntimeStatus runtime, boolean admin) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = repoId;
              row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
              row.status = status;
              row.runtimeStatus = runtime;
              row.runtimeError =
                  runtime == WorkspaceRuntimeStatus.FAILED
                      ? "no workspace-daemon dialed home within 30000ms"
                      : null;
              row.placement = admin ? WorkspacePlacement.DIRECT : WorkspacePlacement.TARGET;
              row.admin = admin;
              workspaces.persist(row);
              return row.id;
            });
  }

  /** An agent row of this test's wrapper, put straight into {@code slot} (null: none). */
  private UUID agentIn(Long slot, boolean admin, AgentRunState runState, Consumer<AgentWorktree> shape) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repoId;
              row.workId = "w-" + id;
              row.wrapperBranch = "ticket/w-" + id;
              row.admin = admin;
              row.workspaceId = slot;
              row.runState = runState;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              shape.accept(row);
              agents.persist(row);
            });
    return id;
  }

  /** OBSOLETE, its item VERIFIED, idle for an hour: what the sweep removes. */
  private static void closedAndIdle(AgentWorktree row) {
    row.state = AgentState.OBSOLETE;
    row.entityStatus = "VERIFIED";
    row.lastActivityAt = Instant.now().minusSeconds(3600);
  }

  /** {@link AgentServiceTest}'s: started in its slot, VERIFIED, released and idle. */
  private String sweepable(String workId, Consumer<String> script) {
    AgentService.Dispatch dispatched = dispatch(workId, null);
    String agentId = dispatched.agent().agentId();
    liveness.markLive(dispatched.agent().workspaceRowId());
    service.reconcile();
    script.accept(agentId);
    // VERIFIED first: a release of an open item's branch leaves its agent ACTIVE (qits-1605).
    service.markEntity(workId, new EntityFacts(null, "VERIFIED", false), null, null);
    service.resolveReleasedBranch(repoId, "ticket/" + workId, "2026.1.1", "abc", null);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agents.findById(UUID.fromString(agentId)).lastActivityAt =
                    Instant.now().minusSeconds(3600));
    return agentId;
  }
}
