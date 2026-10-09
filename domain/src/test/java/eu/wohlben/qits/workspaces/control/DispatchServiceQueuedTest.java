package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import jakarta.enterprise.inject.Vetoed;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DispatchService}'s parking of a launch for a workspace QUEUED for a runner (qits-626), with
 * no Quarkus and no database: the row's placement is scripted through {@link
 * DispatchService#runnerSide}, the executor is a list the test runs by hand, and the daemon is a
 * scripted {@link WorkspaceAgentLauncher}.
 *
 * <p>What it pins: a QUEUED row parks (no thread, no window); {@link WorkspaceTaken} submits it with
 * a window that starts then; {@link WorkspaceUnqueued} drops it and releases the claim; a second
 * press while parked parks nothing more; the race between the read and the park cannot strand it;
 * UNAVAILABLE is a 409 naming the runner; and a DIRECT row is submitted at once, as before.
 *
 * <p>And the held launch's table (qits-1064), through {@link FakeHeldLaunches} shared between two
 * instances — a process and the one that replaced it: what is parked is stored and outlives the
 * process, a take or a drain in the new one delivers it, a delivered launch is deleted and an
 * interrupted one is kept, and two instances that both see it submit it once.
 */
class DispatchServiceQueuedTest {

  private static final DispatchService.RunnerSide QUEUED = runner(WorkspaceRuntimeStatus.QUEUED);
  private static final DispatchService.RunnerSide PROVISIONING =
      runner(WorkspaceRuntimeStatus.PROVISIONING);
  private static final DispatchService.RunnerSide STOPPED = runner(WorkspaceRuntimeStatus.STOPPED);

  private static final Long ROW = 42L;

  private Testable service;
  private ScriptedLauncher launcher;
  private FakeHeldLaunches store;

  @BeforeEach
  void setUp() {
    launcher = new ScriptedLauncher();
    store = new FakeHeldLaunches();
    service = process();
  }

  /** A {@link DispatchService} over the shared launcher and table: one process. */
  private Testable process() {
    Testable process = new Testable();
    process.agents = StubInstance.of(launcher);
    process.processes = StubInstance.empty();
    process.agentActivity = StubInstance.empty();
    process.heldLaunches = store;
    process.launchWindowMs = 200;
    process.pollIntervalMs = 10;
    return process;
  }

  @Test
  void aQueuedRowParksTheLaunchOnNoThread() {
    service.sides(QUEUED, QUEUED);

    service.schedule(ROW, "go");

    assertTrue(service.isParked(ROW));
    assertTrue(service.isPending(ROW), "the claim is kept while parked");
    assertEquals(0, service.submitted.size(), "no thread, so no window is running");
    assertEquals(List.of(), launcher.probes, "nothing asks the daemon while parked");
    assertTrue(store.holds(ROW), "and it is stored, unclaimed");
    assertNull(store.get(ROW).claimedBy());
  }

  /**
   * The window starts at the take, not at the press: parked for longer than the whole window, the
   * launch still waits its full window for the daemon once a runner took the row.
   */
  @Test
  void aTakeSubmitsTheLaunchWithAFreshWindow() throws Exception {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    Thread.sleep(service.launchWindowMs * 2);

    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    assertFalse(service.isParked(ROW));
    assertEquals(1, service.submitted.size());
    launcher.answers(
        WorkspaceAgentLauncher.AgentState.UNREACHABLE,
        WorkspaceAgentLauncher.AgentState.UNREACHABLE,
        WorkspaceAgentLauncher.AgentState.IDLE);
    service.submitted.get(0).run();

    assertEquals(List.of(ROW + ":go"), launcher.launches);
    assertFalse(service.isPending(ROW), "the claim is released when the wait ends");
    assertFalse(store.holds(ROW), "a delivered launch is deleted");
  }

  /** From the take on it is the ordinary window: a daemon that never answers is given up on. */
  @Test
  void afterTheTakeTheWindowStillCloses() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    long started = System.currentTimeMillis();
    service.submitted.get(0).run();

