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
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Placement in {@link WorkspaceService} (qits-853), against a fake {@link RunnerPlacement}: the
 * create field and its two refusals, every row of the RUNNER verb table, the listing's RUNNER branch
 * and its UNAVAILABLE overlay, the dispatch refusal — and that a DIRECT row never reaches the port
 * and still does what it always did.
 */
@QuarkusTest
public class WorkspaceRunnerPlacementTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject WorkspaceService workspaceService;
  @Inject DispatchService dispatchService;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeRunnerPlacement placement;
  @Inject FakeContainerRuntime containers;
  @Inject RunnerClaims claims;
  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

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
                            .filter(w -> w.status == WorkspaceStatus.ACTIVE)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    QuarkusTransaction.requiringNew().run(() -> createdRunners.forEach(runnerRepository::deleteById));
    rows.clear();
    createdRunners.clear();
    placement.reset();
  }

  // --- create -------------------------------------------------------------------------------------

  @Test
  public void aRunnerWorkspaceForAnAdminIsRefusedBeforeAnyBranchIsPushed() throws Exception {
    eligibleRunner();
    String repoId = repo();

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> createRunnerRow(repoId, "adm", true));
    assertEquals(400, refused.statusCode());
    assertFalse(workspaceService.branchExists(repoId, "adm"), "nothing was pushed");
  }

  @Test
  public void aRunnerWorkspaceWithNoEligibleRunnerIs409NoRunner() throws Exception {
    // A runner exists, but it is quarantined (registered and not yet health-checked): not eligible.
    WorkspaceRunner quarantined = runner(1);
    runners.markRegistered(quarantined.id, "client-" + quarantined.id, null);
    String repoId = repo();

    ConflictException refused =
        assertThrows(ConflictException.class, () -> createRunnerRow(repoId, "none", false));
    assertEquals(RunnerRefusals.NO_RUNNER, refused.code());
    assertFalse(workspaceService.branchExists(repoId, "none"), "nothing was pushed");
  }

  @Test
  public void aRunnerWorkspaceIsWrittenRunnerOnNoRunnerAndStopped() throws Exception {
    eligibleRunner();
    String repoId = repo();

    Workspace created = createRunnerRow(repoId, "placed", false);

    Workspace row = read(created.id);
    assertEquals(WorkspacePlacement.RUNNER, row.placement);
    assertNull(row.runnerId);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
  }

  @Test
  public void everyCreateThatStatesNoPlacementIsDirect() throws Exception {
    eligibleRunner();
    String repoId = repo();

    Workspace created = workspaceService.createWorkspace(repoId, "plain", "master", "plain", null);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  // --- start --------------------------------------------------------------------------------------

  @Test
  public void startQueuesAStoppedRowAndTellsTheBacklog() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "start", false);

    String processId = workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.queuedAt);
    assertNull(row.runnerId);
    assertNotNull(processId);
    assertEquals(processId, claims.trackedStart(created.id).orElseThrow().id());
    assertTrue(placement.calls().contains("backlog:" + created.id), placement.calls().toString());
    assertFalse(
        containers.exists(containers.containerName("start", repoId)),
        "nothing ran on the platform host");

    // A second press is a no-op that joins the first start's process.
    assertEquals(processId, workspaceService.beginEnsureContainer(created.id));
  }

  @Test
  public void startQueuesAFailedRowAndKeepsItsRunner() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "failed", false);
    update(
        created.id,
        w -> {
          w.runnerId = runner.id;
          w.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
          w.runtimeError = "boom";
        });

    workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertEquals(runner.id, row.runnerId, "sticky: the row keeps its runner");
    assertNull(row.runtimeError);
  }

  @Test
  public void startOnARunningOrProvisioningRowIsANoOp() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "live", false);
    for (WorkspaceRuntimeStatus live :
        List.of(WorkspaceRuntimeStatus.RUNNING, WorkspaceRuntimeStatus.PROVISIONING)) {
      update(
          created.id,
          w -> {
            w.runnerId = runner.id;
            w.runtimeStatus = live;
          });

      workspaceService.beginEnsureContainer(created.id);

      assertEquals(live, read(created.id).runtimeStatus);
    }
    assertFalse(placement.calls().stream().anyMatch(c -> c.startsWith("backlog:")));
  }

  @Test
  public void startOnAnUnavailableRowIs409() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "gone", false);
    update(created.id, w -> w.runnerId = runner.id);

    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> workspaceService.beginEnsureContainer(created.id));
    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(created.id).runtimeStatus);
  }

  @Test
  public void startOnARowWhoseBranchIsGoneAbandonsIt() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "lost", false);
    TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), "git", "branch", "-D", "lost");

    assertThrows(NotFoundException.class, () -> workspaceService.beginEnsureContainer(created.id));

    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);
  }

  // --- stop ---------------------------------------------------------------------------------------

  @Test
  public void stoppingAQueuedRowSendsNoFrame() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "unqueue", false);
    workspaceService.beginEnsureContainer(created.id);

    workspaceService.stopContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.queuedAt);
    assertFalse(placement.calls().stream().anyMatch(c -> c.startsWith("stop:")));
    assertTrue(claims.trackedStart(created.id).isEmpty(), "the start's process ended");
  }

  @Test
  public void stoppingAPlacedRowIsRoutedToItsRunnerThenStopped() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "stop", false);
    running(created.id, runner.id);

    workspaceService.stopContainer(created.id);

    assertTrue(placement.calls().contains("stop:" + created.id));
    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertEquals(runner.id, row.runnerId, "a stop keeps the runner: the volume is there");
  }

  @Test
  public void stoppingOnAnOfflineRunnerIs409AndChangesNothing() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "offline", false);
    running(created.id, runner.id);

    ConflictException refused =
        assertThrows(ConflictException.class, () -> workspaceService.stopContainer(created.id));
    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(created.id).runtimeStatus);
  }

  @Test
  public void aStopTheRunnerNeverAnswersIs504AndChangesNothing() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.neverAnswer(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "silent", false);
    running(created.id, runner.id);

    DomainException refused =
        assertThrows(DomainException.class, () -> workspaceService.stopContainer(created.id));
    assertEquals(504, refused.statusCode());
    assertEquals(RunnerRefusals.RUNNER_TIMEOUT, refused.code());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(created.id).runtimeStatus);

    DomainException deleteRefused =
        assertThrows(DomainException.class, () -> workspaceService.deleteContainer(created.id));
    assertEquals(504, deleteRefused.statusCode());
    assertEquals(runner.id, read(created.id).runnerId, "the row is unchanged");
  }

  // --- delete-container, recreate, resolution -----------------------------------------------------

  @Test
  public void deletingTheContainerIsRoutedAndClearsTheRunnerWhileTheRowStaysActive()
      throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "reset", false);
    running(created.id, runner.id);

    workspaceService.deleteContainer(created.id);

    assertTrue(placement.calls().contains("delete:" + created.id));
    assertTrue(placement.calls().contains("estate:" + runner.id));
    Workspace row = read(created.id);
    assertEquals(WorkspaceStatus.ACTIVE, row.status);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.runnerId, "stickiness ends: nothing of the row is left on that node");
  }

  @Test
  public void deletingOnAnOfflineRunnerIs409() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "offdel", false);
    running(created.id, runner.id);

    ConflictException refused =
        assertThrows(ConflictException.class, () -> workspaceService.deleteContainer(created.id));
    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertEquals(runner.id, read(created.id).runnerId);
  }

  @Test
  public void recreateIsRefusedByTheCleanTreeGateWithNoDaemon() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "recreate", false);
    running(created.id, runner.id);

    assertThrows(
        BadRequestException.class, () -> workspaceService.beginRecreateContainer(created.id));
    assertFalse(placement.calls().stream().anyMatch(c -> c.startsWith("delete:")));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(created.id).runtimeStatus);
  }

  @Test
  public void aResolutionTellsAConnectedRunnerAndNeverWaitsOrTouchesTheHost() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "discard", false);
    running(created.id, runner.id);
    containers.clearTeardownCalls();

    workspaceService.discardWorkspace(created.id, null, true);

    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);
    assertTrue(placement.calls().contains("released:" + created.id));
    assertFalse(placement.calls().stream().anyMatch(c -> c.startsWith("delete:")), "no wait");
    assertEquals(List.of(), containers.teardownCalls(), "qits-containers was not asked");
    assertFalse(workspaceService.branchExists(repoId, "discard"), "the branch went as usual");
  }

  @Test
  public void aResolutionOfARowOnAnOfflineRunnerStillResolves() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "offdis", false);
    running(created.id, runner.id);

    workspaceService.discardWorkspace(created.id, null, true);

    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);
  }

  // --- the listing --------------------------------------------------------------------------------

  @Test
  public void theListingTakesARunnerRowsOwnStatusAndOverlaysUnavailable() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace onRunner = createRunnerRow(repoId, "listed", false);
    Workspace unplaced = createRunnerRow(repoId, "unplaced", false);
    running(onRunner.id, runner.id);
    update(
        unplaced.id,
        w -> {
          w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
          w.queuedAt = Instant.now();
        });
    // A running platform-host container under the unplaced row's label: the qits-containers
    // listing would call it RUNNING, and a RUNNER row must not read it.
    containers.run(repoId, "unplaced", unplaced.id, "unplaced", "master");

    placement.connect(runner.id);
    assertEquals(WorkspaceRuntimeStatus.RUNNING, listed(repoId, onRunner.id).runtimeStatus());
    assertEquals(WorkspaceRuntimeStatus.QUEUED, listed(repoId, unplaced.id).runtimeStatus());

    placement.disconnect(runner.id);
    assertEquals(WorkspaceRuntimeStatus.UNAVAILABLE, listed(repoId, onRunner.id).runtimeStatus());
    assertEquals(
        WorkspaceRuntimeStatus.UNAVAILABLE, workspaceService.getWorkspace(onRunner.id).runtimeStatus());
    assertEquals(
        WorkspaceRuntimeStatus.QUEUED,
        listed(repoId, unplaced.id).runtimeStatus(),
        "a row on no runner waits for any and is never UNAVAILABLE");
    assertEquals(
        WorkspaceRuntimeStatus.RUNNING, read(onRunner.id).runtimeStatus, "the overlay is not stored");
    containers.rm(containers.containerName("unplaced", repoId));
  }

  // --- dispatch -----------------------------------------------------------------------------------

  @Test
  public void aDispatchOntoARunnerRowIs409() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "dispatched", false);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                dispatchService.dispatch(
                    repoId, "dispatched", false, null, WorkspaceSubject.none(), "go"));
    assertEquals(RunnerRefusals.RUNNER_DISPATCH_UNSUPPORTED, refused.code());
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(created.id).runtimeStatus);
  }

  /**
   * A delivery onto a RUNNER row is refused as a dispatch is, and for its reason: its fallback arm
   * starts the container and waits a fixed window for the daemon, and a RUNNER start only queues.
   */
  @Test
  public void aDeliveryOntoARunnerRowIs409AndStartsNothing() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = createRunnerRow(repoId, "delivered", false);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () -> dispatchService.deliver(repoId, "delivered", "next phase", false));
    assertEquals(RunnerRefusals.RUNNER_DISPATCH_UNSUPPORTED, refused.code());
    assertEquals(
        WorkspaceRuntimeStatus.STOPPED, read(created.id).runtimeStatus, "nothing was queued");
  }

  // --- the seam -----------------------------------------------------------------------------------

  /**
   * A DIRECT row never reaches {@link RunnerPlacement}, and every verb still does to it what it did
   * before placement existed: start provisions on the platform host, the listing reads the
   * qits-containers listing, stop stops in place, delete-container removes. This is the test that
   * fails when a verb's placement branch is flipped.
   */
  @Test
  public void aDirectRowNeverReachesThePortAndKeepsItsLadder() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = workspaceService.createWorkspace(repoId, "direct", "master", "direct", null);
    rows.add(created.id);
    String container = containers.containerName("direct", repoId);

    workspaceService.beginEnsureContainer(created.id);
    awaitRunning(repoId, created.id);
    assertTrue(containers.isRunning(container), "start provisioned on the platform host");

    workspaceService.stopContainer(created.id);
    assertTrue(containers.exists(container), "stop pauses in place");
    assertFalse(containers.isRunning(container), "stop stopped the host container");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(created.id).runtimeStatus);

    workspaceService.deleteContainer(created.id);
    assertFalse(containers.exists(container), "delete-container removed the host container");

    workspaceService.discardWorkspace(created.id, null, true);
    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);

    assertEquals(
        List.of(),
        placement.calls(),
        "a DIRECT row never reaches RunnerPlacement");
  }

  // --- helpers ------------------------------------------------------------------------------------

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  private Workspace createRunnerRow(String repoId, String label, boolean admin) {
    Workspace created =
        workspaceService.createWorkspace(
            repoId,
            label,
            "master",
            label,
            null,
            false,
            false,
            admin,
            WorkspaceSubject.none(),
            null,
            WorkspacePlacement.RUNNER);
    rows.add(created.id);
    return created;
  }

  private WorkspaceRunner runner(int slots) {
    UUID id = UUID.randomUUID();
    WorkspaceRunner runner =
        runners.create(
            id, "r-" + id.toString().substring(0, 8), null, slots, "token-" + id, "sub-" + id);
    createdRunners.add(id);
    return runner;
  }

  private WorkspaceRunner eligibleRunner() {
    WorkspaceRunner runner = runner(1);
    runners.markRegistered(runner.id, "client-" + runner.id, null);
    return runners.greenlight(runner.id);
  }

  private void running(Long rowId, UUID runnerId) {
    update(
        rowId,
        w -> {
          w.runnerId = runnerId;
          w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
        });
  }

  private void update(Long rowId, Consumer<Workspace> change) {
    QuarkusTransaction.requiringNew()
        .run(() -> change.accept(workspaceRepository.findById(rowId)));
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

  private void awaitRunning(String repoId, Long rowId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (read(rowId).runtimeStatus != WorkspaceRuntimeStatus.RUNNING
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(rowId).runtimeStatus, "provisioned in time");
  }
}
