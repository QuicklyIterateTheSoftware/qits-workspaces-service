package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.TargetPlacement.Lease;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseRefused;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseState;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link StaleResolverRefresh} (qits-2091) against {@link FakeTargetPlacement}: a RUNNING TARGET
 * row whose lease reads its resolvers stale is refreshed once its slot is idle, and never when the
 * slot is busy or cannot be asked, when the lease is not stale, or when the row is not a RUNNING
 * TARGET one; a refusal is logged and the pass goes on.
 *
 * <p>The DIRECT leg (qits-2231) against {@link FakeContainerRuntime}: a RUNNING DIRECT row whose
 * container reads its resolvers stale is removed and run again on its volume once idle, never when
 * busy, not stale or unknown, not again within the cooldown — and with no target to be had at all.
 */
@QuarkusTest
public class StaleResolverRefreshTest {

  @Inject StaleResolverRefresh refresh;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeTargetPlacement placement;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeWorkspaceDaemonProvisioner provisioner;
  @Inject FakeAgentDaemon agentDaemon;
  @Inject TestAgents agents;
  @Inject FakeContainerRuntime containers;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<Long> rows = new ArrayList<>();

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
    refresh.clock(null);
    refresh.forgetRefreshes();
    agents.retireAll();
    agentDaemon.reset();
    rows.clear();
    containers.forgetResolvers();
    placement.reset();
    commissioner.reset();
    provisioner.reset();
  }

  @Test
  public void aStaleLeaseOnAnIdleSlotIsRefreshedOnceAndNotAgainAtItsNewGeneration()
      throws Exception {
    Workspace row = runningTargetRow("stale-idle");
    UUID leaseId = row.leaseId;
    agents.placeOn(row.repositoryId, row.id, "qits-1"); // idle: its check says not busy
    placement.resolversStale(leaseId, true);

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(row.id), pass.refreshed());
    assertEquals(List.of("refresh:" + leaseId), refreshes());
    assertEquals(1, placement.lease(leaseId).generation());

    // The runner has not reported the new container yet: still stale at the generation the
    // refresh answered, and another recreate would cure nothing.
    StaleResolverRefresh.Pass again = refresh.refreshStale();

    assertEquals(List.of(), again.refreshed());
    assertEquals(1, refreshes().size(), "refreshed once");
    WorkspaceRuntimeStatus status = read(row.id).runtimeStatus;
    assertEquals(WorkspaceRuntimeStatus.RUNNING, status, "the row is left as it was");
    assertFalse(placement.calls().contains("end:" + leaseId), "never ended");
    assertEquals(List.of(), placement.purged(), "the work volume is never purged");
  }

  @Test
  public void aLeaseStillStaleAtItsRefreshedGenerationIsRefreshedAgainOnlyAfterTheCooldown()
      throws Exception {
    Instant start = Instant.parse("2026-11-11T12:00:00Z");
    refresh.clock(Clock.fixed(start, ZoneOffset.UTC));
    Workspace row = runningTargetRow("stale-cooldown");
    UUID leaseId = row.leaseId;
    placement.resolversStale(leaseId, true);

    assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
    assertEquals(1, placement.lease(leaseId).generation());

    refresh.clock(Clock.fixed(start.plus(Duration.ofMinutes(29)), ZoneOffset.UTC));
    assertEquals(List.of(), refresh.refreshStale().refreshed(), "within the cooldown");
    assertEquals(1, refreshes().size());

    refresh.clock(Clock.fixed(start.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
    assertEquals(List.of(row.id), refresh.refreshStale().refreshed(), "the cooldown is over");
    assertEquals(2, refreshes().size());
    assertEquals(2, placement.lease(leaseId).generation());

    // A new cooldown runs from the second refresh.
    refresh.clock(Clock.fixed(start.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
    assertEquals(List.of(), refresh.refreshStale().refreshed());
    assertEquals(2, refreshes().size(), "refreshed once after the cooldown");
  }

  @Test
  public void aBusySlotIsSkippedAndRefreshedOnceItIsIdle() throws Exception {
    Workspace row = runningTargetRow("stale-busy");
    UUID agent = agents.placeOn(row.repositoryId, row.id, "qits-2");
    agentDaemon.leftover(
        agent.toString(), new AgentDaemon.CleanupCheck(true, List.of(), List.of(), true));
    placement.resolversStale(row.leaseId, true);

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(row.id), pass.busy());
    assertEquals(List.of(), refreshes(), "a busy harness is never restarted");

    agentDaemon.leftover(
        agent.toString(), new AgentDaemon.CleanupCheck(true, List.of(), List.of(), false));
    assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
  }

  @Test
  public void leftoverWorkDoesNotHoldTheRefreshBecauseTheVolumeIsKept() throws Exception {
    Workspace row = runningTargetRow("stale-leftover");
    UUID agent = agents.placeOn(row.repositoryId, row.id, "qits-3");
    agentDaemon.leftover(
        agent.toString(),
        new AgentDaemon.CleanupCheck(
            false,
            List.of("qits-ci"),
            List.of(new AgentDaemon.CleanupCheck.Unpushed("qits-ci", "ticket/qits-3", 2))));
    placement.resolversStale(row.leaseId, true);

    assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
  }

  @Test
  public void aSlotWhoseDaemonCannotBeAskedIsNotKnownIdleAndIsSkipped() throws Exception {
    Workspace row = runningTargetRow("stale-unasked");
    agents.placeOn(row.repositoryId, row.id, "qits-4");
    agentDaemon.disconnect(row.id);
    placement.resolversStale(row.leaseId, true);

    assertEquals(List.of(row.id), refresh.refreshStale().busy());
    assertEquals(List.of(), refreshes());
  }

  @Test
  public void aLeaseThatIsNotStaleIsNotRefreshed() throws Exception {
    runningTargetRow("fresh");

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(), pass.refreshed());
    assertEquals(List.of(), pass.busy());
    assertEquals(List.of(), refreshes());
  }

  @Test
  public void onlyARunningTargetRowNamingTheLeaseIsRefreshed() throws Exception {
    // A TARGET row whose container is still coming up.
    Workspace provisioning = runningTargetRow("stale-provisioning");
    update(provisioning.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING);
    placement.resolversStale(provisioning.leaseId, true);
    // A RUNNING admin row on the platform host, and a stale lease of its owner ref that it does
    // not name: a DIRECT row holds no lease (ck_workspace_lease_placement) and is never looked at.
    Workspace direct =
        workspaceService.createWorkspace(
            repo(), "direct", "master", "direct", null, false, false, true);
    rows.add(direct.id);
    update(direct.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING);
    assertEquals(WorkspacePlacement.DIRECT, read(direct.id).placement);
    UUID orphan = UUID.randomUUID();
    placement.seed(
        new Lease(
            orphan, direct.id.toString(), LeaseState.RUNNING, UUID.randomUUID(), null, null, null,
            null, Instant.now(), true, 0));

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(), pass.refreshed());
    assertEquals(List.of(), refreshes());
  }

  @Test
  public void aRefusedRefreshIsLoggedAndThePassGoesOn() throws Exception {
    Workspace first = runningTargetRow("stale-refused-1");
    Workspace second = runningTargetRow("stale-refused-2");
    placement.resolversStale(first.leaseId, true);
    placement.resolversStale(second.leaseId, true);
    placement.refuseRefreshes(
        new LeaseRefused(409, "LEASE_NOT_RUNNING", "qits-system answered 409", null));

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(2, pass.refused().size(), "each lease was asked, and neither threw");
    assertTrue(pass.refused().containsAll(List.of(first.id, second.id)));
    assertEquals(List.of(), pass.refreshed());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(first.id).runtimeStatus);
  }

  @Test
  public void anUnreadableListingIsAQuietPass() {
    placement.unreachable(new LeaseRefused(-1, null, "qits-system could not be asked", null));

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(), pass.refreshed());
  }

  // --- the DIRECT leg (qits-2231) ----------------------------------------------------------------

  @Test
  public void anIdleDirectContainerWithStaleResolversIsRunAgainOnItsVolume() throws Exception {
    Workspace row = runningDirectRow("direct-stale");
    String name = containerOf(row);
    agents.placeOn(row.repositoryId, row.id, "qits-5"); // idle: its check says not busy
    containers.clearTeardownCalls();
    containers.resolversStale(name, true);

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(row.id), pass.refreshed());
    assertEquals(List.of("stop:" + name, "rm:" + name), containers.teardownCalls());
    assertEquals(2, containers.runCount(name), "run once to start, and once again by the refresh");
    assertTrue(containers.isRunning(name));
    assertTrue(
        containers.listWorkspaceVolumes().stream()
            .anyMatch(v -> v.workspaceId().equals(row.workspaceId)),
        "the work volume is kept");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, read(row.id).runtimeStatus);
    assertEquals(List.of(), refreshes(), "no lease is refreshed for a DIRECT row");
  }

  @Test
  public void aDirectRecreateWhoseRunFailsMarksTheRowFailedAndKeepsTheVolume() throws Exception {
    Workspace row = runningDirectRow("direct-run-fails");
    String name = containerOf(row);
    agents.placeOn(row.repositoryId, row.id, "qits-7");
    containers.clearTeardownCalls();
    containers.resolversStale(name, true);
    containers.throwOnNextRun(name);

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(row.id), pass.refused());
    assertEquals(List.of("stop:" + name, "rm:" + name), containers.teardownCalls());
    assertEquals(
        WorkspaceRuntimeStatus.FAILED,
        read(row.id).runtimeStatus,
        "a row whose container is gone does not read RUNNING");
    assertTrue(
        containers.listWorkspaceVolumes().stream()
            .anyMatch(v -> v.workspaceId().equals(row.workspaceId)),
        "the work volume is kept");
  }

  @Test
  public void aBusyDirectSlotIsSkippedAndNothingIsRemoved() throws Exception {
    Workspace row = runningDirectRow("direct-busy");
    String name = containerOf(row);
    UUID agent = agents.placeOn(row.repositoryId, row.id, "qits-6");
    agentDaemon.leftover(
        agent.toString(), new AgentDaemon.CleanupCheck(true, List.of(), List.of(), true));
    containers.clearTeardownCalls();
    containers.resolversStale(name, true);

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(row.id), pass.busy());
    assertEquals(List.of(), pass.refreshed());
    assertEquals(List.of(), containers.teardownCalls(), "a busy harness is never restarted");
    assertEquals(1, containers.runCount(name));
  }

  @Test
  public void aDirectContainerThatIsNotStaleOrCannotSayIsLeftAlone() throws Exception {
    Workspace fresh = runningDirectRow("direct-fresh");
    Workspace unknown = runningDirectRow("direct-unknown");
    containers.resolversStale(containerOf(fresh), false);
    containers.resolversStale(containerOf(unknown), null); // unreachable, refused, or a 404
    containers.clearTeardownCalls();

    StaleResolverRefresh.Pass pass = refresh.refreshStale();

    assertEquals(List.of(), pass.refreshed());
    assertEquals(List.of(), pass.busy());
    assertEquals(List.of(), pass.refused());
    assertEquals(List.of(), containers.teardownCalls());
    assertEquals(1, containers.runCount(containerOf(fresh)));
    assertEquals(1, containers.runCount(containerOf(unknown)));
  }

  @Test
  public void aDirectContainerStillStaleIsRunAgainOnlyAfterTheCooldown() throws Exception {
    Instant start = Instant.parse("2026-11-14T12:00:00Z");
    refresh.clock(Clock.fixed(start, ZoneOffset.UTC));
    Workspace row = runningDirectRow("direct-cooldown");
    String name = containerOf(row);
    containers.resolversStale(name, true);

    assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
    assertEquals(2, containers.runCount(name));

    refresh.clock(Clock.fixed(start.plus(Duration.ofMinutes(29)), ZoneOffset.UTC));
    assertEquals(List.of(), refresh.refreshStale().refreshed(), "within the cooldown");
    assertEquals(2, containers.runCount(name));

    refresh.clock(Clock.fixed(start.plus(Duration.ofMinutes(30)), ZoneOffset.UTC));
    assertEquals(List.of(row.id), refresh.refreshStale().refreshed(), "the cooldown is over");
    assertEquals(3, containers.runCount(name));
  }

  @Test
  public void theDirectLegRunsWithNoTargetToBeHad() throws Exception {
    Workspace row = runningDirectRow("direct-no-target");
    String name = containerOf(row);
    containers.resolversStale(name, true);
    StaleResolverRefresh bean = ClientProxy.unwrap(refresh);
    Instance<TargetPlacement> present = bean.placement;
    bean.placement = CDI.current().select(TargetPlacement.class, NamedLiteral.of("no-target"));
    try {
      assertFalse(bean.placement.isResolvable());

      assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
      assertEquals(2, containers.runCount(name));
    } finally {
      bean.placement = present;
    }
  }

  @Test
  public void theDirectLegRunsWhenTheLeaseListingCannotBeRead() throws Exception {
    Workspace row = runningDirectRow("direct-no-listing");
    containers.resolversStale(containerOf(row), true);
    placement.unreachable(new LeaseRefused(-1, null, "qits-system could not be asked", null));

    assertEquals(List.of(row.id), refresh.refreshStale().refreshed());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** An admin row on the platform host reading RUNNING, its container run in the fake. */
  private Workspace runningDirectRow(String label) throws Exception {
    Workspace created =
        workspaceService.createWorkspace(repo(), label, "master", label, null, false, false, true);
    rows.add(created.id);
    Workspace row = read(created.id);
    assertEquals(WorkspacePlacement.DIRECT, row.placement);
    containers.run(row.repositoryId, row.workspaceId, row.id, row.branch, row.parent);
    update(created.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING);
    return read(created.id);
  }

  private String containerOf(Workspace row) {
    return containers.containerName(row.workspaceId, row.repositoryId);
  }

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  /** A TARGET row reading RUNNING, its lease RUNNING on a target in the fake. */
  private Workspace runningTargetRow(String label) throws Exception {
    Workspace created =
        workspaceService.createWorkspace(
            repo(),
            label,
            "master",
            label,
            null,
            false,
            false,
            false,
            WorkspaceSubject.none(),
            WorkspacePlacement.TARGET);
    rows.add(created.id);
    Workspace queued = read(created.id);
    placement.move(queued.leaseId, LeaseState.RUNNING, UUID.randomUUID(), null, null);
    update(created.id, w -> w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING);
    return read(created.id);
  }

  private List<String> refreshes() {
    return placement.calls().stream().filter(c -> c.startsWith("refresh:")).toList();
  }

  private void update(Long rowId, Consumer<Workspace> change) {
    QuarkusTransaction.requiringNew()
        .run(() -> change.accept(workspaceRepository.findById(rowId)));
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }
}
