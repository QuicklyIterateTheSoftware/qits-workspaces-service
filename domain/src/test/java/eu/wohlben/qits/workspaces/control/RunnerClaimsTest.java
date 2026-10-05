package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The runner session's writes (qits-851): reserve is the claim, the launch results, the inventory
 * reconcile, and the backlog and estate reads. Rows are written directly; what is proved is the
 * compare-and-swap and the rules around it, against the real postgres the suite runs on.
 */
@QuarkusTest
public class RunnerClaimsTest {

  @Inject RunnerClaims claims;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject FakeRunnerPlacement placement;

  private final List<UUID> createdRunners = new ArrayList<>();
  private final List<Long> rows = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaceRepository
                            .findByIdOptional(id)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    QuarkusTransaction.requiringNew().run(() -> createdRunners.forEach(runnerRepository::deleteById));
    rows.clear();
    createdRunners.clear();
    placement.reset();
  }

  // --- reserve ------------------------------------------------------------------------------------

  @Test
  public void twoRunnersRacingForOneNeverPlacedRowGetItOnce() throws Exception {
    WorkspaceRunner a = eligibleRunner(1);
    WorkspaceRunner b = eligibleRunner(1);
    Long row = queued(null, Instant.now());

    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Optional<Workspace>> reserveA =
          () -> {
            go.await();
            return claims.reserveFor(a);
          };
      Callable<Optional<Workspace>> reserveB =
          () -> {
            go.await();
            return claims.reserveFor(b);
          };
      Future<Optional<Workspace>> fa = pool.submit(reserveA);
      Future<Optional<Workspace>> fb = pool.submit(reserveB);
      go.countDown();
      Optional<Workspace> ra = fa.get();
      Optional<Workspace> rb = fb.get();

      assertEquals(1, (ra.isPresent() ? 1 : 0) + (rb.isPresent() ? 1 : 0), "exactly one claim");
      UUID winner = ra.isPresent() ? a.id : b.id;
      Workspace claimed = read(row);
      assertEquals(WorkspaceRuntimeStatus.PROVISIONING, claimed.runtimeStatus);
      assertEquals(winner, claimed.runnerId);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  public void theCompareAndSwapChangesARowOnce() {
    WorkspaceRunner a = eligibleRunner(1);
    WorkspaceRunner b = eligibleRunner(1);
    Long row = queued(null, Instant.now());

    assertEquals(1, cas(row, a.id));
    assertEquals(0, cas(row, b.id), "the second runner's swap finds the row PROVISIONING");
    assertEquals(0, cas(row, a.id), "and so does the first runner's second");
    assertEquals(a.id, read(row).runnerId);
  }

  @Test
  public void aStickyRowGoesOnlyToItsRunnerAndBeforeAnyUnplacedOne() {
    WorkspaceRunner a = eligibleRunner(2);
    WorkspaceRunner b = eligibleRunner(1);
    Instant now = Instant.now();
    Long unplaced = queued(null, now.minusSeconds(60));
    Long sticky = queued(a.id, now);

    assertEquals(unplaced, claims.reserveFor(b).orElseThrow().id, "b may take only the unplaced");
    assertTrue(claims.reserveFor(b).isEmpty(), "and never a's sticky row (b is full anyway)");

    WorkspaceRunner c = eligibleRunner(1);
    assertTrue(claims.reserveFor(c).isEmpty(), "a's sticky row is a's alone");
    assertEquals(sticky, claims.reserveFor(a).orElseThrow().id);
  }

  @Test
  public void stickyFirstThenUnplacedEachOldestFirst() {
    WorkspaceRunner a = eligibleRunner(4);
    Instant now = Instant.now();
    Long unplacedOld = queued(null, now.minusSeconds(300));
    Long stickyNew = queued(a.id, now);
    Long stickyOld = queued(a.id, now.minusSeconds(60));
    Long unplacedNew = queued(null, now.minusSeconds(30));

    assertEquals(stickyOld, claims.reserveFor(a).orElseThrow().id);
    assertEquals(stickyNew, claims.reserveFor(a).orElseThrow().id);
    assertEquals(unplacedOld, claims.reserveFor(a).orElseThrow().id);
    assertEquals(unplacedNew, claims.reserveFor(a).orElseThrow().id);
    assertTrue(claims.reserveFor(a).isEmpty());
  }

  @Test
  public void aFullRunnerGetsNothingAndTheRowStaysQueued() {
    WorkspaceRunner a = eligibleRunner(1);
    insert(
        w -> {
          w.runnerId = a.id;
          w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
        });
    Long waiting = queued(a.id, Instant.now());

    assertTrue(claims.reserveFor(a).isEmpty());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(waiting).runtimeStatus);
  }

  @Test
  public void aQuarantinedRunnerGetsNothing() {
    WorkspaceRunner a = eligibleRunner(1);
    runners.quarantine(a.id, "test");
    Long waiting = queued(null, Instant.now());

    assertTrue(claims.reserveFor(a).isEmpty());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(waiting).runtimeStatus);
  }

  @Test
  public void aFailedLaunchFreesTheSlotForTheNextRow() {
    WorkspaceRunner a = eligibleRunner(1);
    Instant now = Instant.now();
    Long first = queued(null, now.minusSeconds(10));
    Long second = queued(null, now);

    assertEquals(first, claims.reserveFor(a).orElseThrow().id);
    assertTrue(claims.reserveFor(a).isEmpty(), "one slot, held by the take");

    assertTrue(claims.launchFailed(a.id, first, "pull refused"));
    Workspace failed = read(first);
    assertEquals(WorkspaceRuntimeStatus.FAILED, failed.runtimeStatus);
    assertEquals("pull refused", failed.runtimeError);

    assertEquals(second, claims.reserveFor(a).orElseThrow().id);
  }

  // --- results ------------------------------------------------------------------------------------

  @Test
  public void resultsMoveOnlyARowTheRunnerOwns() {
    WorkspaceRunner a = eligibleRunner(1);
    WorkspaceRunner b = eligibleRunner(1);
    Long row = queued(null, Instant.now());
    claims.reserveFor(a).orElseThrow();

    assertFalse(claims.launched(b.id, row), "not b's row");
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, read(row).runtimeStatus);

    assertTrue(claims.launched(a.id, row));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(row).runtimeStatus);

    assertTrue(claims.exited(a.id, row));
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(row).runtimeStatus);
    assertEquals(a.id, read(row).runnerId, "stopped keeps its runner");

    assertTrue(claims.deleted(a.id, row));
    Workspace deleted = read(row);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, deleted.runtimeStatus);
    assertNull(deleted.runnerId, "deleted clears the runner");
    assertEquals(WorkspaceStatus.ACTIVE, deleted.status);
  }

  @Test
  public void anExitDoesNotKnockAQueuedRowOutOfTheQueue() {
    WorkspaceRunner a = eligibleRunner(1);
    Long row = queued(a.id, Instant.now());

    assertTrue(claims.exited(a.id, row));
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(row).runtimeStatus);
  }

  // --- the inventory reconcile --------------------------------------------------------------------

  @Test
  public void theReconcileTableRowByRow() {
    WorkspaceRunner a = eligibleRunner(4);
    WorkspaceRunner b = eligibleRunner(1);
    Long heldRunning = placedOn(a.id, WorkspaceRuntimeStatus.STOPPED);
    Long heldStopped = placedOn(a.id, WorkspaceRuntimeStatus.RUNNING);
    Long provisioningNotHeld = placedOn(a.id, WorkspaceRuntimeStatus.PROVISIONING);
    Long runningNotHeld = placedOn(a.id, WorkspaceRuntimeStatus.RUNNING);
    Long queuedHeldStopped = queued(a.id, Instant.now());
    Long stoppedNotHeld = placedOn(a.id, WorkspaceRuntimeStatus.STOPPED);
    Long othersRow = placedOn(b.id, WorkspaceRuntimeStatus.STOPPED);

    List<Long> changed =
        claims.reconcile(
            a.id,
            List.of(
                new RunnerClaims.HeldContainer(heldRunning, true),
                new RunnerClaims.HeldContainer(heldStopped, false),
                new RunnerClaims.HeldContainer(queuedHeldStopped, false),
                new RunnerClaims.HeldContainer(othersRow, true)));

    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(heldRunning).runtimeStatus);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(heldStopped).runtimeStatus);
    Workspace requeued = read(provisioningNotHeld);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, requeued.runtimeStatus);
    assertEquals(a.id, requeued.runnerId, "back in the queue, still a's");
    assertNotNull(requeued.queuedAt);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(runningNotHeld).runtimeStatus);
    assertEquals(
        WorkspaceRuntimeStatus.QUEUED,
        read(queuedHeldStopped).runtimeStatus,
        "a start waiting for its stopped container is not cancelled");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(stoppedNotHeld).runtimeStatus);
    Workspace others = read(othersRow);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, others.runtimeStatus, "never reassigned");
    assertEquals(b.id, others.runnerId);
    assertEquals(
        Set.of(heldRunning, heldStopped, provisioningNotHeld, runningNotHeld), Set.copyOf(changed));
    assertTrue(placement.calls().contains("backlog:" + provisioningNotHeld));
  }

  // --- reads --------------------------------------------------------------------------------------

  @Test
  public void theBacklogCountsOwnAndUnplacedQueuedRowsAndTheEstateOwnedOnes() {
    WorkspaceRunner a = eligibleRunner(1);
    WorkspaceRunner b = eligibleRunner(1);
    Long own = queued(a.id, Instant.now());
    queued(null, Instant.now());
    queued(b.id, Instant.now());
    Long stopped = placedOn(a.id, WorkspaceRuntimeStatus.STOPPED);

    assertEquals(2, claims.backlogFor(a.id));
    assertEquals(2, claims.backlogFor(b.id));
    assertEquals(List.of(own, stopped), claims.ownedRowIds(a.id));
  }

  // --- helpers ------------------------------------------------------------------------------------

  private WorkspaceRunner eligibleRunner(int slots) {
    UUID id = UUID.randomUUID();
    runners.create(id, "r-" + id.toString().substring(0, 8), null, slots, "token-" + id, "sub-" + id);
    createdRunners.add(id);
    runners.markRegistered(id, "client-" + id, null);
    return runners.greenlight(id);
  }

  private Long queued(UUID runnerId, Instant queuedAt) {
    return insert(
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
          w.queuedAt = queuedAt;
        });
  }

  private Long placedOn(UUID runnerId, WorkspaceRuntimeStatus status) {
    return insert(
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = status;
        });
  }

  /** One ACTIVE RUNNER row on a fresh branch, shaped by {@code shape}. */
  private Long insert(Consumer<Workspace> shape) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  String label = "w" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.workspaceId = label;
                  workspace.repositoryId = "repo-" + label;
                  workspace.branch = label;
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.placement = WorkspacePlacement.RUNNER;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  shape.accept(workspace);
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  return workspace.id;
                });
    rows.add(id);
    return id;
  }

  private int cas(Long row, UUID runnerId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.claimForRunner(row, runnerId));
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }
}
