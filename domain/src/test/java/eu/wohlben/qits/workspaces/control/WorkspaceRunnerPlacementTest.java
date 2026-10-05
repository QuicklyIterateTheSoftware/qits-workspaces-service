package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
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
import io.quarkus.test.junit.QuarkusMock;
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
import org.junit.jupiter.api.BeforeEach;
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
  @Inject TechnicalProcessRegistry processes;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeWorkspaceGitStatus gitStatus;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<UUID> createdRunners = new ArrayList<>();
  private final List<Long> rows = new ArrayList<>();

  /**
   * A public domain for every start here: the suites ship {@code qits.workspace.domain} empty, and a
   * RUNNER start refuses to queue without an edge plane (qits-799). Installed per test, so the
   * refusal case below can install its own.
   */
  @BeforeEach
  void aPublicDomain() {
    QuarkusMock.installMockForType(planesAt("wohlben.eu"), WorkspaceAddressPlanes.class);
  }

  private static WorkspaceAddressPlanes planesAt(String publicDomain) {
    return new WorkspaceAddressPlanes() {
      @Override
      public WorkspaceAddressPlane plane() {
        return WorkspaceAddressPlane.of(publicDomain, List.of("registry.dev.localhost:8080"));
      }
    };
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
    rows.forEach(gitStatus::forget);
    rows.clear();
    createdRunners.clear();
    placement.reset();
    commissioner.reset();
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

  /**
   * Creating a RUNNER row is the request a runner takes (the qits-ci runner model): it is written
   * QUEUED with {@code queued_at}, on no runner, the backlog is told once it committed — and a
   * runner's reserve can take it at once.
   */
  @Test
  public void aRunnerWorkspaceIsWrittenQueuedOnNoRunnerAndTheBacklogTold() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();

    Workspace created = createRunnerRow(repoId, "placed", false);

    Workspace row = read(created.id);
    assertEquals(WorkspacePlacement.RUNNER, row.placement);
    assertNull(row.runnerId);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.queuedAt);
    assertEquals(List.of("backlog:" + created.id), placement.calls());
    assertTrue(claims.trackedStart(created.id).isEmpty(), "no process ceremony at create");

    Workspace taken = claims.reserveFor(runner).orElseThrow();
    assertEquals(created.id, taken.id);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, read(created.id).runtimeStatus);
    assertEquals(runner.id, read(created.id).runnerId);
  }

  @Test
  public void everyCreateThatStatesNoPlacementIsDirect() throws Exception {
    eligibleRunner();
    String repoId = repo();

    Workspace created = workspaceService.createWorkspace(repoId, "plain", "master", "plain", null);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  // --- create starts (qits-853) -------------------------------------------------------------------

  /**
   * The create door's RUNNER row: queued by the create itself, no process answered, and a following
   * ensure-container is a no-op that queues nothing a second time.
   */
  @Test
  public void creatingARunnerWorkspaceQueuesItAndAnEnsureAfterIsANoOp() throws Exception {
    eligibleRunner();
    String repoId = repo();

    WorkspaceService.CreatedWorkspace created =
        createAndStart(repoId, "made", WorkspacePlacement.RUNNER);

    Long id = created.workspace().id;
    Workspace row = read(id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.queuedAt);
    assertNull(created.startError());
    assertNull(created.technicalProcessId(), "the row's status is its progress");
    assertEquals(List.of("backlog:" + id), placement.calls());

    workspaceService.beginEnsureContainer(id);
    assertEquals(List.of("backlog:" + id), placement.calls(), "the second call queued nothing");
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(id).runtimeStatus);
    assertEquals(row.queuedAt, read(id).queuedAt);
  }

  /**
   * Creating a DIRECT workspace starts the ladder on the platform host: one {@code run}, however
   * soon an ensure-container follows — mid-start it joins the start's process, after it a no-op.
   */
  @Test
  public void creatingADirectWorkspaceStartsItOnce() throws Exception {
    String repoId = repo();
    String container = containers.containerName("made-direct", repoId);

    WorkspaceService.CreatedWorkspace created =
        createAndStart(repoId, "made-direct", WorkspacePlacement.DIRECT);

    Long id = created.workspace().id;
    assertNull(created.startError());
    assertNotNull(created.technicalProcessId());
    assertEquals(
        created.technicalProcessId(),
        workspaceService.beginEnsureContainer(id),
        "an ensure-container while the start runs joins it");

    awaitRunning(repoId, id);
    awaitTerminal(created.technicalProcessId());
    String again = workspaceService.beginEnsureContainer(id);
    awaitTerminal(again);

    assertEquals(1, containers.runCount(container), "provisioned exactly once");
    assertTrue(containers.isRunning(container));
    assertEquals(List.of(), placement.calls(), "a DIRECT row never reaches RunnerPlacement");
    containers.rm(container);
  }

  /**
   * The backlog signal is a nudge, never the record: one that cannot be sent is logged, and the
   * create and its queued row stand.
   */
  @Test
  public void aBacklogSignalThatFailsKeepsTheQueuedRow() throws Exception {
    eligibleRunner();
    String repoId = repo();
    placement.failBacklog(new IllegalStateException("the backlog is unreachable"));

    WorkspaceService.CreatedWorkspace created =
        createAndStart(repoId, "kept", WorkspacePlacement.RUNNER);

    assertNull(created.startError());
    Workspace row = read(created.workspace().id);
    assertEquals(WorkspaceStatus.ACTIVE, row.status, "the create stands");
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertTrue(workspaceService.branchExists(repoId, "kept"));
  }

  // --- start --------------------------------------------------------------------------------------

  @Test
  public void startQueuesAStoppedRowAndTellsTheBacklog() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "start");

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
    Workspace created = stoppedRunnerRow(repoId, "failed");
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

  /**
   * No edge plane (qits-799): a deployment whose {@code QITS_DOMAIN} is a local name fails the start
   * on the spot — FAILED with {@code EDGE_PLANE_UNCONFIGURED} as its runtime error, a {@code
   * container} segment saying so in the start's process — and queues nothing, so no runner is told.
   */
  @Test
  public void startWithNoEdgePlaneFailsTheRowAndQueuesNothing() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "noplane");
    QuarkusMock.installMockForType(planesAt("dev.localhost"), WorkspaceAddressPlanes.class);

    String processId = workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertEquals(
        "EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN 'dev.localhost' is not a public domain",
        row.runtimeError);
    assertNull(row.queuedAt);
    assertNull(row.runnerId);
    assertFalse(
        placement.calls().stream().anyMatch(c -> c.startsWith("backlog:")),
        placement.calls().toString());
    assertTrue(claims.trackedStart(created.id).isEmpty(), "nothing waits for a runner");

    assertNotNull(processId);
    TechnicalProcess process = processes.find(processId).orElseThrow();
    assertTrue(process.isTerminal(), "the start's process ended");
    List<TechnicalProcessFrame> frames = new ArrayList<>();
    process.attach(
        new TechnicalProcess.Listener() {
          @Override
          public void onFrame(TechnicalProcessFrame frame) {
            frames.add(frame);
          }

          @Override
          public void onDone() {}

          @Override
          public boolean isOpen() {
            return true;
          }
        });
    assertTrue(
        frames.stream()
            .anyMatch(
                f ->
                    "container".equals(f.segment())
                        && TechnicalProcessFrame.KIND_LINE.equals(f.kind())
                        && f.line().startsWith("EDGE_PLANE_UNCONFIGURED")),
        frames.toString());
    assertTrue(
        frames.stream()
            .anyMatch(
                f ->
                    "container".equals(f.segment())
                        && TechnicalProcessFrame.KIND_SEGMENT_SETTLED.equals(f.kind())
                        && TechnicalProcessFrame.STATUS_FAILED.equals(f.status())),
        frames.toString());
    assertFalse(
        frames.stream().anyMatch(f -> RunnerClaims.QUEUED_SEGMENT.equals(f.segment())),
        "never queued");
  }

  @Test
  public void startOnARunningOrProvisioningRowIsANoOp() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "live");
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
    Workspace created = stoppedRunnerRow(repoId, "gone");
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
    Workspace created = stoppedRunnerRow(repoId, "lost");
    TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), "git", "branch", "-D", "lost");

    assertThrows(NotFoundException.class, () -> workspaceService.beginEnsureContainer(created.id));

    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);
  }

  // --- stop ---------------------------------------------------------------------------------------

  @Test
  public void stoppingAQueuedRowSendsNoFrame() throws Exception {
    eligibleRunner();
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "unqueue");
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
    Workspace created = stoppedRunnerRow(repoId, "stop");
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
    Workspace created = stoppedRunnerRow(repoId, "offline");
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
    Workspace created = stoppedRunnerRow(repoId, "silent");
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
    Workspace created = stoppedRunnerRow(repoId, "reset");
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
    Workspace created = stoppedRunnerRow(repoId, "offdel");
    running(created.id, runner.id);

    ConflictException refused =
        assertThrows(ConflictException.class, () -> workspaceService.deleteContainer(created.id));
    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertEquals(runner.id, read(created.id).runnerId);
  }

  // --- the workspace token (qits-625, qits-802) ---------------------------------------------------

  /**
   * The token's lifetime is the container's: the create's start mints it (the row holds id, subject
   * and value, the mint states the row's own context and Git refs) and no client is commissioned; a
   * stop deletes nothing and the next start reuses it; delete-container deletes it and clears the
   * row; the start after that mints a fresh one.
   */
  @Test
  public void aRunnerRowsTokenIsMintedAtStartKeptByStopAndDeletedWithTheContainer()
      throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();

    Workspace created = createRunnerRow(repoId, "tok", false);

    assertEquals(1, commissioner.tokensMinted().size());
    FakeCredentialCommissioner.MintedToken minted = commissioner.tokensMinted().get(0);
    assertEquals(created.id.longValue(), minted.rowId());
    assertEquals(List.of("refs/heads/tok"), minted.gitRefs());
    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertEquals(minted.token().tokenId(), row.commissionedTokenId);
    assertEquals(minted.token().subject(), row.commissionedTokenSubject);
    assertEquals(minted.token().token(), row.commissionedToken);
    assertNull(row.commissionedClientId, "a RUNNER row is never commissioned a client");
    assertEquals(List.of(), commissioner.commissionedFor());

    workspaceService.stopContainer(created.id);
    workspaceService.beginEnsureContainer(created.id);
    assertEquals(1, commissioner.tokensMinted().size(), "the start reused the stored token");
    assertEquals(List.of(), commissioner.tokensDeleted(), "a stop deletes nothing");
    assertEquals(minted.token().tokenId(), read(created.id).commissionedTokenId);

    running(created.id, runner.id);
    workspaceService.stopContainer(created.id);
    assertEquals(List.of(), commissioner.tokensDeleted(), "a routed stop deletes nothing either");

    workspaceService.deleteContainer(created.id);
    assertEquals(List.of(minted.token().tokenId()), commissioner.tokensDeleted());
    Workspace deleted = read(created.id);
    assertNull(deleted.commissionedTokenId);
    assertNull(deleted.commissionedTokenSubject);
    assertNull(deleted.commissionedToken);

    workspaceService.beginEnsureContainer(created.id);
    assertEquals(2, commissioner.tokensMinted().size(), "a fresh token for the next container");
    assertEquals(
        commissioner.tokensMinted().get(1).token().tokenId(), read(created.id).commissionedTokenId);
  }

  /**
   * No token can be had: the row goes FAILED with {@code WORKSPACE_TOKEN_UNAVAILABLE} and the
   * reason, nothing is queued and no runner is told — at create and at a later start alike.
   */
  @Test
  public void aStartThatCannotMintFailsTheRowAndQueuesNothing() throws Exception {
    eligibleRunner();
    String repoId = repo();
    commissioner.failTokens("qits-idp is unreachable");

    Workspace created = createRunnerRow(repoId, "notok", false);

    Workspace row = read(created.id);
    assertEquals(WorkspaceStatus.ACTIVE, row.status, "the create stands");
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertEquals("WORKSPACE_TOKEN_UNAVAILABLE: qits-idp is unreachable", row.runtimeError);
    assertNull(row.queuedAt);
    assertNull(row.commissionedTokenId);
    assertFalse(
        placement.calls().stream().anyMatch(c -> c.startsWith("backlog:")),
        placement.calls().toString());

    commissioner.reset();
    commissioner.unwireTokens();
    workspaceService.beginEnsureContainer(created.id);
    assertEquals(WorkspaceRuntimeStatus.FAILED, read(created.id).runtimeStatus);
    assertTrue(
        read(created.id).runtimeError.startsWith("WORKSPACE_TOKEN_UNAVAILABLE: "),
        read(created.id).runtimeError);

    commissioner.reset();
    workspaceService.beginEnsureContainer(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(created.id).runtimeStatus, "minted now");
    assertNotNull(read(created.id).commissionedTokenId);
  }

  /**
   * RUNNER recreate: the clean-tree gate passes, the container is deleted on its runner (token
   * deleted, runner cleared), then the start mints a fresh token and queues the row for any runner.
   */
  @Test
  public void recreateDeletesTheContainerAndItsTokenThenStartsWithAFreshOne() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "recr");
    String first = read(created.id).commissionedTokenId;
    running(created.id, runner.id);
    gitStatus.report(created.id, true);

    String processId = workspaceService.beginRecreateContainer(created.id);

    assertNotNull(processId);
    assertTrue(placement.calls().contains("delete:" + created.id), placement.calls().toString());
    assertEquals(List.of(first), commissioner.tokensDeleted());
    assertEquals(2, commissioner.tokensMinted().size());
    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNull(row.runnerId, "it may land on another runner");
    assertEquals(commissioner.tokensMinted().get(1).token().tokenId(), row.commissionedTokenId);
  }

  /** A resolution deletes the row's token with the rest of the container's teardown. */
  @Test
  public void aResolutionDeletesTheToken() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "resolvetok");
    String held = read(created.id).commissionedTokenId;
    running(created.id, runner.id);

    workspaceService.discardWorkspace(created.id, null, true);

    assertEquals(List.of(held), commissioner.tokensDeleted());
    assertNull(read(created.id).commissionedTokenId);
  }

  @Test
  public void recreateIsRefusedByTheCleanTreeGateWithNoDaemon() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    placement.connect(runner.id);
    String repoId = repo();
    Workspace created = stoppedRunnerRow(repoId, "recreate");
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
    Workspace created = stoppedRunnerRow(repoId, "discard");
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
    Workspace created = stoppedRunnerRow(repoId, "offdis");
    running(created.id, runner.id);

    workspaceService.discardWorkspace(created.id, null, true);

    assertEquals(WorkspaceStatus.ABANDONED, read(created.id).status);
  }

  // --- the listing --------------------------------------------------------------------------------

  @Test
  public void theListingTakesARunnerRowsOwnStatusAndOverlaysUnavailable() throws Exception {
    WorkspaceRunner runner = eligibleRunner();
    String repoId = repo();
    Workspace onRunner = stoppedRunnerRow(repoId, "listed");
    Workspace unplaced = stoppedRunnerRow(repoId, "unplaced");
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
    Workspace created = stoppedRunnerRow(repoId, "dispatched");

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
    Workspace created = stoppedRunnerRow(repoId, "delivered");

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

  private WorkspaceService.CreatedWorkspace createAndStart(
      String repoId, String label, WorkspacePlacement placed) {
    WorkspaceService.CreatedWorkspace created =
        workspaceService.createAndStartWorkspace(
            repoId,
            label,
            "master",
            label,
            null,
            false,
            false,
            false,
            WorkspaceSubject.none(),
            null,
            placed);
    rows.add(created.workspace().id);
    return created;
  }

  private void awaitTerminal(String processId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!processes.find(processId).map(TechnicalProcess::isTerminal).orElse(true)
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(
        processes.find(processId).map(TechnicalProcess::isTerminal).orElse(true),
        "process " + processId + " ended in time");
  }

  /**
   * A RUNNER row that was created (QUEUED) and stopped since — the state a start acts on. Stopped
   * through the real verb, which unqueues a QUEUED row without a frame; the create's own backlog
   * signal is cleared from the log so a test sees only what its own verbs did.
   */
  private Workspace stoppedRunnerRow(String repoId, String label) {
    Workspace created = createRunnerRow(repoId, label, false);
    workspaceService.stopContainer(created.id);
    placement.clearCalls();
    return read(created.id);
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
