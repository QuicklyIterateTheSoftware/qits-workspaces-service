package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.MoveRefusals;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Moving a regular DIRECT workspace onto a runner (qits-776): {@link MoveGate}'s matrix, the
 * compare-and-swap, the teardown and the credential, the door's bring-up and the direct-migration
 * sweep — against the fake container runtime, the fake daemon reports and a fake runner socket.
 *
 * <p>Every sweep here is scoped to the test's own rows: this module's database is shared by every
 * suite, and the oldest DIRECT row in it is somebody else's.
 */
@QuarkusTest
public class DirectPlacementMoveTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject DirectPlacementMove moves;
  @Inject MoveGate gate;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeContainerRuntime containers;
  @Inject FakeWorkspaceGitStatus gitStatus;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject FakeWorkspaceAgentActivity activity;
  @Inject FakeRunnerPlacement placement;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject TechnicalProcessRegistry processes;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<Long> rows = new ArrayList<>();
  private final List<String> labels = new ArrayList<>();
  private final List<UUID> createdRunners = new ArrayList<>();

  /** A public domain, so a moved RUNNING row can queue (qits-799): see WorkspaceRunnerPlacementTest. */
  @BeforeEach
  void aPublicDomain() {
    QuarkusMock.installMockForType(
        new WorkspaceAddressPlanes() {
          @Override
          public WorkspaceAddressPlane plane() {
            return WorkspaceAddressPlane.of("wohlben.eu", List.of("registry.dev.localhost:8080"));
          }
        },
        WorkspaceAddressPlanes.class);
  }

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaceRepository
                            .findByIdOptional(id)
                            .filter(w -> w.status == WorkspaceStatus.ACTIVE)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    QuarkusTransaction.requiringNew().run(() -> createdRunners.forEach(runnerRepository::deleteById));
    rows.forEach(
        id -> {
          gitStatus.forget(id);
          liveness.markDead(id);
          activity.forget(id);
        });
    labels.forEach(containers::clearVolumeExists);
    rows.clear();
    labels.clear();
    createdRunners.clear();
    placement.reset();
    commissioner.reset();
  }

  // --- the gate -------------------------------------------------------------------------------

  @Test
  public void aCleanPushedRunningRowPasses() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "g-ok");
    reportCleanAndPushed(repoId, row);

    assertEquals(MoveGate.Passage.CLEAN_AND_PUSHED, gate.check(read(row.id)));
  }

  @Test
  public void aDirtyTreeRefusesDirty() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "g-dirty");
    gitStatus.report(row.id, false, branchSha(repoId, "g-dirty"));

    assertRefused(400, MoveRefusals.DIRTY, () -> gate.check(read(row.id)));
  }

  @Test
  public void anUnreportedTreeRefusesUnknown() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "g-unknown");
    gitStatus.forget(row.id);

    DomainException refused = assertRefused(400, MoveRefusals.UNKNOWN, () -> gate.check(read(row.id)));
    assertTrue(refused.getMessage().contains("unknown"), refused.getMessage());
  }

  /** The mutation check's target: drop {@code isFullyPushed} from the gate and this one fails. */
  @Test
  public void aHeadTheGitHostDoesNotHoldRefusesUnpushed() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "g-unpushed");
    gitStatus.report(row.id, true, "0123456789abcdef0123456789abcdef01234567");

    assertRefused(400, MoveRefusals.UNPUSHED, () -> gate.check(read(row.id)));
  }

  @Test
  public void aProvisioningRowRefusesProvisioning() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "g-prov");
    reportCleanAndPushed(repoId, row);
    update(row.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING);

    assertRefused(400, MoveRefusals.PROVISIONING, () -> gate.check(read(row.id)));
  }

  @Test
  public void adminAndEditorRowsAreNotRegular() {
    Workspace admin = detached("adm");
    admin.admin = true;
    Workspace editor = detached("edt");
    editor.editor = true;

    assertRefused(400, MoveRefusals.NOT_REGULAR, () -> gate.check(admin));
    assertRefused(400, MoveRefusals.NOT_REGULAR, () -> gate.check(editor));
  }

  @Test
  public void aRunnerRowIsAlreadyMoved() {
    Workspace runner = detached("rnr");
    runner.placement = WorkspacePlacement.RUNNER;

    assertRefused(409, MoveRefusals.ALREADY_MOVED, () -> gate.check(runner));
  }

  @Test
  public void noContainerAndNoVolumePassesTrivially() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "g-empty");
    // No daemon report at all: with nothing on the host, there is nothing it could report about.
    assertEquals(MoveGate.Passage.NOTHING_TO_LOSE, gate.check(read(row.id)));
  }

  @Test
  public void noContainerButAVolumeIsNotTrivial() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "g-vol");
    containers.setVolumeExists("g-vol", true);

    assertRefused(400, MoveRefusals.UNKNOWN, () -> gate.check(read(row.id)));
  }

  // --- the move -------------------------------------------------------------------------------

  @Test
  public void aRunningRowIsTornDownDecommissionedAndQueuedOnTheRunners() throws Exception {
    commissioner.wire();
    String repoId = repo();
    Workspace row = runningDirect(repoId, "m-run");
    String oldClient = read(row.id).commissionedClientId;
    assertNotNull(oldClient, "the DIRECT container was commissioned a client");
    reportCleanAndPushed(repoId, row);
    String container = containers.containerName("m-run", repoId);
    containers.clearTeardownCalls();

    moves.move(row.id, null);

    Workspace moved = read(row.id);
    assertEquals(WorkspacePlacement.RUNNER, moved.placement);
    assertNull(moved.runnerId);
    assertEquals(
        WorkspaceRuntimeStatus.QUEUED,
        moved.runtimeStatus,
        "a RUNNING row is started on the runners, and waits QUEUED while none takes it");
    assertFalse(containers.exists(container), "the DIRECT container is gone");
    assertFalse(containers.workspaceVolumeExists("m-run"), "and so is its volume");
    assertEquals(List.of("stop:" + container, "rm:" + container), containers.teardownCalls());
    assertTrue(
        commissioner.decommissioned().contains(oldClient),
        "the row's old client was given back: " + commissioner.decommissioned());
    assertNull(moved.commissionedClientId);
    assertNull(moved.commissionedClientSecret);
    assertNotNull(moved.commissionedTokenId, "the RUNNER start minted the row's workspace token");
  }

  @Test
  public void aStoppedEmptyRowIsLeftStoppedOnNoRunner() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "m-stopped");

    moves.move(row.id, null);

    Workspace moved = read(row.id);
    assertEquals(WorkspacePlacement.RUNNER, moved.placement);
    assertNull(moved.runnerId);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, moved.runtimeStatus);
    assertNull(moved.queuedAt, "nothing was started: the row waits for a person to start it");
  }

  @Test
  public void twoConcurrentMovesTearDownOnceAndTheOtherIsAlreadyMoved() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "m-race");
    reportCleanAndPushed(repoId, row);
    String container = containers.containerName("m-race", repoId);
    containers.clearTeardownCalls();

    CountDownLatch go = new CountDownLatch(1);
    Callable<Integer> attempt =
        () -> {
          go.await();
          try {
            moves.move(row.id, null);
            return 200;
          } catch (DomainException e) {
            assertEquals(MoveRefusals.ALREADY_MOVED, e.code());
            return e.statusCode();
          }
        };
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> a = pool.submit(attempt);
      Future<Integer> b = pool.submit(attempt);
      go.countDown();
      List<Integer> outcomes = new ArrayList<>(List.of(a.get(), b.get()));
      outcomes.sort(Integer::compareTo);
      assertEquals(List.of(200, 409), outcomes);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(
        1,
        containers.teardownCalls().stream().filter(("rm:" + container)::equals).count(),
        "exactly one teardown: " + containers.teardownCalls());
  }

  @Test
  public void aMoveRefusedByTheGateChangesNothing() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "m-refused");
    gitStatus.report(row.id, false, branchSha(repoId, "m-refused"));
    String container = containers.containerName("m-refused", repoId);

    assertRefused(400, MoveRefusals.DIRTY, () -> moves.move(row.id, null));
    assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
    assertTrue(containers.isRunning(container));
  }

  // --- the door's bring-up ----------------------------------------------------------------------

  @Test
  public void theDoorBringsUpAGoneContainerWhoseVolumeSurvivesThenMovesIt() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "d-vol");
    containers.setVolumeExists("d-vol", true);
    // The daemon the bring-up starts reports clean, at the branch's head.
    reportCleanAndPushed(repoId, row);
    String container = containers.containerName("d-vol", repoId);

    String processId = moves.beginMove(row.id);

    assertNotNull(processId);
    await(() -> read(row.id).placement == WorkspacePlacement.RUNNER);
    await(() -> read(row.id).runtimeStatus == WorkspaceRuntimeStatus.QUEUED);
    assertEquals(1, containers.runCount(container), "the door provisioned it on the DIRECT path");
    assertFalse(containers.exists(container), "and the move removed it again");
  }

  @Test
  public void theDoorRefusesARunningDirtyRowInTheRequest() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "d-dirty");
    gitStatus.report(row.id, false, branchSha(repoId, "d-dirty"));

    assertRefused(400, MoveRefusals.DIRTY, () -> moves.beginMove(row.id));
    assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
  }

  // --- the sweep ------------------------------------------------------------------------------

  @Test
  public void theSweepSkipsWhenNoRunnerHasAFreeSlot() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "s-noslot");
    // A runner in service, but not connected; and a connected one with no slot left.
    eligibleRunner(1);
    WorkspaceRunner full = eligibleRunner(1);
    placement.connect(full.id);
    Long occupant = occupying(repoId, "s-occupant", full.id);

    List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());

    assertEquals(1, decisions.size(), decisions.toString());
    assertFalse(decisions.get(0).moved());
    assertEquals(row.id, decisions.get(0).rowId());
    assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
    assertNotNull(occupant);
  }

  @Test
  public void theSweepMovesOneRowPerTickOldestFirst() throws Exception {
    String repoId = repo();
    Workspace older = direct(repoId, "s-old");
    Workspace newer = direct(repoId, "s-new");
    freeRunner();

    List<DirectPlacementMove.Decision> first = moves.sweep(mine());

    assertEquals(
        List.of(
            new DirectPlacementMove.Decision(
                older.id, true, "nothing to lose (no container, no volume); left stopped")),
        first);
    assertEquals(WorkspacePlacement.RUNNER, read(older.id).placement);
    assertEquals(WorkspacePlacement.DIRECT, read(newer.id).placement, "one row per tick");

    moves.sweep(mine());
    assertEquals(WorkspacePlacement.RUNNER, read(newer.id).placement, "the next tick takes it");
  }

  @Test
  public void theSweepNeverMovesARowWithALiveAgentSession() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "s-agent");
    reportCleanAndPushed(repoId, row);
    liveness.markLive(row.id);
    freeRunner();

    for (AgentActivityState busy :
        List.of(AgentActivityState.BUSY, AgentActivityState.WAITING, AgentActivityState.IDLE)) {
      activity.report(row.id, busy);
      List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());
      assertEquals(1, decisions.size(), decisions.toString());
      assertFalse(decisions.get(0).moved(), busy + ": " + decisions);
      assertTrue(decisions.get(0).reason().contains(busy.name()), decisions.toString());
      assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
    }

    activity.report(row.id, AgentActivityState.ENDED);
    List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());
    assertTrue(decisions.get(0).moved(), "an ENDED session is no session: " + decisions);
    assertEquals(WorkspacePlacement.RUNNER, read(row.id).placement);
  }

  @Test
  public void theSweepNeedsAConnectedDaemon() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "s-nodaemon");
    reportCleanAndPushed(repoId, row);
    freeRunner();

    List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());

    assertFalse(decisions.get(0).moved(), decisions.toString());
    assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
  }

  @Test
  public void theSweepNeverStartsAStoppedContainer() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "s-stopped");
    reportCleanAndPushed(repoId, row);
    workspaceService.stopContainer(row.id);
    String container = containers.containerName("s-stopped", repoId);
    int startsBefore = containers.startCount(container);
    freeRunner();

    List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());

    assertFalse(decisions.get(0).moved(), decisions.toString());
    assertEquals(startsBefore, containers.startCount(container), "no start on the DIRECT path");
    assertTrue(containers.exists(container));
    assertFalse(containers.isRunning(container));
  }

  @Test
  public void theSweepLeavesAGoneContainerWithASurvivingVolumeToTheDoor() throws Exception {
    String repoId = repo();
    Workspace row = direct(repoId, "s-vol");
    containers.setVolumeExists("s-vol", true);
    freeRunner();

    List<DirectPlacementMove.Decision> decisions = moves.sweep(mine());

    assertFalse(decisions.get(0).moved(), decisions.toString());
    assertTrue(decisions.get(0).reason().contains("volume"), decisions.toString());
    assertEquals(0, containers.runCount(containers.containerName("s-vol", repoId)));
    assertEquals(WorkspacePlacement.DIRECT, read(row.id).placement);
  }

  @Test
  public void aFailedTeardownLeavesARunnerRowAndTheNextTickRetriesTheRm() throws Exception {
    commissioner.wire();
    String repoId = repo();
    Workspace row = runningDirect(repoId, "o-orphan");
    String oldClient = read(row.id).commissionedClientId;
    reportCleanAndPushed(repoId, row);
    liveness.markLive(row.id);
    String container = containers.containerName("o-orphan", repoId);
    containers.throwOnNextRm(container);
    freeRunner();

    List<DirectPlacementMove.Decision> first = moves.sweep(mine());

    assertTrue(first.get(0).moved(), first.toString());
    Workspace moved = read(row.id);
    assertEquals(WorkspacePlacement.RUNNER, moved.placement, "the swap committed before the rm");
    assertEquals(
        WorkspaceRuntimeStatus.STOPPED,
        moved.runtimeStatus,
        "not started on a runner while its old container may still run");
    assertTrue(containers.exists(container), "the DIRECT container is an orphan now");
    assertTrue(commissioner.decommissioned().contains(oldClient), "its client went back anyway");
    assertNull(moved.commissionedClientId);

    containers.clearTeardownCalls();
    List<DirectPlacementMove.Decision> second = moves.sweep(mine());

    assertTrue(
        second.stream().anyMatch(d -> d.rowId().equals(row.id) && d.reason().contains("direct-orphan")),
        second.toString());
    assertTrue(containers.teardownCalls().contains("rm:" + container), containers.teardownCalls().toString());
    assertFalse(containers.exists(container), "the retry removed it");
    assertEquals(WorkspacePlacement.RUNNER, read(row.id).placement);
  }

  // --- the read model -------------------------------------------------------------------------

  @Test
  public void pushedIsAnsweredForARegularDirectRowOnly() throws Exception {
    String repoId = repo();
    Workspace row = runningDirect(repoId, "r-pushed");

    gitStatus.forget(row.id);
    assertNull(listed(repoId, row.id).pushed(), "no head reported: unknown");
    reportCleanAndPushed(repoId, row);
    assertEquals(Boolean.TRUE, listed(repoId, row.id).pushed());
    gitStatus.report(row.id, true, "0123456789abcdef0123456789abcdef01234567");
    assertEquals(Boolean.FALSE, listed(repoId, row.id).pushed());
    assertFalse(listed(repoId, row.id).editor());

    // A row this test method never listed: the listing's entities are request-scoped here, and the
    // move's swap is a bulk update that does not refresh one already loaded.
    Workspace moved = runningDirect(repoId, "r-moved");
    reportCleanAndPushed(repoId, moved);
    moves.move(moved.id, null);
    assertEquals(WorkspacePlacement.RUNNER, read(moved.id).placement);
    assertNull(listed(repoId, moved.id).pushed(), "a RUNNER row is never asked");
  }

  // --- fixtures -------------------------------------------------------------------------------

  private Mine mine() {
    return new Mine(Set.copyOf(rows));
  }

  /** The sweep's scope: this test's rows, the set taken when the sweep is asked. */
  private record Mine(Set<Long> ids) implements java.util.function.Predicate<Workspace> {
    @Override
    public boolean test(Workspace w) {
      return ids.contains(w.id);
    }
  }

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  /** A regular DIRECT row as the estate holds them, never provisioned. */
  private Workspace direct(String repoId, String label) {
    Workspace row =
        LegacyDirectRows.direct(
            () -> workspaceService.createWorkspace(repoId, label, "master", label, null));
    rows.add(row.id);
    labels.add(label);
    return row;
  }

  /** {@link #direct}, provisioned and RUNNING on the platform host. */
  private Workspace runningDirect(String repoId, String label) {
    Workspace row = direct(repoId, label);
    workspaceService.ensureContainer(row.id);
    assertTrue(containers.isRunning(containers.containerName(label, repoId)));
    return read(row.id);
  }

  private void reportCleanAndPushed(String repoId, Workspace row) throws Exception {
    gitStatus.report(row.id, true, branchSha(repoId, row.branch));
  }

  private String branchSha(String repoId, String branch) throws Exception {
    File origin = Path.of(dataDir, repoId, "origin").toAbsolutePath().toFile();
    return TestGit.exec(origin, "git", "rev-parse", "refs/heads/" + branch).trim();
  }

  private static Workspace detached(String label) {
    Workspace w = new Workspace();
    w.id = -1L;
    w.workspaceId = label;
    w.repositoryId = "repo-detached";
    w.branch = label;
    w.placement = WorkspacePlacement.DIRECT;
    w.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    w.status = WorkspaceStatus.ACTIVE;
    return w;
  }

  private WorkspaceRunner eligibleRunner(int slots) {
    UUID id = UUID.randomUUID();
    runners.create(id, "r-" + id.toString().substring(0, 8), null, slots, "token-" + id, "sub-" + id);
    createdRunners.add(id);
    runners.markRegistered(id, "client-" + id, null);
    return runners.greenlight(id);
  }

  /** A connected runner in service with a free slot. */
  private WorkspaceRunner freeRunner() {
    WorkspaceRunner runner = eligibleRunner(2);
    placement.connect(runner.id);
    return runner;
  }

  /** A RUNNING RUNNER row on {@code runnerId}, holding one of its slots. */
  private Long occupying(String repoId, String label, UUID runnerId) {
    Workspace row = workspaceService.createWorkspace(repoId, label, "master", label, null);
    rows.add(row.id);
    update(
        row.id,
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
        });
    return row.id;
  }

  private void update(Long rowId, Consumer<Workspace> change) {
    QuarkusTransaction.requiringNew().run(() -> change.accept(workspaceRepository.findById(rowId)));
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }

  private WorkspaceDto listed(String repoId, Long rowId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> rowId.equals(w.id()))
        .findFirst()
        .orElseThrow();
  }

  private static DomainException assertRefused(int status, String code, Runnable call) {
    DomainException refused = assertThrows(DomainException.class, call::run);
    assertEquals(status, refused.statusCode(), refused.getMessage());
    assertEquals(code, refused.code(), refused.getMessage());
    return refused;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("condition not reached within " + AWAIT_MILLIS + " ms");
  }
}
