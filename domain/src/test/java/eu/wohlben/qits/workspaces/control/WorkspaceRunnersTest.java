package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerRefDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import eu.wohlben.qits.workspaces.error.RunnerOwnsWorkspacesException;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The runner registry's rules ({@link WorkspaceRunners}) and the schema {@code V12} gives them: the
 * name rule, the delete refusal, the two placement checks and the runtime status column. The
 * placement tests write rows directly, so they prove the constraints and not a service check.
 */
@QuarkusTest
public class WorkspaceRunnersTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject WorkspaceRunners runners;
  @Inject WorkspaceRunnerRepository runnerRepository;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject WorkspaceService workspaceService;
  @Inject FakeRepositoryLookup repositories;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private final List<UUID> createdRunners = new ArrayList<>();
  private final List<Long> writtenRows = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              writtenRows.forEach(
                  id ->
                      workspaceRepository
                          .findByIdOptional(id)
                          .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED));
              createdRunners.forEach(runnerRepository::deleteById);
            });
    writtenRows.clear();
    createdRunners.clear();
  }

  // --- the name rule --------------------------------------------------------------------------------

  @Test
  public void aRunnerNameIsALowerCaseWordOfAtMost64Characters() {
    assertDoesNotThrow(() -> WorkspaceRunners.requireName("a"));
    assertDoesNotThrow(() -> WorkspaceRunners.requireName("node-1"));
    assertDoesNotThrow(() -> WorkspaceRunners.requireName("n" + "x".repeat(63)));

    for (String refused :
        new String[] {null, "", "1node", "-node", "Node", "node_1", "node.1", "n" + "x".repeat(64)}) {
      BadRequestException e =
          assertThrows(
              BadRequestException.class, () -> WorkspaceRunners.requireName(refused), refused);
      assertEquals(400, e.statusCode());
    }
  }

  @Test
  public void createRefusesAMalformedNameAndATakenOne() {
    assertThrows(BadRequestException.class, () -> create("Bad Name"));

    String name = uniqueName();
    WorkspaceRunner first = create(name);
    assertEquals(WorkspaceRunners.DEFAULT_SLOTS, first.slots);
    assertFalse(first.registered());

    ConflictException taken = assertThrows(ConflictException.class, () -> create(name));
    assertEquals(409, taken.statusCode());
    ConflictException checked =
        assertThrows(ConflictException.class, () -> runners.requireCreatable(name, null, 1));
    assertEquals(409, checked.statusCode());
  }

  @Test
  public void negativeSlotsAreRefusedAndZeroDrains() {
    WorkspaceRunner runner = create(uniqueName());
    assertThrows(BadRequestException.class, () -> runners.patch(runner.id, -1, null));

    WorkspaceRunner drained = runners.patch(runner.id, 0, "  ");
    assertEquals(0, drained.slots);
    assertNull(drained.description, "a blank description clears it");
  }

  // --- the workspace memory limits (qits-951) -----------------------------------------------------

  @Test
  public void aWorkspaceMemoryLimitIsADockerSizeOfAtLeast6m() {
    for (String accepted : new String[] {null, "", "  ", "6m", "12g", "12G", "12288m", " 16g "}) {
      assertDoesNotThrow(() -> WorkspaceRunners.requireWorkspaceMemoryLimit(accepted), accepted);
      assertDoesNotThrow(
          () -> WorkspaceRunners.requireWorkspaceMemorySwapLimit(accepted), accepted);
    }
    for (String refused :
        new String[] {"0", "5m", "6291455", "12gb", "1.5g", "-1", "twelve", "99999999999999999g"}) {
      BadRequestException e =
          assertThrows(
              BadRequestException.class,
              () -> WorkspaceRunners.requireWorkspaceMemoryLimit(refused),
              refused);
      assertEquals(400, e.statusCode());
      assertTrue(e.getMessage().startsWith("workspaceMemoryLimit"), e.getMessage());
    }
    assertDoesNotThrow(() -> WorkspaceRunners.requireWorkspaceMemorySwapLimit("-1"));
    assertDoesNotThrow(() -> WorkspaceRunners.requireWorkspaceMemorySwapLimit(" -1 "));
    BadRequestException swap =
        assertThrows(
            BadRequestException.class, () -> WorkspaceRunners.requireWorkspaceMemorySwapLimit("-2"));
    assertTrue(swap.getMessage().startsWith("workspaceMemorySwapLimit"), swap.getMessage());
  }

  @Test
  public void aSwapLimitIsAtLeastTheMemoryAndNeedsOne() {
    assertDoesNotThrow(() -> WorkspaceRunners.requireMemoryPair(null, null));
    assertDoesNotThrow(() -> WorkspaceRunners.requireMemoryPair("12g", null));
    assertDoesNotThrow(() -> WorkspaceRunners.requireMemoryPair("12g", "12g"));
    assertDoesNotThrow(() -> WorkspaceRunners.requireMemoryPair("12g", "12289m"));
    assertDoesNotThrow(() -> WorkspaceRunners.requireMemoryPair("12g", "-1"));
    assertDoesNotThrow(
        () -> WorkspaceRunners.requireMemoryPair(null, "-1"), "unlimited fits the default too");

    BadRequestException below =
        assertThrows(
            BadRequestException.class, () -> WorkspaceRunners.requireMemoryPair("12g", "8g"));
    assertTrue(below.getMessage().contains("cannot be below workspaceMemoryLimit"), below.getMessage());
    BadRequestException alone =
        assertThrows(
            BadRequestException.class, () -> WorkspaceRunners.requireMemoryPair(null, "16g"));
    assertTrue(alone.getMessage().contains("needs workspaceMemoryLimit"), alone.getMessage());
  }

  @Test
  public void createStoresTheLimitsTrimmedAndRefusesAPairThatDoesNotFit() {
    String name = uniqueName();
    assertThrows(
        BadRequestException.class, () -> runners.requireCreatable(name, null, 1, "12g", "8g"));
    assertThrows(
        BadRequestException.class, () -> runners.requireCreatable(name, null, 1, null, "8g"));
    assertThrows(
        BadRequestException.class, () -> runners.requireCreatable(name, null, 1, "4", null));
    UUID refusedId = UUID.randomUUID();
    assertThrows(
        BadRequestException.class,
        () -> runners.create(refusedId, name, null, 1, "12g", "8g", "t-" + refusedId, "s"));
    assertFalse(runnerExists(refusedId), "a refused create writes no row");

    UUID id = UUID.randomUUID();
    runners.create(id, name, null, 1, " 12g ", "16g", "token-" + id, "sub-" + id);
    createdRunners.add(id);
    WorkspaceRunner stored = runners.get(id);
    assertEquals("12g", stored.workspaceMemoryLimit);
    assertEquals("16g", stored.workspaceMemorySwapLimit);
    WorkspaceRunnerDto view = runners.view(stored);
    assertEquals("12g", view.workspaceMemoryLimit());
    assertEquals("16g", view.workspaceMemorySwapLimit());

    WorkspaceRunner unset = create(uniqueName());
    assertNull(runners.get(unset.id).workspaceMemoryLimit, "nothing is the platform default");
    assertNull(runners.get(unset.id).workspaceMemorySwapLimit);
    UUID blankId = UUID.randomUUID();
    runners.create(blankId, uniqueName(), null, 1, " ", "", "token-" + blankId, "sub-" + blankId);
    createdRunners.add(blankId);
    assertNull(runners.get(blankId).workspaceMemoryLimit, "blank is unset");
    assertNull(runners.get(blankId).workspaceMemorySwapLimit);
  }

  @Test
  public void patchLeavesOnNullClearsOnBlankAndChecksThePairItWouldLeave() {
    WorkspaceRunner runner = create(uniqueName());

    WorkspaceRunner set = runners.patch(runner.id, null, null, "12g", null);
    assertEquals("12g", set.workspaceMemoryLimit);
    assertNull(set.workspaceMemorySwapLimit);

    set = runners.patch(runner.id, null, null, null, "16g");
    assertEquals("12g", set.workspaceMemoryLimit, "null leaves the memory");
    assertEquals("16g", set.workspaceMemorySwapLimit);

    // The resulting pair is what is checked: a memory raised above the stored swap is refused, and
    // clearing the memory under a set swap is too — and neither writes anything.
    assertThrows(BadRequestException.class, () -> runners.patch(runner.id, 3, null, "20g", null));
    assertThrows(BadRequestException.class, () -> runners.patch(runner.id, 3, null, "", null));
    WorkspaceRunner unchanged = runners.get(runner.id);
    assertEquals("12g", unchanged.workspaceMemoryLimit);
    assertEquals("16g", unchanged.workspaceMemorySwapLimit);
    assertEquals(WorkspaceRunners.DEFAULT_SLOTS, unchanged.slots, "a refused patch moves nothing");

    WorkspaceRunner both = runners.patch(runner.id, null, null, "20g", "24g");
    assertEquals("20g", both.workspaceMemoryLimit);
    assertEquals("24g", both.workspaceMemorySwapLimit);

    WorkspaceRunner unlimited = runners.patch(runner.id, null, null, null, "-1");
    assertEquals("-1", unlimited.workspaceMemorySwapLimit);

    assertThrows(BadRequestException.class, () -> runners.patch(runner.id, null, null, "5m", null));

    WorkspaceRunner cleared = runners.patch(runner.id, null, null, "", "");
    assertNull(cleared.workspaceMemoryLimit, "blank clears it back to the platform default");
    assertNull(cleared.workspaceMemorySwapLimit);
    assertNull(runners.get(runner.id).workspaceMemoryLimit);

    WorkspaceRunner described = runners.patch(runner.id, 2, "only slots");
    assertNull(described.workspaceMemoryLimit, "the old patch leaves the limits alone");
  }

  // --- registration and standing ------------------------------------------------------------------

  @Test
  public void registrationChecksTheSubjectQuarantinesAndSpendsTheToken() throws Exception {
    WorkspaceRunner runner = create(uniqueName());

    assertThrows(ForbiddenException.class, () -> runners.requireRegistrable(runner.id, "other"));
    assertThrows(ForbiddenException.class, () -> runners.requireRegistrable(runner.id, null));
    runners.requireRegistrable(runner.id, "sub-" + runner.id);

    WorkspaceRunner registered =
        runners.markRegistered(runner.id, "client-1", JSON.readTree("{\"version\":\"1.0\"}"));
    assertTrue(registered.registered());
    assertTrue(registered.quarantined(), "a new runner waits for its first health check");
    assertEquals(WorkspaceRunners.AWAITING_FIRST_HEALTH_CHECK, registered.quarantineReason);
    assertNull(registered.registrationTokenId, "the spent token is referenced no more");
    assertFalse(registered.eligible());

    assertThrows(
        ConflictException.class, () -> runners.requireRegistrable(runner.id, "sub-" + runner.id));
    assertThrows(
        ConflictException.class, () -> runners.markRegistered(runner.id, "client-2", null));

    runners.recordHealthCheck(runner.id, true, null);
    WorkspaceRunner greenlit = runners.greenlight(runner.id);
    assertFalse(greenlit.quarantined());
    assertTrue(greenlit.eligible());
    assertEquals(Boolean.TRUE, greenlit.lastHealthCheckOk);
    assertEquals(
        runner.id, runners.findByClientId("client-1").map(r -> r.id).orElseThrow());
  }

  @Test
  public void capabilitiesMergeKeyByKeyAndKeepTheLastKnown() throws Exception {
    WorkspaceRunner runner = create(uniqueName());
    runners.markRegistered(
        runner.id, "client-" + runner.id, JSON.readTree("{\"version\":\"1.0\",\"arch\":\"amd64\"}"));

    runners.recordCapabilities(
        runner.id, JSON.readTree("{\"dotClaudeVolume\":\"qits-workspaces-runner-dot-claude-ab\"}"));
    runners.recordCapabilities(
        runner.id,
        JSON.readTree(
            "{\"login\":{\"claude\":\"ABSENT\",\"kimi\":\"UNKNOWN\","
                + "\"checkedAt\":\"2026-10-05T10:00:00Z\"}}"));
    WorkspaceRunner after =
        runners.recordCapabilities(
            runner.id, JSON.readTree("{\"version\":\"1.1\",\"login\":{\"claude\":\"PRESENT\"}}"));

    WorkspaceRunnerDto view = runners.view(after);
    assertEquals("1.1", view.version(), "a reported key replaces the stored one");
    assertEquals("amd64", view.arch(), "an unmentioned key keeps its last known value");
    assertEquals("qits-workspaces-runner-dot-claude-ab", view.dotClaudeVolume());
    assertEquals(
        new WorkspaceRunnerDto.Login("PRESENT", null, null),
        view.login(),
        "login is replaced whole, never a mix of two probes");

    assertThrows(
        BadRequestException.class, () -> runners.recordCapabilities(runner.id, JSON.readTree("[]")));
  }

  /**
   * qits-850: a failed check of a runner already out keeps its {@code quarantined_at} — the
   * back-off schedule counts from it — and takes the newer reason; one in service is taken out now.
   */
  @Test
  public void quarantineForKeepsTheQuarantineInstantAndTakesTheNewerReason() {
    WorkspaceRunner runner = create(uniqueName());
    runners.markRegistered(runner.id, "client-" + runner.id, null);
    WorkspaceRunner before = runners.get(runner.id);

    WorkspaceRunners.Quarantine kept = runners.quarantineFor(runner.id, "health check failed: x");

    assertFalse(kept.began());
    assertTrue(kept.reasonChanged());
    assertEquals(before.quarantinedAt, runners.get(runner.id).quarantinedAt);
    assertEquals("health check failed: x", runners.get(runner.id).quarantineReason);
    assertFalse(runners.quarantineFor(runner.id, "health check failed: x").reasonChanged());

    runners.greenlight(runner.id);
    WorkspaceRunners.Quarantine began = runners.quarantineFor(runner.id, "health check failed: y");
    assertTrue(began.began());
    assertTrue(runners.get(runner.id).quarantined());
  }

  /**
   * qits-850: the report lands as the capabilities' {@code health} key beside the columns, and
   * every other key is kept; a {@code health} key in a runner's own report is not the runner's to
   * write.
   */
  @Test
  public void aHealthReportIsKeptBesideWhatTheRunnerSaid() throws Exception {
    WorkspaceRunner runner = create(uniqueName());
    runners.markRegistered(
        runner.id, "client-" + runner.id, JSON.readTree("{\"version\":\"1.0\"}"));
    var report =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            JSON.readTree(
                "{\"at\":\"2026-10-05T10:00:00Z\",\"ok\":false,\"detail\":\"selfTest failed\","
                    + "\"requestId\":\"r-1\",\"checks\":[{\"name\":\"nodeInventory\",\"ok\":true,"
                    + "\"detail\":\"1 container\",\"data\":{\"containers\":[{\"name\":\"c\"}]}}]}");

    WorkspaceRunner after =
        runners.recordHealthCheck(runner.id, false, java.time.Instant.now(), report);
    runners.recordCapabilities(runner.id, JSON.readTree("{\"health\":{\"ok\":true}}"));

    assertEquals(Boolean.FALSE, after.lastHealthCheckOk);
    WorkspaceRunnerDto view = runners.view(runners.get(runner.id));
    assertEquals("1.0", view.version());
    assertFalse(view.health().ok(), "the runner's own health key was ignored");
    assertEquals(
        List.of(new WorkspaceRunnerDto.Check("nodeInventory", true, "1 container")),
        view.health().checks());
    assertEquals(
        "c",
        runners.health(runner.id).checks().get(0).data().path("containers").get(0).path("name").asText());
    assertEquals("r-1", runners.health(runner.id).requestId());
  }

  // --- the delete refusal -------------------------------------------------------------------------

  @Test
  public void aRunnerOwningAnActiveWorkspaceIsNotDeleted() {
    WorkspaceRunner runner = create(uniqueName());
    Long owned =
        insert(
            w -> {
              w.placement = WorkspacePlacement.RUNNER;
              w.runnerId = runner.id;
              w.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
            });

    assertEquals(1, count(() -> workspaceRepository.countActiveOnRunner(runner.id)));
    assertEquals(1, count(() -> workspaceRepository.countLiveOnRunner(runner.id)));
    RunnerOwnsWorkspacesException refused =
        assertThrows(RunnerOwnsWorkspacesException.class, () -> runners.delete(runner.id));
    assertEquals(409, refused.statusCode());
    assertEquals("RUNNER_OWNS_WORKSPACES", refused.code());
    assertEquals(List.of(owned), refused.workspaceIds());
    Workspace row = QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(owned));
    assertEquals(
        List.of(
            new RunnerOwnsWorkspacesException.OwnedWorkspace(owned, row.repositoryId, row.branch)),
        refused.workspaces(),
        "each row with what the runners page links it by");
    assertTrue(runnerExists(runner.id));
    assertEquals(
        new WorkspaceRunners.Counts(1, 1, 0), runners.counts(runner.id), "running, owned, queued");

    // Resolved, the row keeps the runner's id as history and holds nothing up.
    QuarkusTransaction.requiringNew()
        .run(() -> workspaceRepository.findById(owned).status = WorkspaceStatus.ABANDONED);
    assertEquals(0, count(() -> workspaceRepository.countActiveOnRunner(runner.id)));

    runners.delete(runner.id);
    assertFalse(runnerExists(runner.id));
  }

  @Test
  public void onlyRunningAndProvisioningRowsHoldASlot() {
    WorkspaceRunner runner = create(uniqueName());
    for (WorkspaceRuntimeStatus status :
        List.of(
            WorkspaceRuntimeStatus.RUNNING,
            WorkspaceRuntimeStatus.PROVISIONING,
            WorkspaceRuntimeStatus.STOPPED,
            WorkspaceRuntimeStatus.QUEUED,
            WorkspaceRuntimeStatus.FAILED)) {
      insert(
          w -> {
            w.placement = WorkspacePlacement.RUNNER;
            w.runnerId = runner.id;
            w.runtimeStatus = status;
          });
    }

    assertEquals(2, count(() -> workspaceRepository.countLiveOnRunner(runner.id)));
    assertEquals(5, count(() -> workspaceRepository.countActiveOnRunner(runner.id)));
    assertEquals(
        5,
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveIdsOnRunner(runner.id))
            .size());
  }

  // --- the schema ---------------------------------------------------------------------------------

  @Test
  public void everyRowIsDirectUnlessItSaysOtherwise() {
    // An admin row: an ACTIVE regular one may not be DIRECT at all (V15, below).
    Long id = insert(w -> w.admin = true);
    Workspace row = QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(id));
    assertEquals(WorkspacePlacement.DIRECT, row.placement);
    assertNull(row.runnerId);
    assertNull(row.queuedAt);
  }

  @Test
  public void anAdminOrEditorRowIsNeverPlacedOnARunner() {
    RuntimeException admin =
        assertThrows(
            RuntimeException.class,
            () ->
                insert(
                    w -> {
                      w.placement = WorkspacePlacement.RUNNER;
                      w.admin = true;
                    }));
    assertTrue(names(admin, "ck_workspace_runner_posture"), messages(admin));

    RuntimeException editor =
        assertThrows(
            RuntimeException.class,
            () ->
                insert(
                    w -> {
                      w.placement = WorkspacePlacement.RUNNER;
                      w.editor = true;
                    }));
    assertTrue(names(editor, "ck_workspace_runner_posture"), messages(editor));

    // A RUNNER row with neither is fine.
    assertDoesNotThrow(() -> insert(w -> w.placement = WorkspacePlacement.RUNNER));
  }

  @Test
  public void onlyARunnerRowNamesARunner() {
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                insert(
                    w -> {
                      w.admin = true;
                      w.runnerId = UUID.randomUUID();
                    }));
    assertTrue(names(refused, "ck_workspace_runner_placement"), messages(refused));
  }

  /**
   * V15 (qits-780): the direct path is admin and editor only, as the schema keeps it. An ACTIVE
   * regular row may not be DIRECT; an admin one may, a RUNNER one may, and a resolved regular row
   * keeps whatever placement it had as history.
   */
  @Test
  public void anActiveRegularRowIsNeverDirect() {
    RuntimeException refused = assertThrows(RuntimeException.class, () -> insert(w -> {}));
    assertTrue(names(refused, "ck_workspace_direct_only_admin_editor"), messages(refused));

    assertDoesNotThrow(() -> insert(w -> w.admin = true));
    assertDoesNotThrow(() -> insert(w -> w.placement = WorkspacePlacement.RUNNER));
    assertDoesNotThrow(() -> insert(w -> w.status = WorkspaceStatus.ABANDONED));
  }

  @Test
  public void queuedIsStoredAndUnavailableIsNot() {
    assertDoesNotThrow(
        () ->
            insert(
                w -> {
                  w.placement = WorkspacePlacement.RUNNER;
                  w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
                }));

    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                insert(
                    w -> {
                      w.placement = WorkspacePlacement.RUNNER;
                      w.runtimeStatus = WorkspaceRuntimeStatus.UNAVAILABLE;
                    }));
    assertTrue(names(refused, "ck_workspace_runtime_status"), messages(refused));
  }

  // --- the workspace read model ------------------------------------------------------------------

  @Test
  public void aWorkspaceReadsItsPlacementAndItsRunnersName() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    // The DIRECT side is an admin workspace: the direct path is admin and editor only (qits-780).
    workspaceService.createWorkspace(repoId, "adm", "master", "adm", null, false, false, true);
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    WorkspaceRunner runner = create(uniqueName());
    java.time.Instant queuedAt = java.time.Instant.parse("2026-10-05T10:00:00Z");
    Long feat =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row =
                      workspaceRepository.findActiveByRepositoryAndBranch(repoId, "feat").orElseThrow();
                  row.placement = WorkspacePlacement.RUNNER;
                  row.runnerId = runner.id;
                  row.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
                  row.queuedAt = queuedAt;
                  return row.id;
                });
    writtenRows.add(feat);

    List<WorkspaceDto> listed = workspaceService.listWorkspaces(repoId);
    WorkspaceDto placed = listed.stream().filter(w -> feat.equals(w.id())).findFirst().orElseThrow();
    assertEquals(WorkspacePlacement.RUNNER, placed.placement());
    assertEquals(new WorkspaceRunnerRefDto(runner.id, runner.name), placed.runner());
    assertEquals(queuedAt, placed.queuedAt());

    WorkspaceDto main = listed.stream().filter(w -> !feat.equals(w.id())).findFirst().orElseThrow();
    assertEquals(WorkspacePlacement.DIRECT, main.placement());
    assertNull(main.runner(), "a DIRECT workspace is on no runner");
    assertNull(main.queuedAt());
  }

  // --- helpers ------------------------------------------------------------------------------------

  private WorkspaceRunner create(String name) {
    UUID id = UUID.randomUUID();
    WorkspaceRunner runner = runners.create(id, name, null, null, "token-" + id, "sub-" + id);
    createdRunners.add(id);
    return runner;
  }

  private static String uniqueName() {
    return "r-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private boolean runnerExists(UUID id) {
    return QuarkusTransaction.requiringNew().call(() -> runnerRepository.findById(id) != null);
  }

  private static long count(java.util.function.Supplier<Long> read) {
    return QuarkusTransaction.requiringNew().call(read::get);
  }

  /** One ACTIVE row on a fresh branch, shaped by {@code shape}. */
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
                  shape.accept(workspace);
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  return workspace.id;
                });
    writtenRows.add(id);
    return id;
  }

  /** Whether the failure, or anything it was caused by, names {@code constraint}. */
  private static boolean names(Throwable failure, String constraint) {
    return messages(failure).contains(constraint);
  }

  private static String messages(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      all.append(t).append('\n');
    }
    return all.toString();
  }
}
