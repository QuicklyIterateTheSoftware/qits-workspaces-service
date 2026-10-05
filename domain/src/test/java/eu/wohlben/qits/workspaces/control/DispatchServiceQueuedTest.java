package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import jakarta.enterprise.inject.Vetoed;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 */
class DispatchServiceQueuedTest {

  private static final DispatchService.RunnerSide QUEUED = runner(WorkspaceRuntimeStatus.QUEUED);
  private static final DispatchService.RunnerSide PROVISIONING =
      runner(WorkspaceRuntimeStatus.PROVISIONING);
  private static final DispatchService.RunnerSide STOPPED = runner(WorkspaceRuntimeStatus.STOPPED);

  private static final Long ROW = 42L;

  private Testable service;
  private ScriptedLauncher launcher;

  @BeforeEach
  void setUp() {
    launcher = new ScriptedLauncher();
    service = new Testable();
    service.agents = StubInstance.of(launcher);
    service.processes = StubInstance.empty();
    service.agentActivity = StubInstance.empty();
    service.launchWindowMs = 200;
    service.pollIntervalMs = 10;
  }

  @Test
  void aQueuedRowParksTheLaunchOnNoThread() {
    service.sides(QUEUED, QUEUED);

    service.schedule(ROW, "go");

    assertTrue(service.isParked(ROW));
    assertTrue(service.isPending(ROW), "the claim is kept while parked");
    assertEquals(0, service.submitted.size(), "no thread, so no window is running");
    assertEquals(List.of(), launcher.probes, "nothing asks the daemon while parked");
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
    service.onTaken(new WorkspaceTaken(ROW, UUID.randomUUID()));
    assertEquals(0, service.submitted.size(), "a dropped launch is not released by a later take");
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
  void aShutdownDropsWhatIsParked() {
    service.sides(QUEUED, QUEUED);
    service.schedule(ROW, "go");

    service.shutdown();

    assertFalse(service.isParked(ROW));
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
    public boolean launch(Long workspaceRowId, String instruction) {
      launches.add(workspaceRowId + ":" + instruction);
      return true;
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