    assertTrue(System.currentTimeMillis() - started >= service.launchWindowMs);
    assertEquals(List.of(), launcher.launches);
    assertFalse(service.isPending(ROW));
    assertFalse(store.holds(ROW), "a launch given up on is deleted too, or every drain retries it");
  }

  @Test
  void aDeliveryParksAndATakeDeliversIt() {
    service.sides(QUEUED, QUEUED);

    assertTrue(service.scheduleDelivery(ROW, "next phase", false));
    assertTrue(service.isParked(ROW));
    assertEquals(0, service.submitted.size());

    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    service.submitted.get(0).run();

    assertEquals(
        List.of(ROW + ":next phase"),
        launcher.launches,
        "no agent was running, so the text seeds a launch — the delivery's own fallback arm");
  }

  @Test
  void leavingTheQueueDropsTheParkedLaunchAndReleasesTheClaim() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    service.onUnqueued(new WorkspaceUnqueued(ROW, "Stopped before a runner took it."));

    assertFalse(service.isParked(ROW));
    assertFalse(service.isPending(ROW));
    assertFalse(store.holds(ROW), "and it is deleted from the table");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(0, service.submitted.size(), "a dropped launch is not released by a later take");
    assertFalse(service.isPending(ROW));
  }

  @Test
  void anUnqueueForARowWithNothingParkedChangesNothing() {
    service.sides(PROVISIONING);
    service.schedule(ROW, "go");

    service.onUnqueued(new WorkspaceUnqueued(ROW, "Stopped."));

    assertTrue(service.isPending(ROW), "a running wait keeps its claim");
    assertEquals(1, service.submitted.size());
  }

  @Test
  void aSecondPressWhileParkedParksOnce() {
    service.sides(QUEUED, QUEUED, QUEUED, QUEUED);

    service.schedule(ROW, "go");
    service.schedule(ROW, "go");
    assertFalse(service.scheduleDelivery(ROW, "hello", false), "the slot is taken");

    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(1, service.submitted.size());
  }

  /** Taken between the read that said QUEUED and the park: the second read submits it. */
  @Test
  void aTakeBetweenTheReadAndTheParkCannotStrandTheLaunch() {
    service.sides(QUEUED, PROVISIONING);

    service.schedule(ROW, "go");

    assertFalse(service.isParked(ROW));
    assertEquals(1, service.submitted.size());
  }

  /** The observer and the second read race for the entry: exactly one of them submits it. */
  @Test
  void anObserverRacingTheSecondReadSubmitsOnce() {
    service.script(
        () -> QUEUED,
        () -> {
          service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
          return PROVISIONING;
        });

    service.schedule(ROW, "go");

    assertFalse(service.isParked(ROW));
    assertEquals(1, service.submitted.size());
  }

  /** Stopped between the read and the park: dropped, and the claim released. */
  @Test
  void aStopBetweenTheReadAndTheParkDropsIt() {
    service.sides(QUEUED, STOPPED);

    service.schedule(ROW, "go");

    assertFalse(service.isParked(ROW));
    assertFalse(service.isPending(ROW));
    assertEquals(0, service.submitted.size());
    assertFalse(store.holds(ROW));
  }

  @Test
  void anUnavailableRowIs409NamingTheRunner() {
    DispatchService.RunnerSide offline =
        new DispatchService.RunnerSide(
            WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.UNAVAILABLE, "r-night-shift");

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> DispatchService.refuseUnavailable(ROW, offline, "take an agent"));

    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertTrue(refused.getMessage().contains("r-night-shift"), refused.getMessage());
    DispatchService.refuseUnavailable(ROW, QUEUED, "take an agent");
    DispatchService.refuseUnavailable(ROW, DispatchService.RunnerSide.DIRECT, "take an agent");
  }

  @Test
  void aDirectRowIsSubmittedAtOnceAsBefore() {
    service.sides(DispatchService.RunnerSide.DIRECT);

    service.schedule(ROW, "go");

    assertFalse(service.isParked(ROW));
    assertEquals(1, service.submitted.size());
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    service.submitted.get(0).run();
    assertEquals(List.of(ROW + ":go"), launcher.launches);
  }

  @Test
  void aShutdownKeepsWhatIsParked() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    service.shutdown();

    assertFalse(service.isParked(ROW));
    assertTrue(store.holds(ROW), "the next process resumes it");
  }

  // --- the held launch's table (qits-1064) ---------------------------------------------------------

  /** The launch parked before a restart is launched by the new process's take, with no re-press. */
  @Test
  void aTakeAfterARestartLaunchesWhatTheOldProcessHeld() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    Testable restarted = process();
    restarted.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    assertEquals(1, restarted.submitted.size());
    assertEquals(restarted.owner, store.get(ROW).claimedBy(), "claimed while it is delivered");
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    restarted.submitted.get(0).run();
    assertEquals(List.of(ROW + ":go"), launcher.launches);
    assertFalse(store.holds(ROW), "deleted once the daemon took it");
    assertFalse(restarted.isPending(ROW));
  }

  /** The new process's drain claims and submits a launch whose row a runner already took. */
  @Test
  void aDrainSubmitsALaunchWhoseRowWasTakenMeanwhile() {
    service.sides(QUEUED, QUEUED);
    service.scheduleDelivery(ROW, "next phase", false);

    Testable restarted = process();
    restarted.sides(PROVISIONING);
    assertEquals(1, restarted.drainHeldLaunches());

    assertEquals(1, restarted.submitted.size());
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    restarted.submitted.get(0).run();
    assertEquals(List.of(ROW + ":next phase"), launcher.launches, "a delivery stays a delivery");
    assertFalse(store.holds(ROW));
  }

  /** Still queued at the drain: parked again and kept stored, and the take then launches it. */
  @Test
  void aDrainParksALaunchWhoseRowIsStillQueued() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    Testable restarted = process();
    restarted.sides(QUEUED, QUEUED);
    restarted.drainHeldLaunches();

    assertTrue(restarted.isParked(ROW));
    assertTrue(store.holds(ROW));
    assertEquals(0, restarted.submitted.size());
    restarted.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(1, restarted.submitted.size());
  }

  /** A drain deletes a launch whose row resolved, stopped or failed — nothing will take it. */
  @Test
  void aDrainDropsALaunchWhoseRowLeftTheQueue() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    Testable restarted = process();
    restarted.sides(DispatchService.RunnerSide.DIRECT);
    assertEquals(0, restarted.drainHeldLaunches());

    assertFalse(store.holds(ROW));
    assertEquals(0, restarted.submitted.size());
    assertFalse(restarted.isPending(ROW));
  }

  /** A row whose runner is offline is left for a later pass, stored and unclaimed. */
  @Test
  void aDrainLeavesALaunchWhoseRunnerIsOffline() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    Testable restarted = process();
    restarted.sides(
        new DispatchService.RunnerSide(
            WorkspacePlacement.RUNNER, WorkspaceRuntimeStatus.UNAVAILABLE, "r-night-shift"));
    assertEquals(0, restarted.drainHeldLaunches());

    assertTrue(store.holds(ROW));
    assertNull(store.get(ROW).claimedBy());
    assertFalse(restarted.isPending(ROW));
  }

  /** Both processes park it (a deploy's overlap) and both see a take: it is submitted once. */
  @Test
  void twoProcessesThatBothSeeItSubmitItOnce() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    Testable beside = process();
    beside.sides(QUEUED, QUEUED);
    beside.drainHeldLaunches();
    assertTrue(beside.isParked(ROW));

    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    beside.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    assertEquals(1, service.submitted.size() + beside.submitted.size());
    assertFalse(beside.isPending(ROW), "the loser releases its own claim");
  }

  /**
   * A launch the old process is still delivering is not taken by the new one's drain; once the old
   * one shuts down and gives its claim back, the next drain takes it.
   */
  @Test
  void aDrainLeavesALiveClaimAndTakesItOnceItIsGivenBack() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(service.owner, store.get(ROW).claimedBy());

    Testable next = process();
    assertEquals(0, next.drainHeldLaunches(), "the old process is delivering it");
    assertEquals(0, next.submitted.size());

    service.shutdown();
    assertTrue(store.holds(ROW));
    assertNull(store.get(ROW).claimedBy(), "the shutdown gave the claim back");

    next.sides(PROVISIONING);
    assertEquals(1, next.drainHeldLaunches());
    assertEquals(1, next.submitted.size());
  }

  /** A claim older than the lease is a process that died without its shutdown: taken again. */
  @Test
  void aClaimOlderThanTheLeaseIsTakenAgain() throws Exception {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    Testable next = process();
    Thread.sleep(next.lease().toMillis() + 50);
    next.sides(PROVISIONING);
    assertEquals(1, next.drainHeldLaunches());
    assertEquals(next.owner, store.get(ROW).claimedBy());
  }

  /** A wait interrupted by a shutdown leaves its launch stored for the next process. */
  @Test
  void anInterruptedWaitKeepsTheLaunch() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    Thread.currentThread().interrupt();
    try {
      service.submitted.get(0).run();
    } finally {
      Thread.interrupted();
    }

    assertEquals(List.of(), launcher.launches);
    assertTrue(store.holds(ROW), "kept for the next process");
    assertFalse(service.isPending(ROW));
  }

  /** A press onto a workspace that already holds a launch — another process's — adds nothing. */
  @Test
  void aPressOntoAWorkspaceThatHoldsALaunchAddsNothing() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    Testable restarted = process();
    restarted.sides(QUEUED);
    restarted.schedule(ROW, "again");

    assertFalse(restarted.isParked(ROW));
    assertFalse(restarted.isPending(ROW));
    assertEquals("go", store.get(ROW).text(), "one held launch per workspace");
  }

  /** A resolution deletes the held launch, claimed or not. */
  @Test
  void aResolutionDiscardsTheLaunch() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    service.onResolved(
        new WorkspaceResolved("repo", "ws", ROW, WorkspaceStatus.ABANDONED));

    assertFalse(store.holds(ROW));
  }

  // --- the sweep reconciles what this process parked (qits-1064, the deploy overlap) ---------------

  /**
   * The new process (B) parks at its boot drain, the runner takes the row on the old one (A), A
   * claims and then shuts down mid-wait. B heard no take — the event is A's — so its sweep has to
   * claim and submit the launch, once.
   */
  @Test
  void aTakeOnTheOldProcessThatShutDownIsDeliveredByTheSweep() {
    Testable a = service;
    a.sides(QUEUED, QUEUED);
    a.schedule(ROW, "go");
    Testable b = process();
    b.sides(QUEUED, QUEUED);
    b.drainHeldLaunches();
    assertTrue(b.isParked(ROW));

    a.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    a.shutdown();
    assertNull(store.get(ROW).claimedBy(), "the old process gave its claim back");

    b.sides(PROVISIONING);
    assertEquals(1, b.drainHeldLaunches());

    assertFalse(b.isParked(ROW));
    assertEquals(1, b.submitted.size());
    assertEquals(b.owner, store.get(ROW).claimedBy());
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    b.submitted.get(0).run();
    assertEquals(List.of(ROW + ":go"), launcher.launches, "launched exactly once");
    assertFalse(store.holds(ROW));
    assertFalse(b.isPending(ROW));

    assertEquals(0, b.drainHeldLaunches(), "and the next pass finds nothing to do");
    assertEquals(1, b.submitted.size());
  }

  /**
   * The old process took and delivered it, deleting the stored launch. B's parked entry is stale;
   * the sweep forgets it, so a later press for the workspace on B is accepted rather than refused.
   */
  @Test
  void aLaunchDeliveredByTheOldProcessIsForgottenByTheSweep() {
    Testable a = service;
    a.sides(QUEUED, QUEUED);
    a.schedule(ROW, "go");
    Testable b = process();
    b.sides(QUEUED, QUEUED);
    b.drainHeldLaunches();

    a.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    launcher.answers(WorkspaceAgentLauncher.AgentState.IDLE);
    a.submitted.get(0).run();
    assertFalse(store.holds(ROW), "delivered and deleted by the old process");

    assertEquals(0, b.drainHeldLaunches());

    assertFalse(b.isParked(ROW));
    assertFalse(b.isPending(ROW), "the claim is released");
    assertEquals(0, b.submitted.size());
    b.sides(runner(WorkspaceRuntimeStatus.RUNNING));
    assertTrue(b.scheduleDelivery(ROW, "next phase", false), "a later press is accepted");
    assertEquals(1, b.submitted.size());
  }

  /** The old process handled the unqueue and dropped the stored launch: B forgets its entry. */
  @Test
  void aLaunchUnqueuedOnTheOldProcessIsForgottenByTheSweep() {
    Testable a = service;
    a.sides(QUEUED, QUEUED);
    a.schedule(ROW, "go");
    Testable b = process();
    b.sides(QUEUED, QUEUED);
    b.drainHeldLaunches();

    a.onUnqueued(new WorkspaceUnqueued(ROW, "Stopped before a runner took it."));
    assertFalse(store.holds(ROW));

    assertEquals(0, b.drainHeldLaunches());

    assertFalse(b.isParked(ROW));
    assertFalse(b.isPending(ROW));
    assertEquals(0, b.submitted.size());
  }

  /** The row left the queue and the stored launch is still there: B drops both, and releases. */
  @Test
  void aParkedLaunchWhoseRowLeftTheQueueIsDroppedByTheSweep() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    service.sides(STOPPED);
    assertEquals(0, service.drainHeldLaunches());

    assertFalse(service.isParked(ROW));
    assertFalse(service.isPending(ROW));
    assertFalse(store.holds(ROW), "nothing will take it, so it is deleted");
    assertEquals(0, service.submitted.size());
  }

  /**
   * The old process holds a live claim (within the lease) and the row reads taken: B submits
   * nothing, leaves the launch to the claim's holder, and releases its own {@code pending}.
   */
  @Test
  void aLiveClaimElsewhereIsLeftAloneAndReleasesPending() {
    Testable a = service;
    a.sides(QUEUED, QUEUED);
    a.schedule(ROW, "go");
    Testable b = process();
    b.sides(QUEUED, QUEUED);
    b.drainHeldLaunches();
    a.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    b.sides(PROVISIONING);
    assertEquals(0, b.drainHeldLaunches());

    assertEquals(0, b.submitted.size(), "the old process is delivering it");
    assertFalse(b.isParked(ROW));
    assertFalse(b.isPending(ROW), "and B no longer refuses presses for it");
    assertEquals(a.owner, store.get(ROW).claimedBy(), "the claim is untouched");
    assertEquals(1, a.submitted.size());
  }

  /** A parked launch whose row is still QUEUED is untouched by the sweep. */
  @Test
  void aStillQueuedParkedLaunchIsUntouchedByTheSweep() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    service.sides(QUEUED);
    assertEquals(0, service.drainHeldLaunches());

    assertTrue(service.isParked(ROW));
    assertTrue(service.isPending(ROW));
    assertTrue(store.holds(ROW));
    assertNull(store.get(ROW).claimedBy());
    assertEquals(0, service.submitted.size());
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(1, service.submitted.size(), "the take still releases it");
  }

  /** A wait this process claimed and is delivering is not read, claimed or released by a sweep. */
  @Test
  void anInFlightWaitOfThisProcessIsUntouchedByTheSweep() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));

    assertEquals(0, service.drainHeldLaunches(), "no read is scripted, so none may happen");

    assertTrue(service.isPending(ROW));
    assertEquals(service.owner, store.get(ROW).claimedBy());
    assertEquals(1, service.submitted.size());
  }

  // --- fakes --------------------------------------------------------------------------------------

  private static DispatchService.RunnerSide runner(WorkspaceRuntimeStatus status) {
    return new DispatchService.RunnerSide(WorkspacePlacement.RUNNER, status, null);
  }

  /**
   * The service with its row read scripted and its executor replaced by a list. {@code @Vetoed}:
   * it inherits {@code @ApplicationScoped}, and would otherwise be a second {@code DispatchService}
   * bean in every {@code @QuarkusTest} of the module.
   */
  @Vetoed
  private static final class Testable extends DispatchService {

    final List<Runnable> submitted = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<RunnerSide>> reads = new ArrayDeque<>();

    void sides(RunnerSide... sides) {
      for (RunnerSide side : sides) {
        reads.add(() -> side);
      }
    }

    @SafeVarargs
    final void script(Supplier<RunnerSide>... next) {
      reads.addAll(List.of(next));
    }

    @Override
    RunnerSide runnerSide(Long rowId) {
      Supplier<RunnerSide> next = reads.poll();
      if (next == null) {
        throw new AssertionError("an unscripted read of workspace " + rowId);
      }
      return next.get();
    }

    @Override
    void execute(Runnable wait) {
      submitted.add(wait);
    }
  }

  /** {@code pending_agent_launch}, in memory and shared between the instances of one test. */
  private static final class FakeHeldLaunches implements HeldAgentLaunches {

    private final Map<Long, Held> rows = new HashMap<>();

    synchronized boolean holds(Long workspaceId) {
      return rows.containsKey(workspaceId);
    }

    synchronized Held get(Long workspaceId) {
      return rows.get(workspaceId);
    }

    @Override
    public synchronized boolean hold(
        Long workspaceId, String text, boolean delivery, boolean compactFirst, Instant parkedAt) {
      return rows.putIfAbsent(
              workspaceId,
              new Held(workspaceId, text, delivery, compactFirst, parkedAt, null, null))
          == null;
    }

    @Override
    public synchronized List<Held> all() {
      return List.copyOf(rows.values());
    }

    @Override
    public synchronized Optional<Held> claim(
        Long workspaceId, String owner, Instant now, Instant staleBefore) {
      Held held = rows.get(workspaceId);
      if (held == null || !held.claimable(staleBefore)) {
        return Optional.empty();
      }
      Held claimed = withClaim(held, owner, now);
      rows.put(workspaceId, claimed);
      return Optional.of(claimed);
    }

    @Override
    public synchronized void release(Long workspaceId, String owner) {
      Held held = rows.get(workspaceId);
      if (held != null && owner.equals(held.claimedBy())) {
        rows.put(workspaceId, withClaim(held, null, null));
      }
    }

    @Override
    public synchronized int releaseAll(String owner) {
      List<Long> mine =
          rows.values().stream()
              .filter(held -> owner.equals(held.claimedBy()))
              .map(Held::workspaceId)
              .toList();
      mine.forEach(id -> rows.put(id, withClaim(rows.get(id), null, null)));
      return mine.size();
    }

    @Override
    public synchronized void finish(Long workspaceId, String owner) {
      Held held = rows.get(workspaceId);
      if (held != null && owner.equals(held.claimedBy())) {
        rows.remove(workspaceId);
      }
    }

    @Override
    public synchronized boolean dropUnclaimed(Long workspaceId, Instant staleBefore) {
      Held held = rows.get(workspaceId);
      if (held == null || !held.claimable(staleBefore)) {
        return false;
      }
      rows.remove(workspaceId);
      return true;
    }

    @Override
    public synchronized void discard(Long workspaceId) {
      rows.remove(workspaceId);
    }

    private static Held withClaim(Held held, String owner, Instant at) {
      return new Held(
          held.workspaceId(),
          held.text(),
          held.delivery(),
          held.compactFirst(),
          held.parkedAt(),
          owner,
          at);
    }
  }

  /** A daemon that answers the probes it was scripted with, then the last one forever. */
  private static final class ScriptedLauncher implements WorkspaceAgentLauncher {

    final List<Long> probes = new CopyOnWriteArrayList<>();
    final List<String> launches = new CopyOnWriteArrayList<>();
    private final List<AgentState> script = new ArrayList<>(List.of(AgentState.UNREACHABLE));

    void answers(AgentState... states) {
      script.clear();
      script.addAll(List.of(states));
    }

    @Override
    public AgentState agentState(Long workspaceRowId) {
      probes.add(workspaceRowId);
      return script.size() > 1 ? script.remove(0) : script.get(0);
    }

    @Override
    public Launch launch(Long workspaceRowId, String instruction) {
      launches.add(workspaceRowId + ":" + instruction);
      // No command id: this test has no database for DispatchService to keep one in.
      return Launch.accepted(null);
    }

    @Override
    public DeliveryOutcome deliver(Long workspaceRowId, String text) {
      return DeliveryOutcome.UNREACHABLE;
    }

    @Override
    public boolean setBlocked(Long workspaceRowId, boolean blocked) {
      return false;
    }
  }
}
