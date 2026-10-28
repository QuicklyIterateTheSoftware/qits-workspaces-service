package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.TargetPlacement.Lease;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseState;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.LegacyPlacement;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.TargetRefusals;
import eu.wohlben.qits.workspaces.persistence.LegacyPlacementRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * TARGET placement in {@link WorkspaceService} and {@link TargetLeaseStates} (epic qits-1481,
 * qits-1532), against {@link FakeTargetPlacement}: the create rule and its refusals, a start that
 * mints the lease's token and asks for a WORKSPACE lease, the lease states mapped onto the row, stop,
 * delete-container, recreate and resolution, the legacy adoption, the reconcile — and that a DIRECT
 * row never reaches the port.
 */
@QuarkusTest
public class WorkspaceTargetPlacementTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject WorkspaceService workspaceService;
  @Inject TargetLeaseStates states;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject LegacyPlacementRepository legacy;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeTargetPlacement placement;
  @Inject FakeContainerRuntime containers;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeWorkspaceDaemonProvisioner provisioner;
  @Inject FakeAgentDaemon agentDaemon;
  @Inject TestAgents agents;
  @Inject FakeWorkspaceDaemonLiveness liveness;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<Long> rows = new ArrayList<>();

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
                    id -> {
                      workspaceRepository
                          .findByIdOptional(id)
                          .filter(w -> w.status == WorkspaceStatus.ACTIVE)
                          .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED);
                      legacy.deleteById(id);
                    }));
    rows.forEach(liveness::markDead);
    agents.retireAll();
    agentDaemon.reset();
    rows.clear();
    placement.reset();
    commissioner.reset();
    provisioner.reset();
  }

  // --- create ---------------------------------------------------------------------------------------

  @Test
  public void anAdminWorkspaceStatedOntoATargetIsRefusedBeforeAnyBranchIsPushed() throws Exception {
    String repoId = repo();

    BadRequestException refused =
        assertThrows(BadRequestException.class, () -> createTargetRow(repoId, "adm", true));
    assertEquals(400, refused.statusCode());
    assertFalse(workspaceService.branchExists(repoId, "adm"), "nothing was pushed");
  }

  @Test
  public void aRegularWorkspaceStatedOntoTheDirectPathIsRefused() throws Exception {
    String repoId = repo();

    DomainException refused =
        assertThrows(
            DomainException.class,
            () ->
                workspaceService.createWorkspace(
                    repoId, "plain", "master", "plain", null, false, false, false,
                    WorkspaceSubject.none(), WorkspacePlacement.DIRECT));
    assertEquals(TargetRefusals.DIRECT_PLACEMENT_REFUSED, refused.code());
    assertFalse(workspaceService.branchExists(repoId, "plain"), "nothing was pushed");
  }

  /**
   * A regular create is TARGET and asks for its lease at once: the token minted for it first (the
   * row's own context, no Git refs), then {@code createLease} with the row's id, the lease's
   * {@code detail} shown as the row's runtime error while it waits.
   */
  @Test
  public void aRegularCreateMintsTheLeasesTokenAndAsksForAWorkspaceLease() throws Exception {
    String repoId = repo();

    Workspace created = createTargetRow(repoId, "lease", false);

    Workspace row = read(created.id);
    assertEquals(WorkspacePlacement.TARGET, row.placement);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.queuedAt);
    assertNotNull(row.leaseId);
    assertNull(row.targetId, "a QUEUED lease has no target");
    assertEquals(FakeTargetPlacement.WAITING, row.runtimeError);
    assertEquals(1, commissioner.tokensMinted().size());
    FakeCredentialCommissioner.MintedToken minted = commissioner.tokensMinted().get(0);
    assertEquals(created.id.longValue(), minted.rowId());
    assertEquals(List.of(), minted.gitRefs());
    assertEquals(minted.token().tokenId(), row.commissionedTokenId);

    TargetPlacement.LeaseRequest asked = placement.request(row.leaseId);
    assertEquals(created.id.longValue(), asked.rowId());
    assertNull(asked.adopt(), "no retired runner held it");
    assertNull(asked.pinTargetId());
    assertEquals("1000", asked.spec().user());
    assertTrue(asked.spec().image().contains("/qits/workspace:"), asked.spec().image());
    assertEquals(minted.token().token(), asked.spec().env().get("QITS_TOKEN"));
    assertFalse(asked.spec().env().containsKey("CLAUDE_CONFIG_DIR"));
    assertFalse(asked.spec().env().containsKey("HOME"));
    assertFalse(
        containers.exists(containers.containerName("lease", repoId)),
        "nothing ran on the platform host");
  }

  @Test
  public void startOnALiveRowIsANoOpAndASecondPressJoinsTheFirst() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "press");

    String processId = workspaceService.beginEnsureContainer(created.id);

    assertNotNull(processId);
    assertEquals(processId, workspaceService.beginEnsureContainer(created.id));
    assertEquals(1, creates(created.id), "one lease, however often it is pressed");
    for (WorkspaceRuntimeStatus live :
        List.of(WorkspaceRuntimeStatus.RUNNING, WorkspaceRuntimeStatus.PROVISIONING)) {
      update(created.id, w -> w.runtimeStatus = live);
      workspaceService.beginEnsureContainer(created.id);
      assertEquals(1, creates(created.id), live + " asks for nothing");
    }
  }

  @Test
  public void startWithNoEdgePlaneFailsTheRowAndAsksForNothing() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "noplane");
    QuarkusMock.installMockForType(planesAt("dev.localhost"), WorkspaceAddressPlanes.class);

    workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertEquals(
        "EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN 'dev.localhost' is not a public domain",
        row.runtimeError);
    assertEquals(0, creates(created.id));
  }

  @Test
  public void aStartThatCannotMintFailsTheRowAndAsksForNothing() throws Exception {
    String repoId = repo();
    commissioner.failTokens("qits-idp is unreachable");

    Workspace created = createTargetRow(repoId, "notok", false);

    Workspace row = read(created.id);
    assertEquals(WorkspaceStatus.ACTIVE, row.status, "the create stands");
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertEquals("WORKSPACE_TOKEN_UNAVAILABLE: qits-idp is unreachable", row.runtimeError);
    assertNull(row.leaseId);
    assertEquals(0, creates(created.id));

    commissioner.reset();
    workspaceService.beginEnsureContainer(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(created.id).runtimeStatus, "minted now");
  }

  /** A refusal about the request is permanent: FAILED, saying what qits-system said. */
  @Test
  public void aLeaseQitsSystemRefusesFailsTheRowAndGivesTheTokenBack() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "refused");
    placement.refuse(
        new TargetPlacement.LeaseRefused(
            400, "IMAGE_NOT_ALLOWED", "a WORKSPACE slot runs only workspace images", null));

    workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertTrue(row.runtimeError.startsWith("TARGET_REFUSED: "), row.runtimeError);
    assertNull(row.leaseId);
    assertNull(row.commissionedTokenId, "the token minted for it is given back");
    assertEquals(
        List.of(commissioner.tokensMinted().get(commissioner.tokensMinted().size() - 1)
            .token().tokenId()),
        commissioner.tokensDeleted().subList(commissioner.tokensDeleted().size() - 1,
            commissioner.tokensDeleted().size()));
  }

  /** qits-system not answering is a moment: 409, and the row is as it was. */
  @Test
  public void aStartQitsSystemDoesNotAnswerIs409AndLeavesTheRowStopped() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "down");
    placement.refuse(new TargetPlacement.LeaseRefused(-1, null, "connection refused", null));

    ConflictException refused =
        assertThrows(
            ConflictException.class, () -> workspaceService.beginEnsureContainer(created.id));

    assertEquals(TargetRefusals.TARGET_UNAVAILABLE, refused.code());
    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.leaseId);
    assertNull(row.commissionedTokenId);
  }

  // --- the lease's states, mapped -------------------------------------------------------------------

  @Test
  public void grantedThenRunningWaitsForTheDaemonThenReadsRunning() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "up", false);
    UUID leaseId = read(created.id).leaseId;
    UUID target = UUID.randomUUID();

    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, null));

    Workspace granted = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, granted.runtimeStatus);
    assertEquals(target, granted.targetId);
    assertNull(granted.runtimeError);
    assertNull(granted.queuedAt);

    states.observe(placement.move(leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);

    WorkspaceDto listed = listed(repoId, created.id);
    assertEquals(leaseId, listed.leaseId());
    assertEquals(target, listed.targetId());
    assertEquals(WorkspacePlacement.TARGET, listed.placement());
  }

  /** GRANTED on a QUEUED row shows the lease's detail (e.g. an image pull) as the runtime error. */
  @Test
  public void grantedWithADetailOnAQueuedRowShowsItWhileProvisioning() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "pulling", false);
    UUID leaseId = read(created.id).leaseId;
    String detail = "pulling registry.x/qits/workspace:2026.1027.144757 since 2026-10-27T19:55:49Z";

    states.observe(placement.move(leaseId, LeaseState.GRANTED, UUID.randomUUID(), null, detail));

    Workspace granted = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, granted.runtimeStatus);
    assertEquals(detail, granted.runtimeError);
  }

  /** A fresh GRANTED detail on a row already PROVISIONING replaces the old one, and it is written. */
  @Test
  public void grantedWithANewDetailOnAProvisioningRowUpdatesAndPersistsIt() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "stillpulling", false);
    UUID leaseId = read(created.id).leaseId;
    UUID target = UUID.randomUUID();
    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, "pulling the image"));

    states.observe(
        placement.move(leaseId, LeaseState.GRANTED, target, null, "still pulling the image"));

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, row.runtimeStatus);
    assertEquals("still pulling the image", row.runtimeError);
  }

  /** RUNNING clears whatever pull detail GRANTED left on the row. */
  @Test
  public void runningClearsThePullDetailLeftByGranted() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "pulled", false);
    UUID leaseId = read(created.id).leaseId;
    UUID target = UUID.randomUUID();
    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, "pulling the image"));
    assertEquals("pulling the image", read(created.id).runtimeError);

    states.observe(placement.move(leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);

    assertNull(read(created.id).runtimeError);
  }

  /** A GRANTED detail never overwrites an agent-kill error standing on the row. */
  @Test
  public void grantedWithADetailDoesNotOverwriteAnAgentKillError() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "killed", false);
    UUID leaseId = read(created.id).leaseId;
    UUID target = UUID.randomUUID();
    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, "pulling the image"));
    String killError = AgentKills.describe(false, "cmd-1", null);
    update(created.id, w -> w.runtimeError = killError);

    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, "pulling again"));

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, row.runtimeStatus);
    assertEquals(killError, row.runtimeError);
  }

  @Test
  public void aDaemonThatNeverDialsHomeFailsTheRow() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "nodaemon", false);
    UUID leaseId = read(created.id).leaseId;
    provisioner.answerTargetRows(id -> Optional.empty());

    states.observe(placement.move(leaseId, LeaseState.RUNNING, UUID.randomUUID(), null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.FAILED);

    assertTrue(read(created.id).runtimeError.startsWith("no workspace-daemon dialed home"));
  }

  /**
   * qits-1600: a container restarted over an existing {@code /workspace} reports {@code Provisioned}
   * before this service has observed its lease RUNNING. The start registered the slot when it asked
   * for the lease, so the report is kept, and the wait RUNNING starts takes it at once — the fake
   * drops a report that finds no slot and fails an await after two seconds, so RUNNING here is the
   * early report kept, not a timeout waited out.
   */
  @Test
  public void aProvisionedThatBeatsTheObservedRunningIsKeptAndTheRowReadsRunning()
      throws Exception {
    provisioner.reportTargetRowsThroughSlots();
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "early", false);
    UUID leaseId = read(created.id).leaseId;
    UUID target = UUID.randomUUID();
    states.observe(placement.move(leaseId, LeaseState.GRANTED, target, null, null));
    assertTrue(provisioner.hasSlot(created.id), "the start registered the slot at its lease");

    assertTrue(
        provisioner.reportTarget(created.id, ProvisionResult.ok("cafebabe")),
        "the early report is kept on the slot");
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, read(created.id).runtimeStatus);

    states.observe(placement.move(leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);

    assertNull(read(created.id).runtimeError);
    awaitNoSlot(created.id);
  }

  /** The ordinary order still holds: RUNNING observed first, the report after it. */
  @Test
  public void aProvisionedAfterTheObservedRunningReadsRunning() throws Exception {
    provisioner.reportTargetRowsThroughSlots();
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "late", false);
    UUID leaseId = read(created.id).leaseId;

    states.observe(placement.move(leaseId, LeaseState.RUNNING, UUID.randomUUID(), null, null));
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, read(created.id).runtimeStatus);
    assertTrue(provisioner.reportTarget(created.id, ProvisionResult.ok("cafebabe")));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    awaitNoSlot(created.id);
  }

  /**
   * A start that ends before its lease runs leaves no slot behind (qits-1600): not after an ENDED
   * lease, not after a stop, not after a refused lease — and the next start of the row never reads
   * an outcome a previous start's daemon left on its slot.
   */
  @Test
  public void anAbandonedStartLeavesNoSlotAndTheNextStartInheritsNoOutcome() throws Exception {
    provisioner.reportTargetRowsThroughSlots();
    String repoId = repo();

    Workspace ended = createTargetRow(repoId, "ended", false);
    states.observe(placement.move(read(ended.id).leaseId, LeaseState.ENDED, null, null, null));
    assertFalse(provisioner.hasSlot(ended.id), "an ENDED lease forgets the slot");

    Workspace exited = createTargetRow(repoId, "exited", false);
    states.observe(
        placement.move(read(exited.id).leaseId, LeaseState.EXITED, UUID.randomUUID(), 1, null));
    assertFalse(provisioner.hasSlot(exited.id), "an EXITED lease forgets the slot");

    Workspace stopped = createTargetRow(repoId, "stopped", false);
    assertTrue(
        provisioner.reportTarget(stopped.id, ProvisionResult.failed("a stale outcome")),
        "the first start's daemon reported");
    workspaceService.stopContainer(stopped.id);
    assertFalse(provisioner.hasSlot(stopped.id), "a stop forgets the slot");

    workspaceService.beginEnsureContainer(stopped.id);
    UUID second = read(stopped.id).leaseId;
    assertNotNull(second);
    states.observe(placement.move(second, LeaseState.RUNNING, UUID.randomUUID(), null, null));
    assertTrue(provisioner.reportTarget(stopped.id, ProvisionResult.ok("cafebabe")));
    awaitStatus(stopped.id, WorkspaceRuntimeStatus.RUNNING);

    // Last: the fake refuses every create from here on.
    Workspace refused = stoppedTargetRow(repoId, "refusedslot");
    placement.refuse(new TargetPlacement.LeaseRefused(-1, null, "connection refused", null));
    assertThrows(ConflictException.class, () -> workspaceService.beginEnsureContainer(refused.id));
    assertFalse(provisioner.hasSlot(refused.id), "a lease never had forgets the slot");
  }

  /** A RUNNING lease this process never asked for, on a PROVISIONING row, is a restart: RUNNING. */
  @Test
  public void aRunningLeaseAfterARestartReadsRunningWithoutAWait() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "restart");
    UUID leaseId = UUID.randomUUID();
    update(
        created.id,
        w -> {
          w.leaseId = leaseId;
          w.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING;
        });

    states.observe(
        new Lease(
            leaseId, created.id.toString(), LeaseState.RUNNING, UUID.randomUUID(), null, null,
            Instant.now()));

    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(created.id).runtimeStatus);
  }

  @Test
  public void aFailedLeaseFailsTheRowDeletesItsTokenAndEndsTheLease() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "fails", false);
    Workspace queued = read(created.id);

    states.observe(
        placement.move(queued.leaseId, LeaseState.FAILED, UUID.randomUUID(), null, "pull failed"));

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.FAILED, row.runtimeStatus);
    assertEquals("pull failed", row.runtimeError);
    assertNull(row.commissionedTokenId);
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
    assertTrue(placement.calls().contains("end:" + queued.leaseId), placement.calls().toString());
    assertEquals(queued.leaseId, row.leaseId, "named until the lease is ENDED");

    states.observe(placement.move(queued.leaseId, LeaseState.ENDED, null, null, null));
    assertNull(read(created.id).leaseId);
    assertEquals(WorkspaceRuntimeStatus.FAILED, read(created.id).runtimeStatus);
  }

  @Test
  public void anExitedLeaseStopsTheRowAndTheNextStartAsksForAFreshLease() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "exits", false);
    Workspace queued = read(created.id);
    UUID target = UUID.randomUUID();
    states.observe(placement.move(queued.leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);

    states.observe(placement.move(queued.leaseId, LeaseState.EXITED, target, 137, null));

    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(created.id).runtimeStatus);
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
    // The end the exit asked for left it ENDING; qits-system reports it ENDED later.
    placement.move(queued.leaseId, LeaseState.ENDED, target, 137, null);

    workspaceService.beginEnsureContainer(created.id);

    Workspace restarted = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, restarted.runtimeStatus);
    assertNotNull(restarted.leaseId);
    assertFalse(queued.leaseId.equals(restarted.leaseId), "a new lease");
    assertEquals(2, commissioner.tokensMinted().size(), "one token per lease");
  }

  // --- stop, delete, recreate, resolution ----------------------------------------------------------

  @Test
  public void stoppingAQueuedLeaseEndsItAtOnceAndGivesTheTokenBack() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "stopq", false);
    Workspace queued = read(created.id);

    workspaceService.stopContainer(created.id);

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.leaseId);
    assertNull(row.commissionedTokenId);
    assertEquals(LeaseState.ENDED, placement.lease(queued.leaseId).state());
    // The commissioner is the suite's: the scheduler may hand back other tests' tokens meanwhile.
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
  }

  @Test
  public void stoppingARunningLeaseLeavesItEndingUntilTheTargetReportsItGone() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "stopr", false);
    Workspace queued = read(created.id);
    UUID target = UUID.randomUUID();
    states.observe(placement.move(queued.leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);

    workspaceService.stopContainer(created.id);

    Workspace stopped = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, stopped.runtimeStatus);
    assertEquals(queued.leaseId, stopped.leaseId);
    assertEquals(LeaseState.ENDING, placement.lease(queued.leaseId).state());
    assertFalse(
        commissioner.tokensDeleted().contains(queued.commissionedTokenId),
        "the container still runs");

    states.observe(placement.move(queued.leaseId, LeaseState.ENDED, target, null, null));

    Workspace ended = read(created.id);
    assertNull(ended.leaseId);
    assertNull(ended.targetId);
    assertNull(ended.commissionedTokenId);
    // The commissioner is the suite's: the scheduler may hand back other tests' tokens meanwhile.
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
  }

  @Test
  public void deletingTheContainerEndsTheLeaseAndPurgesTheWorkVolume() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "del", false);
    Workspace queued = read(created.id);
    placement.endsAtOnce();

    workspaceService.deleteContainer(created.id);

    assertEquals(List.of("ws-" + created.id), placement.purged());
    Workspace row = read(created.id);
    assertEquals(WorkspaceStatus.ACTIVE, row.status);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.leaseId);
    assertNull(row.adoptedVolume);
    assertNull(row.commissionedTokenId);
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
  }

  @Test
  public void recreateRefusesAnAgentWithLeftoverWorkBeforeTheLeaseIsTouched() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "recr-held", false);
    Workspace queued = read(created.id);
    states.observe(placement.move(queued.leaseId, LeaseState.RUNNING, UUID.randomUUID(), null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    UUID agent = agents.placeOn(repoId, created.id, "qits-77");
    agentDaemon.leftover(
        agent.toString(),
        new AgentDaemon.CleanupCheck(
            false,
            List.of(),
            List.of(new AgentDaemon.CleanupCheck.Unpushed("qits-ci", "ticket/qits-77", 1))));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> workspaceService.beginRecreateContainer(created.id));

    assertEquals("AGENT_HAS_LEFTOVER_WORK", ex.code());
    assertFalse(placement.calls().contains("end:" + queued.leaseId));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(created.id).runtimeStatus);
  }

  @Test
  public void recreateDeletesTheContainerThenAsksForAFreshLease() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "recr", false);
    Workspace queued = read(created.id);
    placement.endsAtOnce();

    String processId = workspaceService.beginRecreateContainer(created.id);

    assertNotNull(processId);
    assertEquals(List.of("ws-" + created.id), placement.purged());
    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.leaseId);
    assertFalse(queued.leaseId.equals(row.leaseId));
    assertEquals(2, commissioner.tokensMinted().size());
  }

  @Test
  public void aResolutionEndsTheLeaseAndPurgesTheVolumeOnceItIsEnded() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "discard", false);
    Workspace queued = read(created.id);
    UUID target = UUID.randomUUID();
    states.observe(placement.move(queued.leaseId, LeaseState.RUNNING, target, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    containers.clearTeardownCalls();

    workspaceService.discardWorkspace(created.id, null, true);

    Workspace resolved = read(created.id);
    assertEquals(WorkspaceStatus.ABANDONED, resolved.status);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, resolved.runtimeStatus);
    assertTrue(placement.calls().contains("end:" + queued.leaseId), placement.calls().toString());
    assertEquals(queued.leaseId, resolved.leaseId, "named until it is ENDED");
    assertEquals(List.of(), placement.purged(), "not while the lease is ending");
    assertEquals(List.of(), containers.teardownCalls(), "qits-containers was not asked");

    states.observe(placement.move(queued.leaseId, LeaseState.ENDED, target, null, null));

    assertEquals(List.of("ws-" + created.id), placement.purged());
    assertNull(read(created.id).leaseId);
  }

  // --- legacy adoption ------------------------------------------------------------------------------

  /**
   * A row a retired runner held: its first lease adopts the old container and volume, pinned to the
   * target on the same node (matched here by the runner's name: the runner never reported an engine
   * id or a host name), and the entry goes once that lease is RUNNING. Every later lease of the row
   * still names the adopted volume.
   */
  @Test
  public void aLegacyRowsLeasesAdoptTheOldVolumePinnedToItsNode() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "legacy");
    UUID oldRunner = UUID.fromString("8d45fd1d-0000-4000-8000-000000000001");
    UUID node = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              LegacyPlacement entry = new LegacyPlacement();
              entry.rowId = created.id;
              entry.oldRunnerId = oldRunner;
              entry.runnerName = "ws-proof";
              legacy.persist(entry);
            });
    placement.targets(
        List.of(
            new TargetPlacement.Target(UUID.randomUUID(), "localhost", null, null),
            new TargetPlacement.Target(node, "ws-proof", null, null)));
    String adopted = "qits-workspaces-runner-ws-" + created.id + "-8d45fd1d";

    workspaceService.beginEnsureContainer(created.id);

    Workspace row = read(created.id);
    TargetPlacement.LeaseRequest first = placement.request(row.leaseId);
    assertEquals(new TargetPlacement.Adopt(adopted, adopted), first.adopt());
    assertEquals(node, first.pinTargetId());
    assertEquals(adopted, row.adoptedVolume);

    states.observe(placement.move(row.leaseId, LeaseState.RUNNING, node, null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> legacy.findByIdOptional(created.id)).isEmpty(),
        "the entry has done its work");

    workspaceService.stopContainer(created.id);
    states.observe(placement.move(row.leaseId, LeaseState.ENDED, node, null, null));
    workspaceService.beginEnsureContainer(created.id);

    TargetPlacement.LeaseRequest next = placement.request(read(created.id).leaseId);
    assertEquals(new TargetPlacement.Adopt(adopted, adopted), next.adopt(), "for its whole life");
    assertNull(next.pinTargetId(), "qits-system pins it to its work volume's target itself");
  }

  /**
   * No target on the old runner's node yet: the row waits QUEUED, saying for which target, and no
   * lease is asked for and no token minted — an unpinned lease would start it fresh elsewhere and
   * strand its volume. Once the target is installed, the next retry asks for the adopting, pinned
   * lease.
   */
  @Test
  public void aLegacyRowWaitsForItsTargetAndIsAdoptedOnceItIsInstalled() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "waits");
    UUID oldRunner = UUID.fromString("8d45fd1d-0000-4000-8000-000000000002");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              LegacyPlacement entry = new LegacyPlacement();
              entry.rowId = created.id;
              entry.oldRunnerId = oldRunner;
              entry.runnerName = "ws-proof";
              legacy.persist(entry);
            });
    placement.targets(
        List.of(new TargetPlacement.Target(UUID.randomUUID(), "localhost", null, null)));
    int mintedBefore = commissioner.tokensMinted().size();

    workspaceService.beginEnsureContainer(created.id);

    Workspace waiting = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, waiting.runtimeStatus);
    assertEquals(
        "waiting for target ws-proof to be installed (it holds this workspace's volume)",
        waiting.runtimeError);
    assertNull(waiting.leaseId);
    assertNull(waiting.commissionedTokenId);
    assertEquals(0, creates(created.id), "no lease is asked for");
    assertEquals(mintedBefore, commissioner.tokensMinted().size(), "and no token minted");

    workspaceService.startWaitingLegacyRows();
    assertEquals(0, creates(created.id), "a retry with still no target waits on");

    UUID node = UUID.randomUUID();
    placement.targets(
        List.of(
            new TargetPlacement.Target(UUID.randomUUID(), "localhost", null, null),
            new TargetPlacement.Target(node, "ws-proof", null, null)));

    assertTrue(workspaceService.startWaitingLegacyRows().contains(created.id));

    Workspace adopted = read(created.id);
    assertNotNull(adopted.leaseId);
    TargetPlacement.LeaseRequest asked = placement.request(adopted.leaseId);
    String name = "qits-workspaces-runner-ws-" + created.id + "-8d45fd1d";
    assertEquals(new TargetPlacement.Adopt(name, name), asked.adopt());
    assertEquals(node, asked.pinTargetId());
    assertEquals(FakeTargetPlacement.WAITING, adopted.runtimeError, "now the lease's own detail");
  }

  /** The engine id wins over the host name, and the host name over the name. */
  @Test
  public void aLegacyRowIsPinnedByEngineIdThenHostNameThenName() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "byengine");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              LegacyPlacement entry = new LegacyPlacement();
              entry.rowId = created.id;
              entry.oldRunnerId = UUID.randomUUID();
              entry.runnerName = "lab";
              entry.hostname = "lab-host";
              entry.engineId = "ENGINE-LAB";
              legacy.persist(entry);
            });
    UUID byEngine = UUID.randomUUID();
    placement.targets(
        List.of(
            new TargetPlacement.Target(UUID.randomUUID(), "lab", null, null),
            new TargetPlacement.Target(UUID.randomUUID(), "other", null, "lab-host"),
            new TargetPlacement.Target(byEngine, "third", "ENGINE-LAB", null)));

    workspaceService.beginEnsureContainer(created.id);

    assertEquals(byEngine, placement.request(read(created.id).leaseId).pinTargetId());
  }

  /**
   * An entry that names nothing of its runner (its runner row was already gone when V30 copied
   * it): no node is known to hold the volume, so the lease is asked for unpinned and adopts
   * nothing, and the entry goes.
   */
  @Test
  public void aLegacyRowWhoseRunnerRecordedNothingStartsFreshAndUnpinned() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "gone");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              LegacyPlacement entry = new LegacyPlacement();
              entry.rowId = created.id;
              entry.oldRunnerId = UUID.randomUUID();
              legacy.persist(entry);
            });

    workspaceService.beginEnsureContainer(created.id);

    TargetPlacement.LeaseRequest asked = placement.request(read(created.id).leaseId);
    assertNull(asked.adopt());
    assertNull(asked.pinTargetId());
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> legacy.findByIdOptional(created.id)).isEmpty());
  }

  // --- the reconcile ----------------------------------------------------------------------------------

  @Test
  public void theReconcileEndsARowWhoseLeaseIsGoneAndAnOrphanedLease() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "recon", false);
    Workspace queued = read(created.id);
    placement.forget(queued.leaseId);
    Lease orphan =
        new Lease(
            UUID.randomUUID(), "999999", LeaseState.QUEUED, null, null, null,
            Instant.now().minus(Duration.ofHours(1)));
    placement.seed(orphan);

    List<Long> changed = states.reconcile(Duration.ofMinutes(2), Instant.now());

    assertTrue(changed.contains(created.id), changed.toString());
    Workspace row = read(created.id);
    assertNull(row.leaseId, "a lease qits-system no longer lists is ENDED");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertTrue(commissioner.tokensDeleted().contains(queued.commissionedTokenId));
    assertTrue(placement.calls().contains("end:" + orphan.id()), placement.calls().toString());
  }

  @Test
  public void theReconcileShowsAQueuedLeasesNewDetail() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "detail", false);
    UUID leaseId = read(created.id).leaseId;

    placement.move(leaseId, LeaseState.QUEUED, null, null, "every target is full");

    states.reconcile(Duration.ofMinutes(2), Instant.now());

    assertEquals("every target is full", listed(repoId, created.id).runtimeError());
  }

  @Test
  public void aStartRecordedWhileTheListingIsInFlightSurvivesTheReconcile() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "midlist");
    // The listing's answer was read before the start: it does not hold the new lease.
    placement.onList(() -> workspaceService.beginEnsureContainer(created.id), false);

    List<Long> changed = states.reconcile(Duration.ZERO, Instant.now());

    Workspace row = read(created.id);
    assertFalse(changed.contains(created.id), changed.toString());
    assertNotNull(row.leaseId, "the start's lease is still recorded");
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.commissionedTokenId, "the start's token is kept");
    assertFalse(placement.calls().contains("end:" + row.leaseId), placement.calls().toString());
    assertEquals(LeaseState.QUEUED, placement.lease(row.leaseId).state());
  }

  @Test
  public void aLeaseListedBeforeItsRowRecordsItIsNoOrphan() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "midorphan");
    // The listing holds the new lease, but the rows were read before the start recorded it.
    placement.onList(() -> workspaceService.beginEnsureContainer(created.id), true);

    states.reconcile(Duration.ZERO, Instant.now().plusSeconds(60));

    Workspace row = read(created.id);
    assertNotNull(row.leaseId);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertFalse(placement.calls().contains("end:" + row.leaseId), placement.calls().toString());
  }

  @Test
  public void aRowWhoseLeaseIsUnlistedButStillHeldIsNotEnded() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "unlisted", false);
    UUID leaseId = read(created.id).leaseId;
    // The listing misses the lease (it raced past it); a direct read still finds it.
    Lease held = placement.lease(leaseId);
    placement.forget(leaseId);
    placement.onList(() -> placement.seed(held), false);

    List<Long> changed = states.reconcile(Duration.ofMinutes(2), Instant.now());

    assertFalse(changed.contains(created.id), changed.toString());
    assertEquals(leaseId, read(created.id).leaseId);
    assertTrue(placement.calls().contains("get:" + leaseId), placement.calls().toString());
  }

  // --- a live row with no lease (qits-1592) -----------------------------------------------------------

  private static final Duration GRACE = Duration.ofMinutes(5);

  /**
   * What {@code V30} left of a row a retired runner held RUNNING: still RUNNING (or PROVISIONING),
   * naming no lease, its container purged since. Past the grace with no daemon it reads STOPPED,
   * saying its container is gone; inside the grace it is left as it is.
   */
  @Test
  public void aLeaselessLiveRowWithNoDaemonStopsOnceItsGraceIsPast() throws Exception {
    String repoId = repo();
    Long running = leaselessRow(repoId, "gone-run", WorkspaceRuntimeStatus.RUNNING);
    Long provisioning = leaselessRow(repoId, "gone-prov", WorkspaceRuntimeStatus.PROVISIONING);
    Instant first = Instant.now();

    assertFalse(states.stopLeaseless(GRACE, first).contains(running), "the first sighting");
    List<Long> inside = states.stopLeaseless(GRACE, first.plus(GRACE).minusSeconds(1));
    assertFalse(inside.contains(running), inside.toString());
    assertFalse(inside.contains(provisioning), inside.toString());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(running).runtimeStatus);
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, read(provisioning).runtimeStatus);

    List<Long> past = states.stopLeaseless(GRACE, first.plus(GRACE));

    assertTrue(past.containsAll(List.of(running, provisioning)), past.toString());
    for (Long rowId : List.of(running, provisioning)) {
      Workspace row = read(rowId);
      assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
      assertTrue(row.runtimeError.startsWith("the container is gone"), row.runtimeError);
      assertEquals(WorkspaceRuntimeStatus.STOPPED, listed(repoId, rowId).runtimeStatus());
    }
  }

  /** A connected daemon says the container runs somewhere: the row stays, and its grace restarts. */
  @Test
  public void aLeaselessRunningRowWithAConnectedDaemonIsLeftAlone() throws Exception {
    String repoId = repo();
    Long rowId = leaselessRow(repoId, "still-up", WorkspaceRuntimeStatus.RUNNING);
    liveness.markLive(rowId);
    Instant first = Instant.now();

    states.stopLeaseless(GRACE, first);
    assertFalse(states.stopLeaseless(GRACE, first.plus(GRACE.multipliedBy(2))).contains(rowId));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(rowId).runtimeStatus);

    liveness.markDead(rowId);
    Instant gone = first.plus(GRACE.multipliedBy(3));
    assertFalse(states.stopLeaseless(GRACE, gone).contains(rowId), "the grace starts over");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(rowId).runtimeStatus);
    assertTrue(states.stopLeaseless(GRACE, gone.plus(GRACE)).contains(rowId));
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(rowId).runtimeStatus);
  }

  /** A row that holds a lease is the lease reconcile's, never this sweep's. */
  @Test
  public void aRunningRowHoldingALeaseIsUntouchedByTheLeaselessSweep() throws Exception {
    String repoId = repo();
    Workspace created = createTargetRow(repoId, "leased", false);
    update(created.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING);
    UUID leaseId = read(created.id).leaseId;
    assertNotNull(leaseId);
    Instant first = Instant.now();

    states.stopLeaseless(GRACE, first);
    assertFalse(states.stopLeaseless(GRACE, first.plus(GRACE.multipliedBy(2))).contains(created.id));

    Workspace row = read(created.id);
    assertEquals(WorkspaceRuntimeStatus.RUNNING, row.runtimeStatus);
    assertEquals(leaseId, row.leaseId);
  }

  /** A lease-less RUNNING row is not started: a start asks for its lease, as from STOPPED. */
  @Test
  public void startOnALeaselessRunningRowAsksForALease() throws Exception {
    String repoId = repo();
    Long rowId = leaselessRow(repoId, "restart", WorkspaceRuntimeStatus.RUNNING);
    placement.clearCalls();

    workspaceService.beginEnsureContainer(rowId);

    assertEquals(1, creates(rowId), placement.calls().toString());
    Workspace row = read(rowId);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNotNull(row.leaseId);
    assertEquals(rowId.longValue(), placement.request(row.leaseId).rowId());
  }

  // --- the seam -----------------------------------------------------------------------------------

  @Test
  public void aDirectRowNeverReachesThePortAndKeepsItsLadder() throws Exception {
    String repoId = repo();
    Workspace created =
        workspaceService.createWorkspace(
            repoId, "direct", "master", "direct", null, false, false, true);
    rows.add(created.id);
    placement.clearCalls();
    String container = containers.containerName("direct", repoId);

    workspaceService.beginEnsureContainer(created.id);
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    assertTrue(containers.isRunning(container), "start provisioned on the platform host");
    workspaceService.stopContainer(created.id);
    assertFalse(containers.isRunning(container));
    workspaceService.deleteContainer(created.id);
    assertFalse(containers.exists(container));
    workspaceService.discardWorkspace(created.id, null, true);

    assertEquals(List.of(), placement.calls(), "a DIRECT row never reaches TargetPlacement");
  }

  @Test
  public void aTargetRowNeverReachesTheDirectLadderThroughTheManualRun() throws Exception {
    String repoId = repo();
    Workspace created = stoppedTargetRow(repoId, "routed");
    String container = containers.containerName("routed", repoId);

    assertThrows(BadRequestException.class, () -> workspaceService.ensureContainer(created.id));
    assertFalse(containers.exists(container));
  }

  @Test
  public void aRepositoryOfTargetRowsCostsNoContainerListing() throws Exception {
    String repoId = repo();
    createTargetRow(repoId, "one", false);
    createTargetRow(repoId, "two", false);

    assertEquals(2, workspaceService.listWorkspaces(repoId).size());
    assertEquals(0, containers.listingsOf(repoId), "no qits-containers listing for TARGET rows");
  }

  @Test
  public void discardingAWorkspaceOnTheDefaultBranchKeepsIt() throws Exception {
    String repoId = repo();
    Workspace onMaster =
        workspaceService.createWorkspace(repoId, "on-master", "master", "master", null, true);
    rows.add(onMaster.id);
    Workspace ordinary = createTargetRow(repoId, "ordinary", false);

    workspaceService.discardWorkspace(onMaster.id, null, true);
    workspaceService.discardWorkspace(ordinary.id, null, true);

    assertTrue(workspaceService.branchExists(repoId, "master"), "the default branch stays");
    assertFalse(workspaceService.branchExists(repoId, "ordinary"), "an own branch still goes");
  }

  // --- helpers ------------------------------------------------------------------------------------

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  private Workspace createTargetRow(String repoId, String label, boolean admin) {
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
            WorkspacePlacement.TARGET);
    rows.add(created.id);
    return created;
  }

  /** A TARGET row that was created and stopped since, with its call log cleared. */
  private Workspace stoppedTargetRow(String repoId, String label) {
    Workspace created = createTargetRow(repoId, label, false);
    workspaceService.stopContainer(created.id);
    placement.clearCalls();
    return read(created.id);
  }

  /** A TARGET row reading {@code status} with no lease and no target: {@code V30}'s leftover. */
  private Long leaselessRow(String repoId, String label, WorkspaceRuntimeStatus status) {
    Workspace created = createTargetRow(repoId, label, false);
    update(
        created.id,
        w -> {
          w.leaseId = null;
          w.targetId = null;
          w.runtimeStatus = status;
          w.runtimeError = null;
          w.queuedAt = null;
        });
    return created.id;
  }

  private long creates(Long rowId) {
    return placement.calls().stream().filter(c -> c.equals("create:" + rowId)).count();
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

  /** The await removes its slot as it returns, a moment after its write. */
  private void awaitNoSlot(Long rowId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (provisioner.hasSlot(rowId) && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertFalse(provisioner.hasSlot(rowId), "the await removed its slot");
  }

  private void awaitStatus(Long rowId, WorkspaceRuntimeStatus wanted) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (read(rowId).runtimeStatus != wanted && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(wanted, read(rowId).runtimeStatus, "reached in time");
  }
}
