package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseState;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The platform CA's anchors reach a workspace container as {@code QITS_TRUST_ANCHORS} when it is
 * created, and a change of the bundle alone never recreates or rolls one (qits-2634, epic qits-2524).
 *
 * <p><b>DIRECT.</b> A stopped container is started by presenting its spec again with {@code
 * Recreate.ifChanged}, so the spec of an existing container must not move with the bundle. The bundle
 * a container was created with is recorded on its row and the factory reads it from there: the
 * tests compose the spec as {@code WorkspaceContainers} does at every ensure ({@link
 * WorkspaceContainerFactory#forWorkspace}) and compare it whole.
 *
 * <p><b>TARGET.</b> The lease spec is composed only for a new lease, and a held lease is never
 * re-specified: the tests read the one request each lease was asked for with and count the asks.
 */
@QuarkusTest
public class WorkspaceTrustAnchorsTest {

  private static final String BUNDLE_A =
      "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n";
  private static final String BUNDLE_B =
      BUNDLE_A + "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n";

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeTrustAnchorSource anchors;
  @Inject WorkspaceTrustAnchors containerAnchors;
  @Inject WorkspaceContainerFactory factory;
  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject FakeContainerRuntime containers;
  @Inject FakeTargetPlacement placement;
  @Inject FakeWorkspaceDaemonLiveness liveness;
  @Inject TargetLeaseStates states;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<Long> rows = new ArrayList<>();

  @BeforeEach
  void aPublicDomainAndNoIssuer() {
    commissioner.reset();
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
    rows.forEach(liveness::markDead);
    rows.clear();
    anchors.reset();
    placement.reset();
    commissioner.reset();
  }

  // --- DIRECT ---------------------------------------------------------------------------------------

  @Test
  public void aDirectContainerIsCreatedWithTheBundleHeld() throws Exception {
    anchors.hold(BUNDLE_A);
    Long rowId = directRow("anc-held");

    workspaceService.ensureContainer(rowId);

    assertEquals(BUNDLE_A, read(rowId).containerTrustAnchors, "recorded for this container");
    Map<String, String> env = directEnv(rowId);
    assertEquals(BUNDLE_A, env.get(TrustAnchorSource.ENV));
    List<String> keys = new ArrayList<>(env.keySet());
    assertEquals(TrustAnchorSource.ENV, keys.get(keys.size() - 1), "written last");
  }

  @Test
  public void aDirectContainerCreatedWithNoBundleHasNoVariable() throws Exception {
    Long rowId = directRow("anc-none");

    workspaceService.ensureContainer(rowId);

    assertNull(read(rowId).containerTrustAnchors);
    assertTrue(containerAnchors.anchorsFor(rowId).isEmpty());
    assertFalse(directEnv(rowId).containsKey(TrustAnchorSource.ENV));
  }

  /**
   * The no-roll rule on the DIRECT path: a new bundle changes nothing about a container that exists,
   * running or stopped. A running one is not ensured at all; a stopped one is started with exactly
   * the spec it was created with, so qits-containers' {@code ifChanged} finds the same hash and
   * restarts it in place rather than replacing it.
   */
  @Test
  public void aBundleChangeAloneLeavesAnExistingDirectContainersSpecAlone() throws Exception {
    anchors.hold(BUNDLE_A);
    Long rowId = directRow("anc-keep");
    workspaceService.ensureContainer(rowId);
    String container = containerOf(rowId);
    Map<String, String> created = directEnv(rowId);

    anchors.hold(BUNDLE_B);
    // Running: a start is a no-op and nothing is re-specified.
    workspaceService.ensureContainer(rowId);
    assertEquals(1, containers.runCount(container), "the running container is not run again");
    assertEquals(created, directEnv(rowId), "its spec did not move with the bundle");

    // Stopped, then started: the existing container is started again with its own spec.
    workspaceService.stopContainer(rowId);
    workspaceService.ensureContainer(rowId);
    assertTrue(containers.isRunning(container));
    assertEquals(1, containers.runCount(container), "started in place, not created again");
    assertEquals(BUNDLE_A, read(rowId).containerTrustAnchors);
    assertEquals(created, directEnv(rowId), "the start presents the spec it was created with");
  }

  /** The other half of the rule: a new bundle applies at the next creation. */
  @Test
  public void aRecreatedDirectContainerTakesTodaysBundle() throws Exception {
    anchors.hold(BUNDLE_A);
    Long rowId = directRow("anc-new");
    workspaceService.ensureContainer(rowId);
    String repoId = read(rowId).repositoryId;
    assertTrue(startedRecorder.awaitCount(repoId, "anc-new", 1, 10_000), "the first start");
    startedRecorder.clear();

    anchors.hold(BUNDLE_B);
    workspaceService.beginRecreateContainer(rowId);
    assertTrue(startedRecorder.awaitCount(repoId, "anc-new", 1, 10_000), "recreated");

    assertEquals(BUNDLE_B, read(rowId).containerTrustAnchors);
    assertEquals(BUNDLE_B, directEnv(rowId).get(TrustAnchorSource.ENV));
  }

  // --- TARGET ---------------------------------------------------------------------------------------

  @Test
  public void aLeaseIsAskedForWithTheBundleHeld() throws Exception {
    anchors.hold(BUNDLE_A);
    Workspace created = targetRow("anc-lease");

    TargetPlacement.LeaseRequest asked = placement.request(read(created.id).leaseId);
    assertEquals(BUNDLE_A, asked.spec().env().get(TrustAnchorSource.ENV));
  }

  @Test
  public void aLeaseAskedForWithNoBundleHasNoVariable() throws Exception {
    Workspace created = targetRow("anc-nolease");

    TargetPlacement.LeaseRequest asked = placement.request(read(created.id).leaseId);
    assertFalse(asked.spec().env().containsKey(TrustAnchorSource.ENV));
  }

  /**
   * The no-roll rule on the TARGET path: a held lease's spec — the one qits-system hashes — is the
   * one it was asked for with. A new bundle, a start pressed on the live row and the reconcile ask
   * for no new lease and re-specify none; the bundle reaches the workspace at its next start.
   */
  @Test
  public void aBundleChangeAloneNeverReSpecifiesAHeldLease() throws Exception {
    anchors.hold(BUNDLE_A);
    Workspace created = targetRow("anc-held-lease");
    UUID leaseId = read(created.id).leaseId;
    TargetPlacement.LeaseRequest asked = placement.request(leaseId);
    states.observe(placement.move(leaseId, LeaseState.RUNNING, UUID.randomUUID(), null, null));
    awaitStatus(created.id, WorkspaceRuntimeStatus.RUNNING);
    placement.clearCalls();

    anchors.hold(BUNDLE_B);
    workspaceService.beginEnsureContainer(created.id);
    states.reconcile(Duration.ofMinutes(2), Instant.now());

    assertEquals(leaseId, read(created.id).leaseId, "the same lease");
    assertTrue(
        placement.calls().stream().noneMatch(c -> c.startsWith("create:")),
        "no new lease: " + placement.calls());
    assertEquals(asked, placement.request(leaseId), "its spec is the one it was asked for with");
    assertEquals(BUNDLE_A, placement.request(leaseId).spec().env().get(TrustAnchorSource.ENV));
  }

  // --- helpers --------------------------------------------------------------------------------------

  /** An admin row, DIRECT for good (an ACTIVE DIRECT row is admin by constraint). */
  private Long directRow(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, label, "master", label, null, false, false, true);
    Long rowId = workspaceIds.of(repoId, label);
    rows.add(rowId);
    return rowId;
  }

  private Workspace targetRow(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    Workspace created =
        workspaceService.createWorkspace(
            repoId,
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
    return created;
  }

  /** The environment {@code WorkspaceContainers} would present for this row at an ensure now. */
  private Map<String, String> directEnv(Long rowId) {
    Workspace row = read(rowId);
    return factory
        .forWorkspace(row.repositoryId, row.workspaceId, rowId, row.branch, row.parent)
        .env();
  }

  private String containerOf(Long rowId) {
    Workspace row = read(rowId);
    return containers.containerName(row.workspaceId, row.repositoryId);
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }

  private void awaitStatus(Long rowId, WorkspaceRuntimeStatus wanted) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (read(rowId).runtimeStatus != wanted && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(wanted, read(rowId).runtimeStatus, "reached in time");
  }
}
