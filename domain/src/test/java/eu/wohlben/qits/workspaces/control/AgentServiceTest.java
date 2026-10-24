package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.events.AgentNudged;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.persistence.AgentBranchRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link AgentService} (qits-1152) on a real database: dispatch and placement, the scheduler's
 * start, turn, yield (a row change only, qits-1212) and resume, a release making an agent obsolete, and the cleanup sweep (D4–D7).
 * The daemon is {@link FakeAgentDaemon}; admin agents are used where a slot is needed, because a
 * DIRECT admin workspace needs no lease.
 */
@QuarkusTest
public class AgentServiceTest {

  @Inject FakeAgentWaitingReporter reporter;

  @Inject AgentService service;
  @Inject AgentWorktreeRepository agents;
  @Inject AgentBranchRepository branches;
  @Inject WorkspaceRepository workspaces;
  @Inject FakeTargetPlacement targets;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeAgentDaemon daemon;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject RecordingLifecycleAnnouncer announced;
  @Inject AgentWaits waits;
  @Inject FakeAgentActivityView activityView;

  private String repoId;

  /** The agents a capacity test wrote, removed after it (qits-1228). */
  private final List<UUID> createdAgents = new ArrayList<>();

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
    // A lease of this service waits already, so a regular agent queues rather than getting a new
    // workspace (AgentService.targetCapacity) unless a test frees the capacity (noLeaseWaits).
    branchRow(WorkspaceRuntimeStatus.QUEUED);
  }

  @AfterEach
  void tearDown() {
    // Every agent of this test's wrapper is retired: a QUEUED or placed one outlives the class and
    // is placed by a later pass, eating runner capacity another test sized (qits-1233 made an agent
    // in a FAILED workspace queue again rather than strand there, which is how this showed).
    List<UUID> left =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    agents.list("repositoryId = ?1 and state <> ?2", repoId, AgentState.REMOVED)
                        .stream()
                        .map(a -> a.id)
                        .toList());
    left.forEach(id -> service.markRemoved(id, AgentRemoved.DISCARDED, false, "the test is over"));
    commissioner.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              createdAgents.forEach(agents::deleteById);
              // A TARGET row left waiting keeps every later test's agents from getting a slot
              // (AgentService.targetCapacity): none of this test's may outlive it.
              workspaces.findActiveByRepositoryId(repoId).stream()
                  .filter(w -> w.placement == WorkspacePlacement.TARGET)
                  .forEach(w -> w.status = WorkspaceStatus.ABANDONED);
            });
    createdAgents.clear();
    targets.reset();
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
  void aRegularAgentQueuesWhileALeaseWaitsAndARepressAnswersIt() {
    AgentService.Dispatch first = dispatch("w-queued", false, "go");

    assertTrue(first.fresh());
    assertEquals(AgentService.AgentLaunch.QUEUED, first.agentLaunch());
    assertNull(first.agent().workspaceRowId(), "a lease waits already: no new workspace");
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
        agentId,
        commissioner.agentTokensMinted().get(0).agentId(),
        "the agent claim (qits-1213) is minted from the agent's own id");
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
  void aStartCarriesNoAgentConfigWhenEitherPropertyIsBlank() {
    // qits-1382: this class's profile ships both qits.workspace.agent-config.* blank, the shipped
    // default; AgentConfigStartTest is the pair-set half, on the shared profile that pins them.
    AgentService.Dispatch dispatched = dispatch("w-no-agent-config", true, "go");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);

    service.reconcile();

    AgentDaemon.Start start =
        daemon.starts().stream().filter(s -> s.agentId().equals(agentId)).findFirst().orElseThrow();
    assertNull(start.agentConfig());
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
    assertEquals(
        "Queued: said at once if the agent is idle, else when its current turn ends, and after"
            + " 15 minutes regardless.",
        delivery.detail());
    service.reconcile();

    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " next phase"));
    assertNull(row(agentId).pendingText);
    liveness.markDead(slot);
  }

  @Test
  void aDeliveryReachesAnAgentAReleaseMadeObsolete() {
    // qits-248: a release took the agent's wrapper branch mid-item, and the next phase's turn was
    // answered "nobody works on it" while the agent still ran.
    AgentService.Dispatch dispatched = dispatch("w-obsolete", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    releaseWrapperOf(agentId);

    AgentService.Delivery delivery = service.deliver("w-obsolete", "verify it", false);
    assertEquals(agentId, delivery.agentId());
    assertTrue(delivery.delivered());
    service.reconcile();

    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " verify it"));
    liveness.markDead(slot);
  }

  /** A release takes the agent's wrapper branch: the agent is OBSOLETE (D4). */
  private void releaseWrapperOf(String agentId) {
    AgentWorktree agent = row(agentId);
    service.resolveReleasedBranch(agent.repositoryId, agent.wrapperBranch, "2026.1.1", "abc", null);
    assertEquals(AgentState.OBSOLETE, row(agentId).state);
  }

  private long liveRowsOf(String workId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                agents.findByWorkIds(List.of(workId)).stream()
                    .filter(a -> a.state != AgentState.REMOVED)
                    .count());
  }

  @Test
  void aDispatchRevivesTheAgentAReleaseMadeObsoleteInsteadOfMakingASecond() {
    // qits-1199: a release took the agent's wrapper branch mid-item, the next dispatch made a
    // second agent on the same wrapper branch, and its daemon refused it "already checked out".
    AgentService.Dispatch first = dispatch("w-revive", true, "first phase");
    String agentId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    releaseWrapperOf(agentId);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(agentId)).cleanupPromptedAt = Instant.now());
    assertNotEquals("VERIFIED", row(agentId).entityStatus, "the item goes on");

    AgentService.Dispatch again = dispatch("w-revive", true, "next phase");

    assertEquals(agentId, again.agent().agentId(), "the item's agent is the answer");
    assertFalse(again.fresh());
    assertEquals(AgentState.ACTIVE, again.agent().state());
    AgentWorktree revived = row(agentId);
    assertEquals(AgentState.ACTIVE, revived.state);
    assertNull(revived.obsoleteAt);
    assertNull(revived.cleanupPromptedAt);
    assertEquals(slot, revived.workspaceId, "it keeps its slot, worktree and session");
    assertEquals(1, liveRowsOf("w-revive"), "no second agent for the item");
    liveness.markDead(slot);
  }

  @Test
  void aRevivedAgentHearsTheNewInstructionRunningOrYielded() {
    AgentService.Dispatch running = dispatch("w-revive-running", true, null);
    String runningId = running.agent().agentId();
    Long runningSlot = running.agent().workspaceRowId();
    AgentService.Dispatch yielded = dispatch("w-revive-yielded", true, null);
    String yieldedId = yielded.agent().agentId();
    Long yieldedSlot = yielded.agent().workspaceRowId();
    liveness.markLive(runningSlot);
    liveness.markLive(yieldedSlot);
    service.reconcile();
    assertTrue(service.isStarted(UUID.fromString(runningId)));
    releaseWrapperOf(runningId);
    releaseWrapperOf(yieldedId);
    setRunState(yieldedId, AgentRunState.YIELDED);

    AgentService.Dispatch toRunning = dispatch("w-revive-running", true, "verify the fix");
    AgentService.Dispatch toYielded = dispatch("w-revive-yielded", true, "verify it too");
    assertEquals(AgentService.AgentLaunch.SCHEDULED, toRunning.agentLaunch(), "not skipped");
    service.reconcile();

    assertTrue(
        daemon.callsFor(runningId)
            .contains("turn " + runningSlot + " " + runningId + " verify the fix"));
    assertEquals(AgentRunState.RUNNING, row(yieldedId).runState, "the yielded agent resumed");
    assertTrue(
        daemon.callsFor(yieldedId)
            .contains("turn " + yieldedSlot + " " + yieldedId + " verify it too"));
    assertEquals(yieldedId, toYielded.agent().agentId());
    liveness.markDead(runningSlot);
    liveness.markDead(yieldedSlot);
  }

  @Test
  void anObsoleteAgentOnAnotherWrapperBranchGetsANewAgentBesideIt() {
    AgentService.Dispatch first = dispatch("w-other-branch", true, null);
    String agentId = first.agent().agentId();
    releaseWrapperOf(agentId);

    AgentService.Dispatch other =
        service.dispatch(
            new AgentService.AgentRequest(
                repoId,
                "epic/w-other-branch",
                "w-other-branch",
                "w-other-branch",
                null,
                "w-other-branch",
                true,
                null,
                "go",
                new EntityFacts("A title", "REFINED", false)));

    assertTrue(other.fresh());
    assertNotEquals(agentId, other.agent().agentId());
    assertEquals(AgentState.OBSOLETE, row(agentId).state, "the old one is left as it was");
  }

  @Test
  void aDeliveryForAnItemNobodyWorksOnSaysSoAndCreatesNothing() {
    AgentService.Delivery delivery = service.deliver("w-nobody", "hello", false);
    assertNull(delivery.agentId());
    assertFalse(delivery.delivered() || delivery.launched() || delivery.resumed());
  }

  /** A TARGET slot of this test's wrapper, made by hand: no lease runs it, so nothing starts. */
  private Long runnerSlot() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = repoId;
              row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
              row.status = WorkspaceStatus.ACTIVE;
              row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              row.placement = WorkspacePlacement.TARGET;
              workspaces.persist(row);
              return row.id;
            });
  }

  /** An agent row of this test's wrapper, put straight into {@code slot} (null: queued). */
  private UUID agentIn(Long slot, boolean admin, AgentRunState runState) {
    return agentIn(slot, admin, runState, Instant.now());
  }

  private UUID agentIn(Long slot, boolean admin, AgentRunState runState, Instant createdAt) {
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
              row.createdAt = createdAt;
              row.gitRefs = "[]";
              agents.persist(row);
            });
    return id;
  }

  private void setRunState(String agentId, AgentRunState runState) {
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(agentId)).runState = runState);
  }

  @Test
  void aYieldOnlyFreesTheSlotAndTheHarnessKeepsRunning() throws InterruptedException {
    AgentService.Dispatch first = dispatch("w-first", true, null);
    String firstId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    assertTrue(service.isStarted(UUID.fromString(firstId)));
    // The scheduler coalesces a kick per dispatch/reconnect and runs it on its own thread; let any
    // still-pending pass from this or an earlier test settle before hand-queuing below, or a stray
    // placeQueued() could place that agent itself and starve this test's own yieldBlocked() call.
    Thread.sleep(100);

    // An admin agent always gets a new slot, so queue one by hand to stand for a full target.
    agentIn(null, true, AgentRunState.QUEUED);
    // The first agent has waited for input since long before the yield time, with no frame since.
    backdateQuiet(firstId, Duration.ofHours(1));
    List<String> before = daemon.callsFor(firstId);

    service.yieldBlocked();

    assertEquals(AgentRunState.YIELDED, row(firstId).runState);
    assertEquals(before, daemon.callsFor(firstId), "a yield makes no daemon call");
    assertTrue(service.isStarted(UUID.fromString(firstId)), "the harness keeps running");

    // Spoken to, the yielded agent runs again at once and hears it now.
    AgentService.Delivery delivery = service.deliver("w-first", "your answer", false);
    assertTrue(delivery.resumed());
    assertEquals(
        "The agent had yielded its workspace slot while it waited for input; it runs again and"
            + " hears this now.",
        delivery.detail());
    service.reconcile();
    assertEquals(AgentRunState.RUNNING, row(firstId).runState);
    assertTrue(daemon.callsFor(firstId).contains("turn " + slot + " " + firstId + " your answer"));
    liveness.markDead(slot);
  }

  /** The agent waited for input since {@code ago}, and nothing came since: no frame, no turn. */
  private void backdateQuiet(String agentId, Duration ago) {
    Instant at = Instant.now().minus(ago);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(agentId));
              row.waitingSince = at;
              row.lastActivityAt = at;
            });
  }

  @Test
  void aTurnJustTypedIntoAWaitingAgentIsNotYieldedFromUnderIt() throws InterruptedException {
    // qits-1511: typing no longer clears waitingSince, so the yield must not read the old clock in
    // the seconds before the turn's UserPromptSubmit arrives.
    AgentService.Dispatch first = dispatch("w-typed-yield", true, null);
    String firstId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      assertTrue(service.isStarted(UUID.fromString(firstId)));
      Thread.sleep(100);
      backdateQuiet(firstId, Duration.ofHours(1));
      service.deliver("w-typed-yield", "your answer", false);
      service.reconcile();
      assertTrue(
          daemon.callsFor(firstId).contains("turn " + slot + " " + firstId + " your answer"));
      assertNull(row(firstId).pendingText, "said");
      assertNotNull(row(firstId).waitingSince, "typed is not taken");

      agentIn(null, true, AgentRunState.QUEUED);
      service.yieldBlocked();
      assertEquals(AgentRunState.RUNNING, row(firstId).runState, "not yielded right after its turn");

      // The harness swallowed the turn: no BUSY frame came, and it is quiet again. It yields.
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  agents.findById(UUID.fromString(firstId)).lastActivityAt =
                      Instant.now().minusSeconds(3600));
      service.yieldBlocked();
      assertEquals(AgentRunState.YIELDED, row(firstId).runState);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aYieldedAgentWithATurnRunsAgainBesideAnotherRunningAgent() {
    AgentService.Dispatch first = dispatch("w-beside", true, null);
    String firstId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    // It yielded, and another agent took its slot and runs there.
    setRunState(firstId, AgentRunState.YIELDED);
    UUID other = agentIn(slot, true, AgentRunState.RUNNING);

    service.deliver("w-beside", "your answer", false);
    service.reconcile();

    assertEquals(AgentRunState.RUNNING, row(firstId).runState);
    assertEquals(AgentRunState.RUNNING, row(other.toString()).runState);
    assertTrue(daemon.callsFor(firstId).contains("turn " + slot + " " + firstId + " your answer"));
    assertEquals(
        1,
        daemon.starts().stream().filter(s -> s.agentId().equals(firstId)).count(),
        "it hears the turn in the harness it kept; it is not started again");
    liveness.markDead(slot);
  }

  private long startsOf(String agentId) {
    return daemon.starts().stream().filter(s -> s.agentId().equals(agentId)).count();
  }

  private AgentDaemon.Start lastStartOf(String agentId) {
    List<AgentDaemon.Start> starts =
        daemon.starts().stream().filter(s -> s.agentId().equals(agentId)).toList();
    return starts.get(starts.size() - 1);
  }

  /**
   * A reconnect forgets which agents were started on the daemon (qits-1212). A yielded agent keeps
   * its harness, so it is started again like a running one — with no turn and still YIELDED — and
   * hears entity updates again. Woken, it hears its turn in that harness: no second start.
   */
  @Test
  void aYieldedAgentIsStartedAgainAfterAReconnectAndIsNeverStartedTwice()
      throws InterruptedException {
    AgentService.Dispatch dispatched = dispatch("w-reconnect", true, null);
    String agentId = dispatched.agent().agentId();
    UUID id = UUID.fromString(agentId);
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    assertEquals(1, startsOf(agentId));
    Thread.sleep(100);
    setRunState(agentId, AgentRunState.YIELDED);

    service.onDaemonConnection(slot);
    service.reconcile();

    assertTrue(service.isStarted(id), "the yielded agent holds a harness on the new connection");
    assertEquals(2, startsOf(agentId));
    assertNull(lastStartOf(agentId).instruction(), "a start-only carries no turn");
    assertEquals("session-" + agentId, lastStartOf(agentId).sessionId(), "it resumes its session");
    assertEquals(AgentRunState.YIELDED, row(agentId).runState, "started, not woken");

    // Entity updates reach it again.
    AgentService.EntityMark mark =
        service.markEntity("w-reconnect", new EntityFacts("A title", "IMPLEMENTED", false), null, null);
    assertTrue(mark.applied());
    assertTrue(daemon.callsFor(agentId).contains("entity " + slot + " " + agentId + " IMPLEMENTED"));

    // Another pass starts nothing: it is started on this connection.
    service.reconcile();
    assertEquals(2, startsOf(agentId));

    // Woken, it hears the turn in the harness it holds.
    service.deliver("w-reconnect", "your answer", false);
    service.reconcile();
    assertEquals(AgentRunState.RUNNING, row(agentId).runState);
    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " your answer"));
    assertNull(row(agentId).pendingText);
    assertEquals(2, startsOf(agentId), "no agent ever gets a second harness from this service");
    liveness.markDead(slot);
  }

  /**
   * A restart of this service starts with nothing started (qits-1212). The drive starts a placed
   * yielded agent again and leaves its pending turn on the row, for the resume to say once it is
   * RUNNING: a start-only path never takes it.
   */
  @Test
  void aServiceRestartStartsAYieldedAgentAgainAndLeavesItsTurnPending()
      throws InterruptedException {
    AgentService.Dispatch dispatched = dispatch("w-restart", true, null);
    String agentId = dispatched.agent().agentId();
    UUID id = UUID.fromString(agentId);
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    Thread.sleep(100);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(id);
              row.runState = AgentRunState.YIELDED;
              row.pendingText = "a wake";
            });

    service.forgetStarted();
    assertFalse(service.isStarted(id));
    service.driveRunning();

    assertTrue(service.isStarted(id));
    assertEquals(2, startsOf(agentId));
    assertNull(lastStartOf(agentId).instruction());
    AgentWorktree after = row(agentId);
    assertEquals(AgentRunState.YIELDED, after.runState);
    assertEquals("a wake", after.pendingText, "the pending turn is the resume's to say");
    assertFalse(
        daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("turn ")),
        "a start-only path says nothing");

    // The resume makes it RUNNING and the drive says the turn, in the harness it holds.
    service.reconcile();
    assertEquals(AgentRunState.RUNNING, row(agentId).runState);
    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " a wake"));
    assertNull(row(agentId).pendingText);
    assertEquals(2, startsOf(agentId));
    liveness.markDead(slot);
  }

  /**
   * A restart of this service while the daemon keeps the harness (qits-1225): the drive starts the
   * running agent again, the daemon attaches to the harness and ignores the instruction, so the
   * pending turn stays on the row and the next pass says it as a turn.
   */
  @Test
  void aServiceRestartWithASurvivingHarnessSaysThePendingTurnAsATurn()
      throws InterruptedException {
    AgentService.Dispatch dispatched = dispatch("w-attach", true, null);
    String agentId = dispatched.agent().agentId();
    UUID id = UUID.fromString(agentId);
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    assertEquals(1, startsOf(agentId));
    Thread.sleep(100);
    setPendingText(id, "a turn");

    service.forgetStarted();
    service.driveRunning();

    assertTrue(service.isStarted(id));
    assertEquals(2, startsOf(agentId));
    assertEquals("a turn", lastStartOf(agentId).instruction(), "the start still offers it");
    assertEquals("a turn", row(agentId).pendingText, "an attach launched nothing: still pending");
    assertFalse(
        daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("turn ")),
        "the start's pass says nothing more");

    service.driveRunning();

    assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " a turn"));
    assertNull(row(agentId).pendingText);
    assertEquals(2, startsOf(agentId), "said in the harness it kept: no third start");
    liveness.markDead(slot);
  }

  /**
   * A daemon restart takes the harness (qits-1225): the start launches a new one with the pending
   * turn as its opening turn, the row's text is cleared and no separate turn follows.
   */
  @Test
  void aDaemonRestartStartsTheAgentWithItsPendingTurn() throws InterruptedException {
    AgentService.Dispatch dispatched = dispatch("w-relaunch", true, null);
    String agentId = dispatched.agent().agentId();
    UUID id = UUID.fromString(agentId);
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    service.reconcile();
    assertEquals(1, startsOf(agentId));
    Thread.sleep(100);
    setPendingText(id, "a turn");

    daemon.restart();
    service.forgetStarted();
    service.driveRunning();

    assertTrue(service.isStarted(id));
    assertEquals(2, startsOf(agentId));
    assertEquals("a turn", lastStartOf(agentId).instruction());
    assertNull(row(agentId).pendingText, "the new harness took it as its opening turn");

    service.driveRunning();

    assertFalse(
        daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("turn ")),
        "the turn is not said twice");
    assertEquals(2, startsOf(agentId));
    liveness.markDead(slot);
  }

  private void setPendingText(UUID id, String text) {
    QuarkusTransaction.requiringNew().run(() -> agents.findById(id).pendingText = text);
  }

  /** Only a written yield is a yield (qits-1212): a row that is no longer RUNNING yields nothing. */
  @Test
  void aYieldIsWrittenOnlyOverARunningAgent() {
    Long slot = runnerSlot();
    UUID running = agentIn(slot, false, AgentRunState.RUNNING);
    UUID yielded = agentIn(slot, false, AgentRunState.YIELDED);

    assertTrue(service.writeYield(running));
    assertEquals(AgentRunState.YIELDED, row(running.toString()).runState);
    assertFalse(service.writeYield(running), "a second write finds it yielded already");
    assertFalse(service.writeYield(yielded));
    assertFalse(service.writeYield(UUID.randomUUID()), "no row, no yield");
  }

  @Test
  void aYieldedAgentThatWorksAgainIsRunningAgain() {
    Long slot = runnerSlot();
    UUID id = agentIn(slot, false, AgentRunState.YIELDED);

    service.onActivity(row(id.toString()).workspaceId, id.toString(), Boolean.TRUE, "s-1", "Stop", System.currentTimeMillis());
    assertEquals(AgentRunState.YIELDED, row(id.toString()).runState, "still waiting: still yielded");

    service.onActivity(
        row(id.toString()).workspaceId,
        id.toString(),
        Boolean.FALSE,
        "s-1",
        "UserPromptSubmit",
        System.currentTimeMillis());
    assertEquals(AgentRunState.RUNNING, row(id.toString()).runState);
  }

  @Test
  void aFrameFromAnotherWorkspacesDaemonChangesNothing() {
    Long slot = runnerSlot();
    UUID id = agentIn(slot, false, AgentRunState.YIELDED);

    service.onActivity(
        slot + 1000, id.toString(), Boolean.FALSE, "s-x", "UserPromptSubmit", System.currentTimeMillis());

    AgentWorktree after = row(id.toString());
    assertEquals(AgentRunState.YIELDED, after.runState);
    assertNotEquals("s-x", after.sessionId);
  }

  @Test
  void aQueuedAgentTakesOnlyAnEmptyOrAnAllYieldedWorkspace() {
    Long slot = runnerSlot();
    UUID running = agentIn(slot, false, AgentRunState.RUNNING);
    UUID queued = agentIn(null, false, AgentRunState.QUEUED);
    UUID second = agentIn(null, false, AgentRunState.QUEUED);

    service.placeQueued();
    assertEquals(AgentRunState.QUEUED, row(queued.toString()).runState, "its agent runs");

    setRunState(running.toString(), AgentRunState.YIELDED);
    service.placeQueued();

    AgentWorktree placed = row(queued.toString());
    assertEquals(AgentRunState.RUNNING, placed.runState);
    assertEquals(slot, placed.workspaceId);
    assertEquals(
        AgentRunState.QUEUED,
        row(second.toString()).runState,
        "the slot is taken again: one pass does not book it twice");
  }

  @Test
  void aQueuedAgentSkipsAWorkspaceWhoseYieldedAgentHasATurnPending() {
    Long slot = runnerSlot();
    UUID yielded = agentIn(slot, false, AgentRunState.YIELDED);
    QuarkusTransaction.requiringNew().run(() -> agents.findById(yielded).pendingText = "a wake");
    UUID queued = agentIn(null, false, AgentRunState.QUEUED);

    service.placeQueued();
    assertEquals(
        AgentRunState.QUEUED,
        row(queued.toString()).runState,
        "the yielded agent is about to resume: its slot is not all-yielded");
    assertNull(row(queued.toString()).workspaceId);

    QuarkusTransaction.requiringNew().run(() -> agents.findById(yielded).pendingText = null);
    service.placeQueued();
    assertEquals(AgentRunState.RUNNING, row(queued.toString()).runState);
    assertEquals(slot, row(queued.toString()).workspaceId);
  }

  @Test
  void aQueuedAgentIsNeverPlacedWhereALiveAgentHoldsItsWrapperBranch() {
    Long slot = runnerSlot();
    // An OBSOLETE agent that yielded: the slot reads as all-yielded, but its worktree holds the branch.
    UUID obsolete = agentIn(slot, false, AgentRunState.YIELDED);
    String branch = row(obsolete.toString()).wrapperBranch;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(obsolete);
              row.state = AgentState.OBSOLETE;
              row.obsoleteAt = Instant.now();
            });
    UUID sameBranch = agentIn(null, false, AgentRunState.QUEUED);
    QuarkusTransaction.requiringNew().run(() -> agents.findById(sameBranch).wrapperBranch = branch);
    UUID otherBranch = agentIn(null, false, AgentRunState.QUEUED);

    service.placeQueued();

    AgentWorktree waiting = row(sameBranch.toString());
    assertEquals(AgentRunState.QUEUED, waiting.runState, "git would refuse it the branch there");
    assertNull(waiting.workspaceId);
    AgentWorktree placed = row(otherBranch.toString());
    assertEquals(AgentRunState.RUNNING, placed.runState, "another branch takes the slot");
    assertEquals(slot, placed.workspaceId);
  }

  @Test
  void aRefusedStartIsStoredOnTheAgentAndAnAcceptedOneClearsIt() {
    AgentService.Dispatch dispatched = dispatch("w-refused", true, "go");
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    String detail = "fatal: 'ticket/w-refused' is already checked out at '/workspace/agents/x'";
    daemon.refuseStarts(detail);
    try {
      liveness.markLive(slot);
      service.reconcile();

      AgentWorktree refused = row(agentId);
      assertEquals(detail, refused.startRefusal);
      assertNotNull(refused.startRefusedAt);
      assertEquals(AgentRunState.RUNNING, refused.runState, "the scheduler keeps trying");
      AgentDto view = service.get(UUID.fromString(agentId));
      assertEquals(detail, view.startRefusal());
      assertEquals(refused.startRefusedAt, view.startRefusedAt());

      service.reconcile();
      assertEquals(
          refused.startRefusedAt,
          row(agentId).startRefusedAt,
          "the same refusal again is not a new one");

      daemon.acceptStarts();
      service.reconcile();

      AgentWorktree accepted = row(agentId);
      assertTrue(service.isStarted(UUID.fromString(agentId)));
      assertNull(accepted.startRefusal);
      assertNull(accepted.startRefusedAt);
      assertNull(service.get(UUID.fromString(agentId)).startRefusal());
    } finally {
      daemon.acceptStarts();
      liveness.markDead(slot);
    }
  }

  /**
   * An admin slot of this test's wrapper, made by hand, that never came up: FAILED with the
   * runner's wording, status still ACTIVE (qits-1233). By hand, because a slot a pass creates is
   * started in the background, and that start would overwrite the status.
   */
  private Long failedSlot() {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Workspace row = new Workspace();
              row.repositoryId = repoId;
              row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
              row.status = WorkspaceStatus.ACTIVE;
              row.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
              row.runtimeError = "no workspace-daemon dialed home within 30000ms";
              row.placement = WorkspacePlacement.DIRECT;
              row.admin = true;
              workspaces.persist(row);
              return row.id;
            });
  }

  @Test
  void anAgentWhoseWorkspaceNeverCameUpIsQueuedForAnotherAndSaysWhy() {
    // qits-1233: the workspace went FAILED (status still ACTIVE) and the agent stayed RUNNING in
    // it, never started and with no refusal, until a person discarded it.
    Long failed = failedSlot();
    UUID id = agentIn(failed, true, AgentRunState.RUNNING);
    createdAgents.add(id);
    String agentId = id.toString();

    service.reconcile();

    AgentWorktree requeued = row(agentId);
    assertNotEquals(failed, requeued.workspaceId, "taken out of the workspace that never came up");
    assertNotNull(requeued.startRefusal, "its view says why it does not run");
    assertTrue(requeued.startRefusal.contains("did not come up"), requeued.startRefusal);
    assertTrue(
        requeued.startRefusal.contains("no workspace-daemon dialed home"), requeued.startRefusal);

    service.reconcile();

    AgentWorktree placed = row(agentId);
    assertEquals(AgentRunState.RUNNING, placed.runState);
    assertNotNull(placed.workspaceId);
    assertNotEquals(failed, placed.workspaceId, "the empty FAILED workspace is offered as no slot");

    // The FAILED slot, empty now, is retired once past the orphan grace (created_at is not
    // updatable through the entity).
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .getEntityManager()
                    .createNativeQuery("update workspace set created_at = ?1 where id = ?2")
                    .setParameter(1, Instant.now().minusSeconds(3600))
                    .setParameter(2, failed)
                    .executeUpdate());
    service.removeEmptyWorkspaces();
    assertEquals(WorkspaceStatus.ABANDONED, workspace(failed).status);

    // A second workspace that fails in a row: queued again, but not placed again at once.
    Long second = failedSlot();
    QuarkusTransaction.requiringNew().run(() -> agents.findById(id).workspaceId = second);
    service.reconcile();
    service.reconcile();

    AgentWorktree held = row(agentId);
    assertEquals(AgentRunState.QUEUED, held.runState, "a runner that keeps failing is not hammered");
    assertNull(held.workspaceId);
    assertTrue(held.startRefusal.contains("failure 2 in a row"), held.startRefusal);
  }

  @Test
  void anAgentWhoseWorktreeIsInTheFailedWorkspaceWaitsForItAndSaysWhyOnce() {
    // qits-1233: moving it would let the empty slot be discarded, volume and worktree with it.
    Long failed = failedSlot();
    UUID startedThere = agentIn(failed, true, AgentRunState.RUNNING);
    UUID legacy = agentIn(failed, true, AgentRunState.RUNNING);
    createdAgents.add(startedThere);
    createdAgents.add(legacy);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              agents.findById(startedThere).startedWorkspaceId = failed;
              agents.findById(startedThere).commandId = "cmd-earlier";
              // A row from before started_workspace_id: started somewhere, recorded nowhere.
              agents.findById(legacy).commandId = "cmd-earlier";
            });

    service.reconcile();

    for (UUID id : List.of(startedThere, legacy)) {
      AgentWorktree waiting = row(id.toString());
      assertEquals(failed, waiting.workspaceId, "it stays where its worktree is");
      assertEquals(AgentRunState.RUNNING, waiting.runState);
      assertNotNull(waiting.startRefusal);
      assertTrue(
          waiting.startRefusal.contains("the agent's worktree is there"), waiting.startRefusal);
    }
    Instant first = row(startedThere.toString()).startRefusedAt;
    service.reconcile();
    assertEquals(first, row(startedThere.toString()).startRefusedAt, "said once, not every pass");
  }

  @Test
  void anAcceptedStartRecordsWhereTheWorktreeLives() {
    AgentService.Dispatch dispatched = dispatch("w-started-where", true, "go");
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      assertEquals(slot, row(dispatched.agent().agentId()).startedWorkspaceId);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aReleasedBranchMakesItsAgentsObsoleteAndTheSweepRemovesThemOnceVerifiedAndIdle() {
    AgentService.Dispatch dispatched = dispatch("w-release", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);

    AgentService.Resolution resolution =
        service.resolveReleasedBranch(repoId, "ticket/w-release", "2026.1.1", "abc", null);
    assertTrue(resolution.resolved());
    assertEquals(List.of(agentId), resolution.agentIds());
    assertEquals(AgentState.OBSOLETE, row(agentId).state);

    // Not VERIFIED yet: nothing is removed.
    liveness.markLive(slot);
    service.reconcile(); // deterministically past the agent's start, so it stops touching lastActivityAt
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));
    service.cleanUp();
    assertEquals(AgentState.OBSOLETE, row(agentId).state);

    service.markEntity("w-release", new EntityFacts("A title", "VERIFIED", false), null, null);
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
  void aReleasedSubmoduleBranchTheAgentPushedMarksNobody() {
    // qits-1227: only the release of an agent's wrapper branch ends its work; a submodule branch it
    // pushed is released while the item goes on.
    AgentService.Dispatch dispatched = dispatch("w-pushed", true, null);
    String agentId = dispatched.agent().agentId();
    UUID id = UUID.fromString(agentId);
    QuarkusTransaction.requiringNew()
        .run(() -> branches.upsert(id, "service-repo", "ticket/w-pushed-fix", "abc", Instant.now()));

    AgentService.Resolution resolution =
        service.resolveReleasedBranch("service-repo", "ticket/w-pushed-fix", "2026.1.1", "abc", null);

    assertFalse(resolution.resolved());
    assertEquals(List.of(), resolution.agentIds());
    assertEquals(AgentState.ACTIVE, row(agentId).state);
  }

  @ParameterizedTest
  @ValueSource(strings = {"DONE", "DROPPED"})
  void theSweepRemovesAnObsoleteAgentWhoseItemIsDoneOrDropped(String status) {
    // qits-1219: an item does not only close as VERIFIED; DONE and DROPPED end it too.
    String workId = "w-" + status.toLowerCase(Locale.ROOT);
    AgentService.Dispatch dispatched = dispatch(workId, true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);
    releaseWrapperOf(agentId);

    service.markEntity(workId, new EntityFacts("A title", status, false), null, null);
    liveness.markLive(slot);
    service.reconcile(); // deterministically past the agent's start, so it stops touching lastActivityAt
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));
    service.cleanUp();

    assertEquals(AgentState.REMOVED, row(agentId).state);
    liveness.markDead(slot);
  }

  /**
   * An ACTIVE, started agent whose item is then marked {@code status}, idle for an hour, with
   * {@code script} run against the daemon first. No release: nothing but the status moves it.
   */
  private String closedUnreleased(String workId, String status, Consumer<String> script) {
    AgentService.Dispatch dispatched = dispatch(workId, true, null);
    String agentId = dispatched.agent().agentId();
    liveness.markLive(dispatched.agent().workspaceRowId());
    service.reconcile(); // deterministically past the agent's start
    script.accept(agentId);
    service.markEntity(workId, new EntityFacts(null, status, false), null, null);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agents.findById(UUID.fromString(agentId)).lastActivityAt =
                    Instant.now().minusSeconds(3600));
    return agentId;
  }

  private List<AgentObsolete> obsoleteEventsOf(String agentId) {
    return announced.of(AgentObsolete.class, e -> e.agentId().equals(agentId));
  }

  @Test
  void aDroppedItemsIdleCleanAgentIsRemovedWithoutARelease() {
    // qits-1276: only a release made an agent OBSOLETE, and a dropped item's branch is never
    // released, so its agent stayed ACTIVE for good, holding its slot.
    String agentId = closedUnreleased("w-dropped-clean", "DROPPED", id -> {});
    Long slot = row(agentId).workspaceId;
    try {
      service.reconcile();

      AgentWorktree removed = row(agentId);
      assertEquals(AgentState.REMOVED, removed.state);
      assertNotNull(removed.obsoleteAt, "made OBSOLETE first, by the drop");
      assertTrue(
          daemon.callsFor(agentId).contains("remove " + slot + " " + agentId + " force=false"));
      AgentRemoved event =
          announced.of(AgentRemoved.class, e -> e.agentId().equals(agentId)).get(0);
      assertEquals(AgentRemoved.DROPPED_IDLE_CLEAN, event.reason());
      assertEquals("dropped, idle and clean", event.detail());
      List<AgentObsolete> obsolete = obsoleteEventsOf(agentId);
      assertEquals(1, obsolete.size(), obsolete.toString());
      assertEquals("ticket/w-dropped-clean", obsolete.get(0).branch(), "its wrapper branch");
      assertNull(obsolete.get(0).version(), "nothing was released");
      assertNull(obsolete.get(0).commitSha());
      assertEquals(slot.toString(), obsolete.get(0).workspaceId());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aDroppedItemsAgentWithLeftoverWorkIsPromptedWithTheDroppedWording() {
    String agentId =
        closedUnreleased(
            "w-dropped-dirty",
            "DROPPED",
            id ->
                daemon.leftover(
                    id, new AgentDaemon.CleanupCheck(false, List.of("wrapper"), List.of())));
    Long slot = row(agentId).workspaceId;
    try {
      service.reconcile();

      AgentWorktree prompted = row(agentId);
      assertEquals(AgentState.OBSOLETE, prompted.state, "nothing is removed");
      assertNotNull(prompted.cleanupPromptedAt);
      String said =
          prompted.pendingText != null
              ? prompted.pendingText
              : daemon.callsFor(agentId).stream()
                  .filter(c -> c.startsWith("turn "))
                  .reduce((a, b) -> b)
                  .orElse("");
      assertTrue(said.contains(AgentService.DROPPED_CLEANUP_PROMPT), said);
      assertFalse(said.contains(AgentService.CLEANUP_PROMPT), "not the released wording: " + said);
      assertTrue(said.contains("uncommitted changes in wrapper"), said);
      assertFalse(
          daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")),
          "leftover work is never deleted");
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aRowAlreadyStuckOnDroppedIsHealedByAPassAlone() {
    // qits-1276: the rows that were stuck before the fix took their DROPPED long ago; no new mark
    // will come for them.
    AgentService.Dispatch dispatched = dispatch("w-dropped-stuck", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                AgentWorktree row = agents.findById(UUID.fromString(agentId));
                row.entityStatus = "dropped"; // any case
                row.lastActivityAt = Instant.now().minusSeconds(3600);
              });

      service.reconcile();

      assertEquals(AgentState.REMOVED, row(agentId).state);
      assertEquals(1, obsoleteEventsOf(agentId).size());
    } finally {
      liveness.markDead(slot);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"VERIFIED", "DONE"})
  void anUnreleasedAgentOfAVerifiedOrDoneItemStaysActive(String status) {
    // Only a drop obsoletes without a release: a VERIFIED or DONE item's agent still has its own
    // work to release.
    String agentId =
        closedUnreleased("w-unreleased-" + status.toLowerCase(Locale.ROOT), status, id -> {});
    Long slot = row(agentId).workspaceId;
    try {
      service.reconcile();

      AgentWorktree left = row(agentId);
      assertEquals(AgentState.ACTIVE, left.state);
      assertNull(left.obsoleteAt);
      assertEquals(List.of(), obsoleteEventsOf(agentId));
      assertFalse(daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")));
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aDroppedItemReopenedAndDispatchedAgainRevivesItsAgentAndKeepsIt() {
    // qits-1276: a revived agent's row may still read DROPPED (a dispatch whose facts name no
    // status); the pass must not obsolete it straight back.
    AgentService.Dispatch first = dispatch("w-reopened", true, "first phase");
    String agentId = first.agent().agentId();
    Long slot = first.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      service.markEntity("w-reopened", new EntityFacts(null, "DROPPED", false), null, null);
      service.reconcile();
      assertEquals(
          AgentState.OBSOLETE, row(agentId).state, "the drop obsoletes it; active lately, so kept");

      AgentService.Dispatch again =
          service.dispatch(
              new AgentService.AgentRequest(
                  repoId,
                  "ticket/w-reopened",
                  "w-reopened",
                  "w-reopened",
                  "w-reopened",
                  null,
                  true,
                  null,
                  "reopened: go on",
                  null));

      assertEquals(agentId, again.agent().agentId(), "the same agent, revived");
      assertFalse(again.fresh());
      AgentWorktree revived = row(agentId);
      assertEquals(AgentState.ACTIVE, revived.state);
      assertNotEquals("DROPPED", revived.entityStatus, "the stale word is gone");
      assertEquals(1, liveRowsOf("w-reopened"));

      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  agents.findById(UUID.fromString(agentId)).lastActivityAt =
                      Instant.now().minusSeconds(3600));
      service.reconcile();

      assertEquals(AgentState.ACTIVE, row(agentId).state, "stays ACTIVE across a pass");
      assertEquals(1, obsoleteEventsOf(agentId).size(), "obsoleted once, by the drop only");

      // The item's next mark says what it is now; still ACTIVE.
      service.markEntity("w-reopened", new EntityFacts(null, "REPORTED", false), null, null);
      service.reconcile();
      assertEquals(AgentState.ACTIVE, row(agentId).state);
      assertEquals("REPORTED", row(agentId).entityStatus);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aDroppedItemsRecentlyActiveAgentIsObsoleteButNotRemovedThatPass() {
    AgentService.Dispatch dispatched = dispatch("w-dropped-recent", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      service.markEntity("w-dropped-recent", new EntityFacts(null, "DROPPED", false), null, null);
      QuarkusTransaction.requiringNew()
          .run(() -> agents.findById(UUID.fromString(agentId)).lastActivityAt = Instant.now());

      service.reconcile();

      assertEquals(AgentState.OBSOLETE, row(agentId).state, "obsolete, but still standing");
      assertFalse(daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")));
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aDroppedItemsAgentBusyInTheBackgroundIsObsoleteButNotRemoved() {
    String agentId =
        closedUnreleased(
            "w-dropped-busy",
            "DROPPED",
            id ->
                daemon.leftover(
                    id, new AgentDaemon.CleanupCheck(false, List.of("wrapper"), List.of(), true)));
    Long slot = row(agentId).workspaceId;
    try {
      service.reconcile();

      AgentWorktree left = row(agentId);
      assertEquals(AgentState.OBSOLETE, left.state);
      assertNull(left.cleanupPromptedAt, "no cleanup prompt typed into a working agent");
      assertFalse(daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")));
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aNonClosingStatusStillRemovesNothing() {
    AgentService.Dispatch dispatched = dispatch("w-implemented", true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    UUID id = UUID.fromString(agentId);
    releaseWrapperOf(agentId);

    service.markEntity("w-implemented", new EntityFacts("A title", "IMPLEMENTED", false), null, null);
    liveness.markLive(slot);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(id).lastActivityAt = Instant.now().minusSeconds(3600));
    service.cleanUp();

    assertEquals(AgentState.OBSOLETE, row(agentId).state, "IMPLEMENTED does not close the item");
    liveness.markDead(slot);
  }

  @Test
  void anItemWithTwoObsoleteAgentsMarksBothAndTheSweepRemovesBoth() {
    // qits-1219: `mark` used to write only the one row `findLiveByWorkId` picked, leaving an
    // older OBSOLETE agent's entityStatus frozen forever.
    AgentService.Dispatch first = dispatch("w-two-obsolete", true, null);
    String firstId = first.agent().agentId();
    Long firstSlot = first.agent().workspaceRowId();
    releaseWrapperOf(firstId);

    AgentService.Dispatch second =
        service.dispatch(
            new AgentService.AgentRequest(
                repoId,
                "epic/w-two-obsolete",
                "w-two-obsolete",
                "w-two-obsolete",
                null,
                "w-two-obsolete",
                true,
                null,
                "go",
                new EntityFacts("A title", "REFINED", false)));
    String secondId = second.agent().agentId();
    Long secondSlot = second.agent().workspaceRowId();
    assertNotEquals(firstId, secondId);
    releaseWrapperOf(secondId);
    assertEquals(2, liveRowsOf("w-two-obsolete"), "both agents stand, both OBSOLETE");

    service.markEntity("w-two-obsolete", new EntityFacts("A title", "DONE", false), null, null);

    assertEquals("DONE", row(firstId).entityStatus, "the older row is not left behind");
    assertEquals("DONE", row(secondId).entityStatus);

    liveness.markLive(firstSlot);
    liveness.markLive(secondSlot);
    service.reconcile(); // deterministically past both agents' starts
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              agents.findById(UUID.fromString(firstId)).lastActivityAt =
                  Instant.now().minusSeconds(3600);
              agents.findById(UUID.fromString(secondId)).lastActivityAt =
                  Instant.now().minusSeconds(3600);
            });
    service.cleanUp();

    assertEquals(AgentState.REMOVED, row(firstId).state);
    assertEquals(AgentState.REMOVED, row(secondId).state);
    liveness.markDead(firstSlot);
    liveness.markDead(secondSlot);
  }

  @Test
  void aStaleOlderObsoleteRowIsHealedByTheItemsCurrentStatus() {
    // qits-1219: judged by the item's CURRENT status (the newest live row), not the agent's own
    // possibly-frozen entityStatus.
    AgentService.Dispatch first = dispatch("w-stale", true, null);
    String firstId = first.agent().agentId();
    Long firstSlot = first.agent().workspaceRowId();
    releaseWrapperOf(firstId);

    AgentService.Dispatch second =
        service.dispatch(
            new AgentService.AgentRequest(
                repoId,
                "epic/w-stale",
                "w-stale",
                "w-stale",
                null,
                "w-stale",
                true,
                null,
                "go",
                new EntityFacts("A title", "REFINED", false)));
    String secondId = second.agent().agentId();
    Long secondSlot = second.agent().workspaceRowId();
    releaseWrapperOf(secondId);

    // The older row is stale, frozen on an in-progress status; the newer one is DONE.
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              agents.findById(UUID.fromString(firstId)).entityStatus = "IMPLEMENTING";
              agents.findById(UUID.fromString(secondId)).entityStatus = "DONE";
            });

    liveness.markLive(firstSlot);
    liveness.markLive(secondSlot);
    service.reconcile(); // deterministically past both agents' starts
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              agents.findById(UUID.fromString(firstId)).lastActivityAt =
                  Instant.now().minusSeconds(3600);
              agents.findById(UUID.fromString(secondId)).lastActivityAt =
                  Instant.now().minusSeconds(3600);
            });

    service.cleanUp();

    assertEquals(
        AgentState.REMOVED,
        row(firstId).state,
        "healed: judged by the item's current status, not its own frozen one");
    assertEquals(AgentState.REMOVED, row(secondId).state);
    liveness.markDead(firstSlot);
    liveness.markDead(secondSlot);
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
    service.markEntity("w-dirty", new EntityFacts(null, "VERIFIED", false), null, null);
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
  void aPersonsDiscardRefusesABusyAgentUnlessForced() {
    // qits-1238: a clean check with the harness still busy in the background (a subagent, a
    // monitor, a background shell) must not be read as nothing left to do, or discard would kill a
    // harness still working.
    AgentService.Dispatch dispatched = dispatch("w-discard-busy", true, null);
    String agentId = dispatched.agent().agentId();
    daemon.leftover(agentId, new AgentDaemon.CleanupCheck(true, List.of(), List.of(), true));

    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> service.discard(UUID.fromString(agentId), false));
    assertEquals("AGENT_BUSY", refused.code());
    assertFalse(
        daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")),
        "no removal while it is busy");
    assertEquals(AgentState.ACTIVE, row(agentId).state, "still there, not removed");

    AgentDto discarded = service.discard(UUID.fromString(agentId), true);
    assertEquals(AgentState.REMOVED, discarded.state());
  }

  /** What {@link AgentService} logged while {@code run} ran, formatted. */
  private static List<String> logsOf(Runnable run) {
    List<String> logged = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(
                record.getParameters() == null
                    ? record.getMessage()
                    : String.format(record.getMessage(), record.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(AgentService.class.getName());
    logger.addHandler(capture);
    try {
      run.run();
    } finally {
      logger.removeHandler(capture);
    }
    return logged;
  }

  /**
   * An admin agent started in its slot, whose daemon stays live, then released, its item VERIFIED
   * and idle: what the sweep cleans up. {@code script} runs before the item closes, so no
   * background pass reaches the agent unscripted.
   */
  private String sweepable(String workId, Consumer<String> script) {
    AgentService.Dispatch dispatched = dispatch(workId, true, null);
    String agentId = dispatched.agent().agentId();
    liveness.markLive(dispatched.agent().workspaceRowId());
    service.reconcile();
    script.accept(agentId);
    releaseWrapperOf(agentId);
    service.markEntity(workId, new EntityFacts(null, "VERIFIED", false), null, null);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agents.findById(UUID.fromString(agentId)).lastActivityAt =
                    Instant.now().minusSeconds(3600));
    return agentId;
  }

  @Test
  void anAgentWhoseRemovalWasNeverConfirmedIsRemovedOnceItsDaemonNoLongerKnowsIt() {
    // qits-1233 (qits-1229's agent): the daemon removed the agent but its OK never arrived, and
    // every later cleanup check answered 404 — which read as "no answer", so the sweep stalled.
    // Captured from the start: a background pass kicked by the item's VERIFIED may sweep first.
    List<String> made = new ArrayList<>();
    List<String> logged =
        logsOf(
            () -> {
              made.add(
                  sweepable(
                      "w-lost", id -> daemon.removesAnswer(id, AgentDaemon.Outcome.UNREACHABLE)));
              service.cleanUp();
              service.cleanUp();
            });
    String agentId = made.get(0);
    Long slot = row(agentId).workspaceId;
    try {
      assertEquals(AgentState.OBSOLETE, row(agentId).state, "no answer to the remove: it stays");
      // A background pass may also have logged an earlier skip (not idle yet); this one is the stall.
      String stall =
          "Cleanup leaves agent " + agentId + " for now: its removal was answered UNREACHABLE";
      assertEquals(
          1,
          logged.stream().filter(stall::equals).count(),
          "the stall says why, once over two passes: " + logged);

      daemon.forget(agentId);
      service.cleanUp();

      assertEquals(AgentState.REMOVED, row(agentId).state);
      assertNull(row(agentId).commissionedTokenId, "the credential went with it");
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aPersonsDiscardOfAnAgentItsDaemonForgotNeedsNoForce() {
    AgentService.Dispatch dispatched = dispatch("w-forgotten", true, null);
    String agentId = dispatched.agent().agentId();
    daemon.forget(agentId);

    AgentDto discarded = service.discard(UUID.fromString(agentId), false);

    assertEquals(AgentState.REMOVED, discarded.state());
    assertFalse(
        daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")),
        "nothing is left to remove");
  }

  @Test
  void anAgentBusyInTheBackgroundIsNeitherRemovedNorPrompted() {
    // qits-1233 (qits-1212's agent): a background verify task ran after the main turn's Stop, and
    // the sweep removed the agent mid tool call — clean, idle by its last frame, and not BUSY.
    String agentId =
        sweepable(
            "w-busy",
            id ->
                daemon.leftover(
                    id, new AgentDaemon.CleanupCheck(false, List.of("wrapper"), List.of(), true)));
    Long slot = row(agentId).workspaceId;
    try {
      service.cleanUp();

      AgentWorktree left = row(agentId);
      assertEquals(AgentState.OBSOLETE, left.state);
      assertNull(left.cleanupPromptedAt, "no cleanup prompt typed into a working agent");
      assertNull(left.pendingText);
      assertFalse(
          daemon.callsFor(agentId).stream().anyMatch(c -> c.startsWith("remove ")),
          "no removal while it works");

      // Done, and clean: removed on the next sweep.
      daemon.leftover(agentId, new AgentDaemon.CleanupCheck(true, List.of(), List.of()));
      service.cleanUp();
      assertEquals(AgentState.REMOVED, row(agentId).state);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aRemovalRefusedBecauseTheAgentWentBusyIsNoLeftoverWork() {
    // The check said clean, then a background task started: the daemon refuses the removal with
    // a check that says busy, which is no reason to prompt the agent.
    String agentId =
        sweepable("w-went-busy", id -> daemon.removesAnswer(id, AgentDaemon.Outcome.BUSY));
    Long slot = row(agentId).workspaceId;
    try {
      service.cleanUp();

      AgentWorktree left = row(agentId);
      assertEquals(AgentState.OBSOLETE, left.state);
      assertNull(left.cleanupPromptedAt);
      assertNull(left.pendingText);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aTurnTypedIntoAWaitingAgentTellsQitsProjectsItNoLongerWaitsOnceItsPromptIsSubmitted() {
    // qits-1198: the agent waited, a phase turn was typed into it, and qits-projects kept it
    // blocked while it worked. qits-1511: the typing itself says nothing and leaves waitingSince;
    // the BUSY frame of the turn's UserPromptSubmit is the flip, and that flip is relayed.
    String workId = uniqueWorkId("w-blocked");
    AgentService.Dispatch dispatched = dispatch(workId, true, null);
    String agentId = dispatched.agent().agentId();
    Long slot = dispatched.agent().workspaceRowId();
    liveness.markLive(slot);
    try {
      service.reconcile();
      service.onActivity(row(agentId).workspaceId, agentId, Boolean.TRUE, "s-1", "Stop", System.currentTimeMillis());
      Instant waitingSince = row(agentId).waitingSince;
      assertNotNull(waitingSince);

      service.deliver(workId, "implement it", false);
      service.reconcile();
      assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " implement it"));
      AgentWorktree typed = row(agentId);
      assertNull(typed.pendingText, "said");
      assertEquals(waitingSince, typed.waitingSince, "typing does not clear waitingSince");
      assertTrue(typed.lastActivityAt.isAfter(waitingSince), "the typing restarts the quiet clock");
      List<FakeAgentWaitingReporter.Report> beforeFrame = reporter.reportsFor(workId);
      assertTrue(beforeFrame.get(beforeFrame.size() - 1).waiting(), "nothing relayed on typing");

      service.onActivity(row(agentId).workspaceId, agentId, Boolean.FALSE, "s-1", "UserPromptSubmit", System.currentTimeMillis());
      assertNull(row(agentId).waitingSince, "the BUSY frame clears it");

      List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor(workId);
      FakeAgentWaitingReporter.Report last = reports.get(reports.size() - 1);
      assertFalse(last.waiting(), "the last word qits-projects heard");
      assertEquals("UserPromptSubmit", last.cause());
    } finally {
      liveness.markDead(slot);
    }
  }

  /** A work item id no other test (or earlier run of this one) reported on. */
  private static String uniqueWorkId(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private List<FakeAgentWaitingReporter.Report> reportsWithCause(String workId, String cause) {
    return reporter.reportsFor(workId).stream().filter(r -> cause.equals(r.cause())).toList();
  }

  private void setWaiting(String agentId, String sessionId) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(agentId));
              row.waitingSince = Instant.now();
              row.sessionId = sessionId;
            });
  }

  @Test
  void discardingAWaitingAgentTellsQitsProjectsItNoLongerWaitsOnce() {
    // qits-1274: a REMOVED agent's frames are ignored, so its wait kept the item blocked for good.
    String workId = uniqueWorkId("w-removed-waiting");
    String agentId = dispatch(workId, false, null).agent().agentId();
    service.onActivity(
        row(agentId).workspaceId, agentId, Boolean.TRUE, "s-rm", "Stop", System.currentTimeMillis());
    assertNotNull(row(agentId).waitingSince);

    service.discard(UUID.fromString(agentId), true);

    List<FakeAgentWaitingReporter.Report> removed = reportsWithCause(workId, AgentService.CAUSE_REMOVED);
    assertEquals(1, removed.size(), removed.toString());
    assertFalse(removed.get(0).waiting());
    assertEquals("s-rm", removed.get(0).sessionId());
    assertNull(row(agentId).waitingSince);
    List<FakeAgentWaitingReporter.Report> all = reporter.reportsFor(workId);
    assertEquals(AgentService.CAUSE_REMOVED, all.get(all.size() - 1).cause(), "the last word");

    int before = reporter.reportsFor(workId).size();
    service.discard(UUID.fromString(agentId), true);
    assertEquals(before, reporter.reportsFor(workId).size(), "a second discard says nothing");
  }

  @Test
  void discardingAnAgentThatDoesNotWaitReportsNothing() {
    String workId = uniqueWorkId("w-removed-idle");
    String agentId = dispatch(workId, false, null).agent().agentId();
    int before = reporter.reportsFor(workId).size();

    service.discard(UUID.fromString(agentId), true);

    assertEquals(before, reporter.reportsFor(workId).size());
    assertTrue(reportsWithCause(workId, AgentService.CAUSE_REMOVED).isEmpty());
  }

  @Test
  void removingAWaitingObsoleteAgentLeavesTheCurrentAgentsWaitStanding() {
    // The cleanup of an OBSOLETE agent must not clear the wait of the agent working the item now.
    String workId = uniqueWorkId("w-removed-other");
    String obsoleteId = dispatch(workId, false, null).agent().agentId();
    releaseWrapperOf(obsoleteId);
    String activeId =
        service
            .dispatch(
                new AgentService.AgentRequest(
                    repoId,
                    "epic/" + workId,
                    workId,
                    workId,
                    null,
                    workId,
                    false,
                    null,
                    "go",
                    new EntityFacts("A title", "REFINED", false)))
            .agent()
            .agentId();
    assertNotEquals(obsoleteId, activeId);
    setWaiting(obsoleteId, "s-old");
    setWaiting(activeId, "s-new");

    service.discard(UUID.fromString(obsoleteId), true);

    assertEquals(AgentState.REMOVED, row(obsoleteId).state);
    assertTrue(reportsWithCause(workId, AgentService.CAUSE_REMOVED).isEmpty());
    assertNotNull(row(activeId).waitingSince, "the current agent still waits");

    service.discard(UUID.fromString(activeId), true);
    List<FakeAgentWaitingReporter.Report> removed = reportsWithCause(workId, AgentService.CAUSE_REMOVED);
    assertEquals(1, removed.size(), "the last waiting agent going ends the wait");
    assertEquals("s-new", removed.get(0).sessionId());
  }

  @Test
  void aFreshDispatchTellsQitsProjectsTheItemsAgentDoesNotWaitAndARepressSaysNothing() {
    // qits-1274: a new agent inherited the wait of the item's previous one.
    String workId = uniqueWorkId("w-dispatched");
    AgentService.Dispatch first = dispatch(workId, false, null);
    assertTrue(first.fresh());

    List<FakeAgentWaitingReporter.Report> reports = reporter.reportsFor(workId);
    assertEquals(1, reports.size(), reports.toString());
    assertFalse(reports.get(0).waiting());
    assertEquals(AgentService.CAUSE_DISPATCHED, reports.get(0).cause());
    assertNull(reports.get(0).waitingOn());

    AgentService.Dispatch again = dispatch(workId, false, null);
    assertFalse(again.fresh());
    assertEquals(1, reporter.reportsFor(workId).size(), "a re-press reports nothing more");
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

    service.onActivity(row(agentId).workspaceId, agentId, Boolean.TRUE, "s-9", "Stop", System.currentTimeMillis());
    AgentWorktree waiting = row(agentId);
    assertEquals("s-9", waiting.sessionId);
    assertNotNull(waiting.waitingSince);

    service.onActivity(row(agentId).workspaceId, agentId, Boolean.FALSE, null, "UserPromptSubmit", System.currentTimeMillis());
    assertNull(row(agentId).waitingSince);
  }

  // --- target capacity (qits-1228, epic qits-1481) ------------------------------------------------
  // A new regular workspace is asked for only while no TARGET row of this service waits QUEUED for
  // a slot. The suite shares one database, so each test first stops every other test's waiting row,
  // then queues its agents ahead of any other test's leftovers.

  /**
   * No TARGET row anywhere waits: the leftovers of other tests, and this test's own waiting row,
   * are stopped, not counted. Called before the test queues its agents: any pass after it may
   * place them.
   */
  private void noLeaseWaits() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .list(
                        "status = ?1 and placement = ?2 and runtimeStatus = ?3 and leaseId is not"
                            + " null",
                        WorkspaceStatus.ACTIVE,
                        WorkspacePlacement.TARGET,
                        WorkspaceRuntimeStatus.QUEUED)
                    .forEach(w -> w.runtimeStatus = WorkspaceRuntimeStatus.STOPPED));
  }

  /** An old-model TARGET row on a branch of this test's wrapper: never a slot an agent takes. */
  private void branchRow(WorkspaceRuntimeStatus runtimeStatus) {
    QuarkusTransaction.requiringNew().run(() -> persistBranchRow(runtimeStatus));
  }

  private void persistBranchRow(WorkspaceRuntimeStatus runtimeStatus) {
    Workspace row = new Workspace();
    row.repositoryId = repoId;
    row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
    row.branch = "ticket/old-" + UUID.randomUUID().toString().substring(0, 8);
    row.status = WorkspaceStatus.ACTIVE;
    row.runtimeStatus = runtimeStatus;
    row.placement = WorkspacePlacement.TARGET;
    if (runtimeStatus == WorkspaceRuntimeStatus.QUEUED) {
      // QUEUED for a slot: its lease waits (a QUEUED row with no lease waits for a node instead).
      row.leaseId = UUID.randomUUID();
    }
    workspaces.persist(row);
  }

  /** A queued regular agent ahead of any other test's leftovers: the queue is oldest first. */
  private UUID queuedFirst() {
    UUID id =
        agentIn(
            null, false, AgentRunState.QUEUED, Instant.EPOCH.plusSeconds(createdAgents.size()));
    createdAgents.add(id);
    return id;
  }

  /** This test's slot workspaces: the rows a pass created. */
  private List<Workspace> slots() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaces.findActiveByRepositoryId(repoId).stream()
                    .filter(w -> w.branch == null)
                    .toList());
  }

  @Test
  void aStoppedTargetRowHoldsNoSlotAndAQueuedAgentGetsOneThatAsksForALease() {
    noLeaseWaits();
    branchRow(WorkspaceRuntimeStatus.STOPPED);
    UUID queued = queuedFirst();

    service.placeQueued();

    AgentWorktree placed = row(queued.toString());
    assertEquals(AgentRunState.RUNNING, placed.runState, "the stopped row waits for nothing");
    Workspace slot = workspace(placed.workspaceId);
    assertEquals(WorkspacePlacement.TARGET, slot.placement);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, slot.runtimeStatus);
    assertNotNull(slot.leaseId, "its create asked for a lease");
    assertEquals(FakeTargetPlacement.WAITING, slot.runtimeError, "and shows why it waits");
  }

  @Test
  void aWaitingLeaseKeepsAnAgentQueuedAndTheWaitIsLoggedOnce() {
    List<String> logged = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            logged.add(
                record.getParameters() == null
                    ? record.getMessage()
                    : String.format(record.getMessage(), record.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    // Attached before the agent exists: the scheduler's own pass may be the one that logs.
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(AgentService.class.getName());
    logger.addHandler(capture);
    UUID queued;
    try {
      queued = queuedFirst();

      service.placeQueued();
      service.placeQueued();
    } finally {
      logger.removeHandler(capture);
    }

    List<String> waits =
        logged.stream().filter(m -> m.startsWith("Agent " + queued + " stays queued")).toList();
    assertEquals(AgentRunState.QUEUED, row(queued.toString()).runState);
    assertTrue(slots().isEmpty(), "no slot is asked for while a lease waits");
    assertEquals(1, waits.size(), "logged once over two passes: " + waits);
    assertTrue(
        waits
            .get(0)
            .matches(
                ".* stays queued for a workspace slot: \\d+ workspace\\(s\\) of this service"
                    + " wait for one already"),
        waits.get(0));
  }

  @Test
  void aWaitingSlotIsCountedSoOnePassCreatesOneSlotForTwoAgents() {
    noLeaseWaits();
    UUID first = queuedFirst();
    UUID second = queuedFirst();

    service.placeQueued();

    List<Workspace> created = slots();
    assertEquals(1, created.size(), "the first slot is QUEUED and counted before the second agent");
    assertEquals(WorkspaceRuntimeStatus.QUEUED, created.get(0).runtimeStatus);
    assertEquals(created.get(0).id, row(first.toString()).workspaceId);
    assertEquals(AgentRunState.QUEUED, row(second.toString()).runState);
  }

  // --- the idle nudge (qits-1460) -----------------------------------------------------------------

  private static final Duration NUDGE_AFTER = Duration.ofMinutes(20);

  private static final String NUDGE_OPENING = "You ended your turn with no open wait and no block on";

  /** A dispatched admin agent, started on a live daemon with its instruction, quiet for {@code quiet}. */
  private String quietAgent(String workId, Duration quiet) {
    AgentService.Dispatch dispatched = dispatch(workId, true, "the phase");
    String agentId = dispatched.agent().agentId();
    liveness.markLive(dispatched.agent().workspaceRowId());
    service.reconcile();
    assertTrue(service.isStarted(UUID.fromString(agentId)));
    assertNull(row(agentId).pendingText, "the instruction was handed over");
    // dispatch() says REFINED, which is between phases; a quiet agent mid-phase is the subject.
    service.markEntity(workId, new EntityFacts(null, "IMPLEMENTING", false), null, null);
    endedItsTurn(agentId, quiet);
    return agentId;
  }

  /** The agent's turn ended {@code ago}, and no frame came since. */
  private void endedItsTurn(String agentId, Duration ago) {
    Instant at = Instant.now().minus(ago);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(agentId));
              row.waitingSince = at;
              row.lastActivityAt = at;
            });
  }

  private long nudgeTurns(String agentId) {
    return daemon.callsFor(agentId).stream()
        .filter(c -> c.startsWith("turn ") && c.contains(NUDGE_OPENING))
        .count();
  }

  private List<AgentNudged> nudgesOf(String agentId) {
    return announced.of(AgentNudged.class, e -> e.agentId().equals(agentId));
  }

  private void assertNotNudged(String agentId) {
    service.nudgeIdle(NUDGE_AFTER, 1);
    assertEquals(0, row(agentId).nudgeCount);
    assertNull(row(agentId).nudgedAt);
    assertEquals(List.of(), nudgesOf(agentId));
  }

  @Test
  void aQuietDispatchedAgentIsNudgedExactlyOnce() {
    String agentId = quietAgent("w-nudge-once", NUDGE_AFTER.plusMinutes(1));
    Long slot = row(agentId).workspaceId;
    try {
      service.nudgeIdle(NUDGE_AFTER, 1);

      AgentWorktree nudged = row(agentId);
      assertEquals(AgentService.nudgeText("w-nudge-once"), nudged.pendingText);
      assertTrue(nudged.pendingText.contains("w-nudge-once"));
      assertEquals(1, nudged.nudgeCount);
      assertNotNull(nudged.nudgedAt);
      AgentNudged event = nudgesOf(agentId).get(0);
      assertEquals("w-nudge-once", event.workId());
      assertEquals(slot.toString(), event.workspaceId());
      assertEquals(1, event.nudgeCount());
      assertEquals(AgentNudged.IDLE, event.reason());

      service.reconcile();
      assertEquals(1, nudgeTurns(agentId), daemon.callsFor(agentId).toString());
      assertNull(row(agentId).pendingText, "said");
      assertEquals(1, row(agentId).nudgeCount, "its own delivery does not reset the count");

      // A second sweep finds nothing: the delivery restarted the quiet clock, and the limit is
      // reached. waitingSince stays until a BUSY frame (qits-1511).
      assertNotNull(row(agentId).waitingSince);
      service.nudgeIdle(NUDGE_AFTER, 1);
      // It answers the nudge with nothing: a new Stop, and another quiet period.
      service.onActivity(
          slot, agentId, Boolean.TRUE, "s-1", "Stop", System.currentTimeMillis());
      endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(5));
      service.nudgeIdle(NUDGE_AFTER, 1);
      service.reconcile();

      assertEquals(1, nudgeTurns(agentId), "no second nudge");
      assertEquals(1, nudgesOf(agentId).size());
      assertNull(row(agentId).pendingText);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void threeNudgesInARowEachWaitTwiceAsLongThenNone() {
    // qits-1511: with the defaults, 20, 40 and 80 minutes of quiet, then no fourth.
    String agentId = quietAgent("w-nudge-thrice", NUDGE_AFTER.minusMinutes(1));
    Long slot = row(agentId).workspaceId;
    try {
      int[] minutes = {20, 40, 80};
      for (int k = 1; k <= 3; k++) {
        Duration needed = Duration.ofMinutes(minutes[k - 1]);
        assertEquals(needed, AgentService.quietNeeded(NUDGE_AFTER, k - 1));
        endedItsTurn(agentId, needed.minusMinutes(1));
        service.nudgeIdle(NUDGE_AFTER, 3);
        assertEquals(k - 1, row(agentId).nudgeCount, "not yet: nudge " + k + " needs " + needed);

        endedItsTurn(agentId, needed.plusMinutes(1));
        service.nudgeIdle(NUDGE_AFTER, 3);
        assertEquals(k, row(agentId).nudgeCount, "nudge " + k + " after " + needed);
        assertEquals(k, nudgesOf(agentId).get(k - 1).nudgeCount());
        service.reconcile();
        assertEquals(k, nudgeTurns(agentId), daemon.callsFor(agentId).toString());
      }

      endedItsTurn(agentId, Duration.ofDays(2));
      service.nudgeIdle(NUDGE_AFTER, 3);
      service.reconcile();
      assertEquals(3, row(agentId).nudgeCount, "the limit");
      assertEquals(3, nudgeTurns(agentId), "no fourth nudge");
      assertEquals(3, nudgesOf(agentId).size());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aDeliveryResetsTheCountSoALaterQuietPeriodIsNudgedAgain() {
    String agentId = quietAgent("w-nudge-delivery", NUDGE_AFTER.plusMinutes(1));
    Long slot = row(agentId).workspaceId;
    try {
      service.nudgeIdle(NUDGE_AFTER, 1);
      service.reconcile();
      assertEquals(1, nudgeTurns(agentId));

      service.deliver("w-nudge-delivery", "next phase", false);
      assertEquals(0, row(agentId).nudgeCount, "a delivery is a turn that is not a nudge");
      service.reconcile();
      endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));

      service.nudgeIdle(NUDGE_AFTER, 1);
      service.reconcile();
      assertEquals(2, nudgeTurns(agentId), daemon.callsFor(agentId).toString());
      assertEquals(1, row(agentId).nudgeCount);
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aWaitsWakeResetsTheCountSoALaterQuietPeriodIsNudgedAgain() {
    String agentId = quietAgent("w-nudge-wake", NUDGE_AFTER.plusMinutes(1));
    UUID id = UUID.fromString(agentId);
    Long slot = row(agentId).workspaceId;
    try {
      service.nudgeIdle(NUDGE_AFTER, 1);
      service.reconcile();
      assertEquals(1, nudgeTurns(agentId));

      waits.create(id, "ci run 7", EventSelection.of("BuildStatusChanged"), null);
      assertEquals(0, row(agentId).nudgeCount, "declaring a wait starts the count again");
      // Isolate the wake: as if the nudges ran out after the wait was declared.
      QuarkusTransaction.requiringNew().run(() -> agents.findById(id).nudgeCount = 1);
      endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));
      assertNotNudgedWithAnOpenWait(agentId);

      assertEquals(1, waits.onEvent("ev-nudge", "BuildStatusChanged", Instant.now(), "{}"));
      assertEquals(0, row(agentId).nudgeCount, "a wake is a turn that is not a nudge");
      service.reconcile();
      endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));

      service.nudgeIdle(NUDGE_AFTER, 1);
      service.reconcile();
      assertEquals(2, nudgeTurns(agentId), daemon.callsFor(agentId).toString());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aTurnTheHarnessSwallowedLeavesTheAgentNudgeableOnceQuietAgain() {
    // qits-1511: the turn was typed into a permission dialog; no UserPromptSubmit, no Stop.
    String agentId = quietAgent("w-nudge-swallowed", NUDGE_AFTER.plusMinutes(1));
    Long slot = row(agentId).workspaceId;
    try {
      service.deliver("w-nudge-swallowed", "next step", false);
      service.reconcile();
      assertTrue(daemon.callsFor(agentId).contains("turn " + slot + " " + agentId + " next step"));
      assertNotNull(row(agentId).waitingSince, "still waiting: no BUSY frame came");

      service.nudgeIdle(NUDGE_AFTER, 1);
      assertEquals(0, row(agentId).nudgeCount, "the typing restarted the quiet clock");

      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  agents.findById(UUID.fromString(agentId)).lastActivityAt =
                      Instant.now().minus(NUDGE_AFTER.plusMinutes(1)));
      service.nudgeIdle(NUDGE_AFTER, 1);
      assertEquals(1, row(agentId).nudgeCount);
      assertEquals(AgentService.nudgeText("w-nudge-swallowed"), row(agentId).pendingText);
    } finally {
      liveness.markDead(slot);
    }
  }

  private void assertNotNudgedWithAnOpenWait(String agentId) {
    int before = nudgesOf(agentId).size();
    service.nudgeIdle(NUDGE_AFTER, 5);
    assertEquals(before, nudgesOf(agentId).size(), "an open wait is never nudged");
  }

  @Test
  void anAgentWithAnOpenWaitIsNotNudged() {
    String agentId = quietAgent("w-nudge-wait", Duration.ZERO);
    liveness.markDead(row(agentId).workspaceId);
    waits.create(UUID.fromString(agentId), "ci run 8", EventSelection.of("BuildStatusChanged"), null);
    endedItsTurn(agentId, NUDGE_AFTER.plusHours(1));
    assertNotNudged(agentId);
  }

  @Test
  void anExplicitBlockExemptsTheAgentAndTheDerivedWaitingBlockDoesNot() {
    String explicit = quietAgent("w-nudge-explicit", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(explicit).workspaceId);
    service.markBlocked("w-nudge-explicit", true, "EXPLICIT", null);
    endedItsTurn(explicit, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(explicit);

    service.markBlocked("w-nudge-explicit", true, "BOTH", null);
    assertNotNudged(explicit);

    String derived = quietAgent("w-nudge-derived", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(derived).workspaceId);
    service.markBlocked("w-nudge-derived", true, "AGENT_WAITING", null);
    endedItsTurn(derived, NUDGE_AFTER.plusMinutes(1));
    service.nudgeIdle(NUDGE_AFTER, 1);
    assertEquals(1, row(derived).nudgeCount, "the stalled agent's own derived block is no reason");
  }

  /** The agent's own token subject, given one if the fixture minted none. */
  private String subjectOf(String agentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(agentId));
              if (row.commissionedTokenSubject == null) {
                row.commissionedTokenSubject = "tok-workspace-agent-" + agentId;
              }
              return row.commissionedTokenSubject;
            });
  }

  @Test
  void anAgentThatBlockedItsOwnItemWithNoWaitIsNudgedToDeclareOne() {
    // qits-1511: it called work_block on a release request it could have waited on, and sat idle.
    String agentId = quietAgent("w-nudge-self", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(agentId).workspaceId);
    String self = subjectOf(agentId);
    service.markBlocked("w-nudge-self", true, "EXPLICIT", self);
    assertEquals(self, row(agentId).blockAuthor);
    endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));

    service.nudgeIdle(NUDGE_AFTER, 3);

    AgentWorktree nudged = row(agentId);
    assertEquals(1, nudged.nudgeCount);
    assertEquals(AgentService.selfBlockedNudgeText("w-nudge-self"), nudged.pendingText);
    assertTrue(nudged.pendingText.startsWith("You blocked w-nudge-self yourself"), nudged.pendingText);
    assertEquals(AgentNudged.SELF_BLOCKED, nudgesOf(agentId).get(0).reason());

    // Same spacing as any nudge: the second needs twice the quiet.
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(agentId)).pendingText = null);
    endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));
    service.nudgeIdle(NUDGE_AFTER, 3);
    assertEquals(1, row(agentId).nudgeCount);
    endedItsTurn(agentId, NUDGE_AFTER.multipliedBy(2).plusMinutes(1));
    service.nudgeIdle(NUDGE_AFTER, 3);
    assertEquals(2, row(agentId).nudgeCount);
  }

  @Test
  void aBlockSetByAnybodyElseOrWithAnOpenWaitStillExemptsTheAgent() {
    String other = quietAgent("w-nudge-other", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(other).workspaceId);
    subjectOf(other);
    service.markBlocked("w-nudge-other", true, "EXPLICIT", "a-person");
    endedItsTurn(other, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(other);

    // BOTH is the agent's own derived-plus-explicit state, but the author names somebody else.
    String bothOther = quietAgent("w-nudge-both-other", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(bothOther).workspaceId);
    subjectOf(bothOther);
    service.markEntity(
        "w-nudge-both-other",
        new EntityFacts(null, "IMPLEMENTING", true),
        "BOTH",
        "a-person");
    endedItsTurn(bothOther, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(bothOther);

    String waiting = quietAgent("w-nudge-self-wait", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(waiting).workspaceId);
    service.markBlocked("w-nudge-self-wait", true, "EXPLICIT", subjectOf(waiting));
    waits.create(
        UUID.fromString(waiting), "release-request 9", EventSelection.of("ReleaseRequestStateChanged"), null);
    endedItsTurn(waiting, NUDGE_AFTER.plusHours(1));
    assertNotNudged(waiting);

    // A dispatch's facts name no author: its block reads as somebody else's.
    String unnamed = quietAgent("w-nudge-unnamed", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(unnamed).workspaceId);
    subjectOf(unnamed);
    service.markBlocked("w-nudge-unnamed", true, "EXPLICIT", null);
    endedItsTurn(unnamed, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(unnamed);
  }

  @Test
  void aSelfBlockedAgentIdlePastTheDebounceReadsBothAndIsStillNudged() {
    // qits-1511: qits-projects' AgentWaiting sweep re-announces the item once the derived
    // AGENT_WAITING block joins the agent's own explicit one, so a self-blocked idle agent's row
    // reads BOTH, not EXPLICIT, by the time nudgeIdle asks. BOTH must not read as somebody else's
    // block when the explicit block's own author is this agent.
    String agentId = quietAgent("w-nudge-self-both", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(agentId).workspaceId);
    String self = subjectOf(agentId);
    service.markBlocked("w-nudge-self-both", true, "BOTH", self);
    assertEquals(self, row(agentId).blockAuthor);
    endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));

    service.nudgeIdle(NUDGE_AFTER, 3);

    AgentWorktree nudged = row(agentId);
    assertEquals(1, nudged.nudgeCount);
    assertEquals(AgentService.selfBlockedNudgeText("w-nudge-self-both"), nudged.pendingText);
    assertEquals(AgentNudged.SELF_BLOCKED, nudgesOf(agentId).get(0).reason());
  }

  @Test
  void theMarksStoreTheBlockAuthorAndClearItWhenNoneIsNamed() {
    String agentId = quietAgent("w-block-author", NUDGE_AFTER.plusHours(1));
    liveness.markDead(row(agentId).workspaceId);

    service.markEntity(
        "w-block-author", new EntityFacts(null, "IMPLEMENTING", true), "EXPLICIT", " sub-1 ");
    assertEquals("sub-1", row(agentId).blockAuthor);
    assertEquals("EXPLICIT", row(agentId).blockSource);

    service.markBlocked("w-block-author", true, "BOTH", "sub-2");
    assertEquals("sub-2", row(agentId).blockAuthor);
    assertEquals("BOTH", row(agentId).blockSource);

    service.markBlocked("w-block-author", false, null, null);
    assertNull(row(agentId).blockAuthor);
    service.markEntity("w-block-author", new EntityFacts(null, "IMPLEMENTING", true), "EXPLICIT", "sub-3");
    service.markEntity("w-block-author", new EntityFacts(null, "IMPLEMENTING", false), null, null);
    assertNull(row(agentId).blockAuthor);
  }

  @Test
  void pendingTextIsNotNudged() {
    String agentId = quietAgent("w-nudge-pending", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(agentId).workspaceId);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(agentId)).pendingText = "about to be said");
    assertNotNudged(agentId);
    assertEquals("about to be said", row(agentId).pendingText);
  }

  @Test
  void anAgentWhoseTurnHasNotEndedOrEndedLatelyIsNotNudged() {
    String working = quietAgent("w-nudge-working", NUDGE_AFTER.plusHours(1));
    liveness.markDead(row(working).workspaceId);
    QuarkusTransaction.requiringNew()
        .run(() -> agents.findById(UUID.fromString(working)).waitingSince = null);
    assertNotNudged(working);

    String lately = quietAgent("w-nudge-lately", NUDGE_AFTER.minusMinutes(5));
    liveness.markDead(row(lately).workspaceId);
    assertNotNudged(lately);

    // A frame since the turn ended restarts the quiet clock.
    String framed = quietAgent("w-nudge-framed", NUDGE_AFTER.plusHours(1));
    liveness.markDead(row(framed).workspaceId);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agents.findById(UUID.fromString(framed)).lastActivityAt =
                    Instant.now().minus(Duration.ofMinutes(1)));
    assertNotNudged(framed);
  }

  @Test
  void aClosedItemsAgentIsNotNudged() {
    String agentId = quietAgent("w-nudge-closed", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(agentId).workspaceId);
    service.markEntity("w-nudge-closed", new EntityFacts(null, "VERIFIED", false), null, null);
    endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(agentId);
  }

  @Test
  void anAgentBetweenPhasesIsNotNudgedAndOneMidPhaseIs() {
    String between = quietAgent("w-nudge-refined", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(between).workspaceId);
    service.markEntity("w-nudge-refined", new EntityFacts(null, "refined", false), null, null);
    endedItsTurn(between, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(between);

    String working = quietAgent("w-nudge-implementing", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(working).workspaceId);
    assertEquals("IMPLEMENTING", row(working).entityStatus);
    service.nudgeIdle(NUDGE_AFTER, 1);
    assertEquals(1, row(working).nudgeCount, "an agent mid-phase is nudged");
  }

  @Test
  void anObsoleteAgentIsNotNudged() {
    String agentId = quietAgent("w-nudge-obsolete", NUDGE_AFTER.plusMinutes(1));
    liveness.markDead(row(agentId).workspaceId);
    releaseWrapperOf(agentId);
    endedItsTurn(agentId, NUDGE_AFTER.plusMinutes(1));
    assertNotNudged(agentId);
  }

  @Test
  void aQueuedAgentIsNotNudged() {
    AgentService.Dispatch queued = dispatch("w-nudge-queued", false, null);
    String agentId = queued.agent().agentId();
    assertEquals(AgentRunState.QUEUED, row(agentId).runState);
    endedItsTurn(agentId, NUDGE_AFTER.plusHours(1));
    assertNotNudged(agentId);
  }

  @Test
  void aYieldedAgentIsNudgedAndResumedToHearIt() {
    String agentId = quietAgent("w-nudge-yielded", NUDGE_AFTER.plusMinutes(1));
    Long slot = row(agentId).workspaceId;
    try {
      setRunState(agentId, AgentRunState.YIELDED);

      service.nudgeIdle(NUDGE_AFTER, 1);
      assertEquals(AgentService.nudgeText("w-nudge-yielded"), row(agentId).pendingText);
      service.reconcile();

      assertEquals(AgentRunState.RUNNING, row(agentId).runState, "resumed for the nudge");
      assertEquals(1, nudgeTurns(agentId), daemon.callsFor(agentId).toString());
    } finally {
      liveness.markDead(slot);
    }
  }

  @Test
  void aZeroQuietTimeSwitchesNudgingOff() {
    String agentId = quietAgent("w-nudge-off", NUDGE_AFTER.plusHours(1));
    liveness.markDead(row(agentId).workspaceId);
    service.nudgeIdle(Duration.ZERO, 1);
    service.nudgeIdle(Duration.ofMinutes(-1), 1);
    service.nudgeIdle(NUDGE_AFTER, 0);
    assertEquals(0, row(agentId).nudgeCount);
    assertNull(row(agentId).pendingText);
  }

  // --- the stale-idle repair (qits-1511) ---------------------------------------------------------

  private static final Duration STALE_AFTER = Duration.ofMinutes(65);

  /**
   * A quiet agent whose turn-ended report never came: {@code waitingSince} null, its last frame
   * {@code quiet} ago, its daemon reading it {@code state} (null: no reading, daemon not connected).
   * Its slot's liveness is dropped so a background pass does not drive it.
   */
  private String staleAgent(String workId, Duration quiet, AgentActivityState state) {
    String agentId = quietAgent(workId, quiet);
    liveness.markDead(row(agentId).workspaceId);
    Instant at = Instant.now().minus(quiet);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(agentId));
              row.waitingSince = null;
              row.lastActivityAt = at;
            });
    if (state != null) {
      activityView.report(agentId, state);
    }
    return agentId;
  }

  private List<FakeAgentWaitingReporter.Report> staleIdleRelays(String workId) {
    return reporter.reportsFor(workId).stream()
        .filter(r -> AgentService.CAUSE_STALE_IDLE.equals(r.cause()))
        .toList();
  }

  private void assertNotRepaired(String agentId, Duration after) {
    service.repairStaleIdle(after);
    AgentWorktree row = row(agentId);
    assertNull(row.waitingSince, "not treated as waiting");
    assertEquals(List.of(), staleIdleRelays(row.workId));
  }

  @Test
  void anIdleAgentWithNoTurnEndedReportIsTreatedAsWaitingAndThenNudged() {
    String agentId = staleAgent("w-stale-idle", Duration.ofMinutes(70), AgentActivityState.IDLE);
    try {
      Instant last = row(agentId).lastActivityAt;
      service.nudgeIdle(NUDGE_AFTER, 1);
      assertEquals(0, row(agentId).nudgeCount, "no waitingSince: the nudge cannot see it");

      service.repairStaleIdle(STALE_AFTER);

      AgentWorktree repaired = row(agentId);
      assertEquals(last, repaired.waitingSince, "waiting since its last frame");
      List<FakeAgentWaitingReporter.Report> relays = staleIdleRelays("w-stale-idle");
      assertEquals(1, relays.size(), reporter.reportsFor("w-stale-idle").toString());
      assertTrue(relays.get(0).waiting());
      assertEquals(last.toEpochMilli(), relays.get(0).at());

      service.repairStaleIdle(STALE_AFTER);
      assertEquals(1, staleIdleRelays("w-stale-idle").size(), "a second pass repairs nothing");

      service.nudgeIdle(NUDGE_AFTER, 1);
      assertEquals(1, row(agentId).nudgeCount, "nudgeable at once: 70 min of quiet");
      assertEquals(AgentService.nudgeText("w-stale-idle"), row(agentId).pendingText);
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void aWaitingAgentWithNoTurnEndedReportIsRepaired() {
    String agentId = staleAgent("w-stale-waiting", Duration.ofMinutes(70), AgentActivityState.WAITING);
    try {
      service.repairStaleIdle(STALE_AFTER);
      assertEquals(row(agentId).lastActivityAt, row(agentId).waitingSince);
      assertEquals(1, staleIdleRelays("w-stale-waiting").size());
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void aBusyAgentIsNotRepairedHoweverLongItIsQuiet() {
    String agentId = staleAgent("w-stale-busy", Duration.ofHours(3), AgentActivityState.BUSY);
    try {
      assertNotRepaired(agentId, STALE_AFTER);
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void anAgentWithNoReadingIsNotRepaired() {
    // No activity known: its daemon is not connected, so nothing says it is idle.
    String agentId = staleAgent("w-stale-unknown", Duration.ofHours(3), null);
    assertNotRepaired(agentId, STALE_AFTER);

    activityView.report(agentId, AgentActivityState.ENDED);
    try {
      assertNotRepaired(agentId, STALE_AFTER);
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void anAgentQuietForLessThanTheGraceIsNotRepaired() {
    String agentId = staleAgent("w-stale-recent", Duration.ofMinutes(30), AgentActivityState.IDLE);
    try {
      assertNotRepaired(agentId, STALE_AFTER);
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void anAgentWithSomethingPendingIsNotRepaired() {
    String agentId = staleAgent("w-stale-pending", Duration.ofMinutes(70), AgentActivityState.IDLE);
    try {
      QuarkusTransaction.requiringNew()
          .run(() -> agents.findById(UUID.fromString(agentId)).pendingText = "a turn to say");
      assertNotRepaired(agentId, STALE_AFTER);
    } finally {
      activityView.forget(agentId);
    }
  }

  @Test
  void aQueuedOrObsoleteAgentIsNotRepaired() {
    AgentService.Dispatch queued = dispatch("w-stale-queued", false, null);
    String queuedId = queued.agent().agentId();
    assertEquals(AgentRunState.QUEUED, row(queuedId).runState);
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agents.findById(UUID.fromString(queuedId)).lastActivityAt =
                    Instant.now().minus(Duration.ofHours(2)));
    activityView.report(queuedId, AgentActivityState.IDLE);

    String obsoleteId =
        staleAgent("w-stale-obsolete", Duration.ofMinutes(70), AgentActivityState.IDLE);
    releaseWrapperOf(obsoleteId);
    assertEquals(AgentState.OBSOLETE, row(obsoleteId).state);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(UUID.fromString(obsoleteId));
              row.waitingSince = null;
              row.pendingText = null;
              row.lastActivityAt = Instant.now().minus(Duration.ofMinutes(70));
            });
    try {
      assertNotRepaired(queuedId, STALE_AFTER);
      assertNotRepaired(obsoleteId, STALE_AFTER);
    } finally {
      activityView.forget(queuedId);
      activityView.forget(obsoleteId);
    }
  }

  @Test
  void aZeroStaleTimeSwitchesTheRepairOff() {
    String agentId = staleAgent("w-stale-off", Duration.ofHours(3), AgentActivityState.IDLE);
    try {
      assertNotRepaired(agentId, Duration.ZERO);
      assertNotRepaired(agentId, Duration.ofMinutes(-1));
    } finally {
      activityView.forget(agentId);
    }
  }
}
