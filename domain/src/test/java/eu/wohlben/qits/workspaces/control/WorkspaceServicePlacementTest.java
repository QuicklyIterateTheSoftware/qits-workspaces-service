package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
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
 * {@link WorkspaceService#recordWorkspace}'s placement decision (qits-837, qits-774), against the
 * real {@link WorkspaceRunnerRepository} rather than the fake used for the RUNNER verb-table in
 * {@link WorkspaceRunnerPlacementTest}: a regular create is RUNNER whatever the estate holds — no
 * runner, a quarantined one, an unregistered one, one with no slots, or an eligible one — through
 * both {@link WorkspaceService#createWorkspace} and {@link DispatchService#dispatch}; with none
 * eligible the row waits QUEUED and says "no enabled workspace runner"; a stated DIRECT on a regular
 * create is refused; admin and the editor are DIRECT; and a legacy DIRECT row never moves.
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
  @Inject TechnicalProcessRegistry processes;

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

  /**
   * The rule's zero-runner side (qits-774), through the create door: with no runner at all a create
   * is still RUNNER on no runner and the create queues it, and a following ensure-container answers
   * QUEUED with the reason — no refusal, and no fall back to DIRECT.
   */
  @Test
  public void noRunnerAtAllPlacesACreateOnARunnerAndEnsureAnswersQueuedWithTheReason()
      throws Exception {
    String repoId = repo();

    WorkspaceService.CreatedWorkspace answer =
        workspaceService.createAndStartWorkspace(
            repoId,
            "none-at-all",
            "master",
            "none-at-all",
            null,
            false,
            false,
            false,
            WorkspaceSubject.none(),
            null,
            null);
    Long id = answer.workspace().id;
    rows.add(id);

    Workspace row = read(id);
    assertEquals(WorkspacePlacement.RUNNER, row.placement);
    assertNull(row.runnerId);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertNull(answer.startError(), "waiting for a runner is not a refused start");

    String processId = workspaceService.beginEnsureContainer(id);

    assertEquals(WorkspaceRuntimeStatus.QUEUED, workspaceService.getWorkspace(id).runtimeStatus());
    assertNotNull(processId, "the ensure answers a process saying why the row waits");
    assertEquals(List.of("no enabled workspace runner"), lines(processId));
  }

  /**
   * The same through a create that leaves its row STOPPED (the domain's plain create, which capture
   * uses): RUNNER on no runner, and the first ensure-container is what queues it — saying why it
   * waits.
   */
  @Test
  public void noRunnerAtAllAndAStoppedRunnerRowTheEnsureQueuesItWithTheReason() throws Exception {
    String repoId = repo();

    Workspace created = create(repoId, "none-stopped");

    Workspace row = read(created.id);
    assertEquals(WorkspacePlacement.RUNNER, row.placement);
    assertNull(row.runnerId);

    String processId = workspaceService.beginEnsureContainer(created.id);

    assertEquals(
        WorkspaceRuntimeStatus.QUEUED, workspaceService.getWorkspace(created.id).runtimeStatus());
    assertNotNull(processId);
    assertTrue(
        lines(processId).contains(WorkspaceService.NO_ENABLED_RUNNER), lines(processId).toString());
  }

  @Test
  public void aQuarantinedRunnerIsNotEligibleAndTheCreateIsStillRunner() throws Exception {
    WorkspaceRunner quarantined = runner(1);
    runners.markRegistered(quarantined.id, "client-" + quarantined.id, null); // quarantined, not yet
    // health-checked
    String repoId = repo();

    Workspace created = create(repoId, "quarantined");

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
  }

  @Test
  public void anUnregisteredRunnerIsNotEligibleAndTheCreateIsStillRunner() throws Exception {
    runner(1); // created, never registered: no client id
    String repoId = repo();

    Workspace created = create(repoId, "unregistered");

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
  }

  @Test
  public void aRunnerWithNoSlotsIsNotEligibleAndTheCreateIsStillRunner() throws Exception {
    eligibleRunner(0);
    String repoId = repo();

    Workspace created = create(repoId, "noslots");

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
  }

  @Test
  public void anEligibleRunnerPlacesAPlainCreateOnARunner() throws Exception {
    eligibleRunner(1);
    String repoId = repo();

    Workspace created = create(repoId, "eligible");

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
    String processId = workspaceService.beginEnsureContainer(created.id);
    assertNotNull(processId);
    assertFalse(
        lines(processId).contains(WorkspaceService.NO_ENABLED_RUNNER),
        "a runner could take it, so the row does not say none could");
  }

  @Test
  public void anEligibleRunnerPlacesADispatchsCreateOnARunnerToo() throws Exception {
    eligibleRunner(1);
    String repoId = repo();

    DispatchService.Dispatch answer =
        dispatchService.dispatch(repoId, "dispatched-default", false, null, WorkspaceSubject.none(), "go");
    rows.add(answer.workspace().id());

    assertEquals(WorkspacePlacement.RUNNER, read(answer.workspace().id()).placement);
  }

  /**
   * A dispatch with no runner at all (qits-774) creates a RUNNER row on no runner and parks its
   * launch on QUEUED, as qits-626 made dispatch queue-aware — no 409, and nothing on the direct path.
   */
  @Test
  public void aDispatchWithNoRunnerAtAllCreatesARunnerRowAndParks() throws Exception {
    String repoId = repo();

    DispatchService.Dispatch answer =
        dispatchService.dispatch(repoId, "dispatched-none", false, null, WorkspaceSubject.none(), "go");
    Long id = answer.workspace().id();
    rows.add(id);

    Workspace row = read(id);
    assertEquals(WorkspacePlacement.RUNNER, row.placement);
    assertNull(row.runnerId);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, row.runtimeStatus);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, answer.workspace().runtimeStatus());
    assertEquals(DispatchService.AgentLaunch.SCHEDULED, answer.agentLaunch());
    assertTrue(dispatchService.isParked(id), "the launch waits for a runner");

    workspaceService.stopContainer(id); // drops the parked launch
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
  public void anAdminCreateIsDirectWithNoRunnerAtAll() throws Exception {
    String repoId = repo();

    Workspace created =
        workspaceService.createWorkspace(
            repoId, "admin-none", "master", "admin-none", null, false, false, true);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  /** An admin create stating DIRECT states what the rule answers anyway, and is accepted. */
  @Test
  public void anAdminCreateStatingDirectIsDirect() throws Exception {
    String repoId = repo();

    Workspace created =
        workspaceService.createWorkspace(
            repoId,
            "admin-stated",
            "master",
            "admin-stated",
            null,
            false,
            false,
            true,
            WorkspaceSubject.none(),
            null,
            WorkspacePlacement.DIRECT);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.DIRECT, read(created.id).placement);
  }

  @Test
  public void theEditorIsDirect() {
    Workspace editor = workspaceService.createEditorWorkspace();
    try {
      assertEquals(WorkspacePlacement.DIRECT, read(editor.id).placement);
    } finally {
      rows.add(editor.id);
    }
  }

  /** A regular create stating RUNNER states what happens anyway: no runner at all, still RUNNER. */
  @Test
  public void aRegularCreateStatingRunnerWithNoRunnerAtAllIsRunner() throws Exception {
    String repoId = repo();

    Workspace created =
        workspaceService.createWorkspace(
            repoId,
            "stated-runner",
            "master",
            "stated-runner",
            null,
            false,
            false,
            false,
            WorkspaceSubject.none(),
            null,
            WorkspacePlacement.RUNNER);
    rows.add(created.id);

    assertEquals(WorkspacePlacement.RUNNER, read(created.id).placement);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, read(created.id).runtimeStatus);
  }

  /** A regular create stating DIRECT: 400 {@code DIRECT_PLACEMENT_REFUSED}, nothing pushed. */
  @Test
  public void aRegularCreateStatingDirectIsRefused() throws Exception {
    String repoId = repo();

    DomainException refused =
        assertThrows(
            DomainException.class,
            () ->
                workspaceService.createWorkspace(
                    repoId,
                    "stated-direct",
                    "master",
                    "stated-direct",
                    null,
                    false,
                    false,
                    false,
                    WorkspaceSubject.none(),
                    null,
                    WorkspacePlacement.DIRECT));
    assertEquals(400, refused.statusCode());
    assertEquals(RunnerRefusals.DIRECT_PLACEMENT_REFUSED, refused.code());
    assertEquals(
        "Regular workspaces run on a workspace runner; only admin and editor workspaces use the"
            + " direct path.",
        refused.getMessage());
    assertFalse(workspaceService.branchExists(repoId, "stated-direct"), "nothing was pushed");
  }

  /** An admin row is DIRECT and stays DIRECT: nothing rewrites a placement. */
  @Test
  public void anAdminRowNeverMoves() throws Exception {
    String repoId = repo();
    Workspace created =
        workspaceService.createWorkspace(
            repoId, "early", "master", "early", null, false, false, true);
    rows.add(created.id);

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

  /** Every line the process has written so far, in order. */
  private List<String> lines(String processId) {
    TechnicalProcess process = processes.find(processId).orElseThrow();
    List<String> lines = new ArrayList<>();
    process.attach(
        new TechnicalProcess.Listener() {
          @Override
          public void onFrame(TechnicalProcessFrame frame) {
            if (TechnicalProcessFrame.KIND_LINE.equals(frame.kind())) {
              lines.add(frame.line());
            }
          }

          @Override
          public void onDone() {}

          @Override
          public boolean isOpen() {
            return true;
          }
        });
    return lines;
  }
}
