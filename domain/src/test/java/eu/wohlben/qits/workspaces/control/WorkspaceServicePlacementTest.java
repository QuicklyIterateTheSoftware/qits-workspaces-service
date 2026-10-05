package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkspaceService#recordWorkspace}'s placement decision (qits-837, epic qits-626), against
 * the real {@link WorkspaceRunnerRepository} query rather than the fake used for the RUNNER
 * verb-table in {@link WorkspaceRunnerPlacementTest}: no runner, a quarantined one, an unregistered
 * one, and one with no slots are all "no eligible runner" and default a plain create to DIRECT; one
 * eligible defaults it to RUNNER, through both {@link WorkspaceService#createWorkspace} and {@link
 * DispatchService#dispatch}; an admin create is DIRECT regardless; and a row created DIRECT before a
 * runner became eligible never moves.
 *
 * <p>No new {@code @TestProfile}: this reuses the module's default profile, exactly as {@link
 * WorkspaceRunnerPlacementTest} does.
 */
@QuarkusTest
public class WorkspaceServicePlacementTest {

  @Inject WorkspaceService workspaceService;
  @Inject DispatchService dispatchService;
  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject FakeRepositoryLookup repositories;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<UUID> createdRunners = new ArrayList<>();
  private final List<Long> rows = new ArrayList<>();

  /**
   * A public domain for every test here: a RUNNER create queues through the RUNNER start, which
   * refuses to queue without an edge plane (qits-799). See {@link WorkspaceRunnerPlacementTest}'s
   * copy of this fixture.
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
    rows.clear();
    createdRunners.clear();
  }

  @Test
  public void noRunnerAtAllDefaultsAPlainCreateToDirect() throws Exception {
    String repoId = repo();

    Workspace created = create(repoId, "none-at-all");

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void aQuarantinedRunnerIsNotEligibleAndDefaultsToDirect() throws Exception {
    WorkspaceRunner quarantined = runner(1);
    runners.markRegistered(quarantined.id, "client-" + quarantined.id, null); // quarantined, not yet
    // health-checked
    String repoId = repo();

    Workspace created = create(repoId, "quarantined");

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void anUnregisteredRunnerIsNotEligibleAndDefaultsToDirect() throws Exception {
    runner(1); // created, never registered: no client id
    String repoId = repo();

    Workspace created = create(repoId, "unregistered");

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void aRunnerWithNoSlotsIsNotEligibleAndDefaultsToDirect() throws Exception {
    eligibleRunner(0);
    String repoId = repo();

    Workspace created = create(repoId, "noslots");

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void anEligibleRunnerDefaultsAPlainCreateToRunner() throws Exception {
    eligibleRunner(1);
    String repoId = repo();

    Workspace created = create(repoId, "eligible");

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
  }

  @Test
  public void anEligibleRunnerDefaultsADispatchsCreateToRunnerToo() throws Exception {
    eligibleRunner(1);
    String repoId = repo();

    DispatchService.Dispatch answer =
        dispatchService.dispatch(repoId, "dispatched-default", false, null, WorkspaceSubject.none(), "go");
    rows.add(answer.workspace().id());

    assertEquals(WorkspacePlacement.RUNNER, read(answer.workspace().id()).placement);
  }

  @Test
  public void anAdminCreateIsDirectEvenWithAnEligibleRunner() throws Exception {
    eligibleRunner(1);
    String repoId = repo();

    Workspace created =
        workspaceService.createWorkspace(
            repoId, "admin-create", "master", "admin-create", null, false, false, true);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void aRowCreatedDirectBeforeARunnerExistedNeverMoves() throws Exception {
    String repoId = repo();
    Workspace created = create(repoId, "early");
    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);

    eligibleRunner(1);

    assertEquals(
        WorkspacePlacement.DIRECT,
        read(created.id).placement,
        "placement is fixed at create; a runner appearing later changes nothing already written");
  }

  // --- helpers --------------------------------------------------------------------------------

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  private Workspace create(String repoId, String label) {
    Workspace created = workspaceService.createWorkspace(repoId, label, "master", label, null);
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

  private WorkspaceRunner eligibleRunner(int slots) {
    WorkspaceRunner runner = runner(slots);
    runners.markRegistered(runner.id, "client-" + runner.id, null);
    return runners.greenlight(runner.id);
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }
}
