package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The streamed Start ({@code beginEnsureContainer}): registers a technical process, runs the
 * provision off-thread, and the process's replay tells the whole story — {@code container} and
 * {@code clone} segments (with the fake runtime's real git output) settling {@code ok} on success,
 * a {@code done failed} on a dead branch, and a no-op completion for an already-running container.
 * Driven through {@link FakeContainerRuntime}, so no docker is needed.
 */
@QuarkusTest
public class WorkspaceEnsureContainerProcessTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject TechnicalProcessRegistry registry;
  @Inject FakeWorkspaceDaemonProvisioner provisioner;
  @Inject FakeContainerRuntime containers;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * Records a process's replay (attach on a terminal process replays + done). On a live process the
   * listener stays attached and frames keep arriving from the start's own thread while a test
   * streams them, so the list must iterate over a snapshot.
   */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new CopyOnWriteArrayList<>();

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
  }

  @AfterEach
  void releaseTheFakes() {
    provisioner.reset();
  }

  private String repoWithWorkspace(String workspaceId) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(
        repoId, workspaceId, "master", workspaceId, null, false, false, true);
    return repoId;
  }

  private TechnicalProcess awaitTerminal(String processId) throws InterruptedException {
    TechnicalProcess process = registry.find(processId).orElseThrow();
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!process.isTerminal() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }
    assertTrue(process.isTerminal(), "process did not reach done in time");
    return process;
  }

  private static Replay replayOf(TechnicalProcess process) {
    Replay replay = new Replay();
    process.attach(replay);
    return replay;
  }

  private static TechnicalProcessFrame settled(Replay replay, String segment) {
    return replay.frames.stream()
        .filter(f -> "segment-settled".equals(f.kind()) && segment.equals(f.segment()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("segment '" + segment + "' never settled"));
  }

  private static TechnicalProcessFrame doneFrame(Replay replay) {
    return replay.frames.stream()
        .filter(f -> "done".equals(f.kind()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no done frame"));
  }

  @Test
  public void aFreshProvisionStreamsDockerRunAndCloneSegmentsAndEndsDoneOk() throws Exception {
    String repoId = repoWithWorkspace("stream");

    String processId = workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "stream"));
    assertNotNull(processId);
    assertEquals(processId, registry.activeFor(repoId, "stream").orElseThrow());

    Replay replay = replayOf(awaitTerminal(processId));
    assertEquals("ok", settled(replay, "container").status());
    assertEquals("ok", settled(replay, "clone").status());
    assertEquals("ok", doneFrame(replay).status());
    // The fake runtime runs a real host `git clone`, whose output streams into the clone segment.
    assertTrue(
        replay.frames.stream()
            .anyMatch(
                f ->
                    "line".equals(f.kind())
                        && "clone".equals(f.segment())
                        && f.line().contains("Cloning")),
        "the clone segment carries git's own output lines");
    assertTrue(registry.activeFor(repoId, "stream").isEmpty(), "done clears the active mapping");
    assertEquals(
        eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus.RUNNING,
        workspaceService.getWorkspace(workspaceIds.of(repoId, "stream")).runtimeStatus());
  }

  @Test
  public void aSecondStartCompletesAsANoOpWithoutReprovisioning() throws Exception {
    String repoId = repoWithWorkspace("noop");
    awaitTerminal(workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "noop")));

    Replay replay = replayOf(awaitTerminal(workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "noop"))));
    assertEquals("ok", settled(replay, "container-start").status());
    assertEquals("ok", doneFrame(replay).status());
    assertTrue(
        replay.frames.stream().noneMatch(f -> "container".equals(f.segment())),
        "an already-running container must not re-provision");
  }

  @Test
  public void aDeadBranchEndsTheProcessDoneFailedWithTheReasonInTheStream() throws Exception {
    String repoId = repoWithWorkspace("doomed");
    // Kill the workspace's branch in the bare origin, so the provision has nothing to recreate
    // from (the branch-gone abandonment path).
    TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), "git", "branch", "-D", "--", "doomed");

    Replay replay =
        replayOf(awaitTerminal(workspaceService.beginEnsureContainer(workspaceIds.of(repoId, "doomed"))));
    assertEquals("failed", doneFrame(replay).status());
    assertTrue(
        replay.frames.stream()
            .anyMatch(f -> "line".equals(f.kind()) && f.line().contains("no branch")),
        "the abandonment reason lands in the stream");
  }

  @Test
  public void anUnknownWorkspaceFailsFastInRequest() {
    assertThrows(
        NotFoundException.class, () -> workspaceService.beginEnsureContainer(-1L));
  }

  // ---- qits-1076: a stop during the start window -------------------------------------------------

  /** The row as persisted, read in a transaction of its own — not the listing's live overlay. */
  private Workspace persisted(Long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(id).orElseThrow());
  }

  private static void await(BooleanSupplier condition, String what) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
      Thread.sleep(25);
    }
    assertTrue(condition.getAsBoolean(), what);
  }

  private long callsOn(String verb, String container) {
    return containers.teardownCalls().stream().filter((verb + ":" + container)::equals).count();
  }

  private static boolean streamSays(Replay replay, String text) {
    return replay.frames.stream()
        .anyMatch(f -> "line".equals(f.kind()) && f.line() != null && f.line().contains(text));
  }

  /** A start parked in its clone window: the row PROVISIONING and the fake daemon not yet reported. */
  private String startHeldInTheCloneWindow(Long id) throws InterruptedException {
    String processId = workspaceService.beginEnsureContainer(id);
    await(
        () ->
            provisioner.isHolding(id)
                && persisted(id).runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING,
        "the start never reached its clone window");
    return processId;
  }

  /**
   * The clone window (qits-1076): a stop while the start awaits the daemon's self-provision ends that
   * start at once — done failed, the reason in its stream, no longer the row's active process — and
   * the start yields: the container is left stopped, never removed, and the row is STOPPED with no
   * runtime error rather than FAILED. The next ensure-container then starts the container again
   * under a new process instead of joining the dead one.
   */
  @Test
  public void aStopInTheCloneWindowEndsTheStartRemovesNothingAndTheNextEnsureStartsAgain()
      throws Exception {
    provisioner.holdDirectRows();
    String repoId = repoWithWorkspace("clone-stop");
    Long id = workspaceIds.of(repoId, "clone-stop");
    String container = containers.containerName("clone-stop", repoId);
    String first = startHeldInTheCloneWindow(id);

    long before = System.currentTimeMillis();
    workspaceService.stopContainer(id);

    TechnicalProcess ended = registry.find(first).orElseThrow();
    assertTrue(ended.isTerminal(), "the stop ends the start it overtook, not the provision timeout");
    assertTrue(System.currentTimeMillis() - before < 5_000, "the start ended promptly");
    Replay replay = replayOf(ended);
    assertEquals("failed", doneFrame(replay).status());
    assertTrue(streamSays(replay, "stopped while it was starting"), "the stream says why");
    assertTrue(registry.activeFor(id).isEmpty(), "no process is left for an ensure to join");

    await(() -> !provisioner.isHolding(id), "the stop released the provision await");
    // The provision thread yields by stopping the container in place once more: wait for that, so
    // the assertions below see where it left things rather than racing it.
    await(() -> callsOn("stop", container) >= 2, "the overtaken start never yielded");
    Workspace row = persisted(id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.runtimeError, "a stop is not a failed start");
    assertEquals(0, callsOn("rm", container), "the paused container must not be removed");
    assertTrue(containers.exists(container), "the container is kept, stopped in place");
    assertFalse(containers.isRunning(container));

    String second = workspaceService.beginEnsureContainer(id);
    assertNotNull(second);
    assertNotEquals(first, second, "the ensure starts afresh instead of joining the dead start");
    assertEquals("ok", doneFrame(replayOf(awaitTerminal(second))).status());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, persisted(id).runtimeStatus);
    assertTrue(containers.isRunning(container));
    assertEquals(0, callsOn("rm", container));
  }

  /**
   * A provision outcome that arrives after the stop — the daemon's failure was already on its way —
   * is the stop's, not the start's: no removal, no FAILED, the row stays STOPPED.
   */
  @Test
  public void aFailedProvisionOutcomeArrivingAfterAStopTearsNothingDown() throws Exception {
    provisioner.holdDirectRows();
    provisioner.ignoreAbandon();
    String repoId = repoWithWorkspace("late-fail");
    Long id = workspaceIds.of(repoId, "late-fail");
    String container = containers.containerName("late-fail", repoId);
    String first = startHeldInTheCloneWindow(id);

    workspaceService.stopContainer(id);
    assertTrue(registry.find(first).orElseThrow().isTerminal());
    assertTrue(provisioner.isHolding(id), "the outcome has not arrived yet");

    provisioner.complete(id, ProvisionResult.failed("daemon reported the clone failed"));

    await(() -> callsOn("stop", container) >= 2, "the overtaken start never yielded");
    Workspace row = persisted(id);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, row.runtimeStatus);
    assertNull(row.runtimeError);
    assertEquals(0, callsOn("rm", container), "a late failure must not remove a stopped container");
    assertTrue(containers.exists(container));
  }

  /**
   * And a clone that succeeds after the stop does not turn the stopped row RUNNING, nor leave its
   * container running: the start yields exactly as a failed one does.
   */
  @Test
  public void aSuccessfulProvisionArrivingAfterAStopLeavesTheRowStopped() throws Exception {
    provisioner.holdDirectRows();
    provisioner.ignoreAbandon();
    String repoId = repoWithWorkspace("late-ok");
    Long id = workspaceIds.of(repoId, "late-ok");
    String container = containers.containerName("late-ok", repoId);
    startHeldInTheCloneWindow(id);

    workspaceService.stopContainer(id);
    provisioner.complete(id, ProvisionResult.ok(""));

    await(() -> callsOn("stop", container) >= 2, "the overtaken start never yielded");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, persisted(id).runtimeStatus);
    assertFalse(containers.isRunning(container), "the start must not leave the container running");
    assertEquals(0, callsOn("rm", container));
  }

  /**
   * The bootstrap window (qits-1076): the clone is done and the row RUNNING, but the start's process
   * stays open until the bootstrap chain settles. A stop there ends it too, so the next
   * ensure-container starts the container under a new process rather than answering the old id.
   */
  @Test
  public void aStopInTheBootstrapWindowEndsTheStartAndTheNextEnsureStartsAgain() throws Exception {
    Path release = Files.createTempDirectory("qits-1076-bootstrap").resolve("release");
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    commitBootstrapChain(
        repoId,
        "version: 1\nbootstrap:\n  - name: 'hold'\n    execute: 'while [ ! -e "
            + release
            + " ]; do sleep 0.1; done'\n");
    workspaceService.createWorkspace(
        repoId, "boot-stop", "master", "boot-stop", null, false, false, true);
    Long id = workspaceIds.of(repoId, "boot-stop");
    try {
      String first = workspaceService.beginEnsureContainer(id);
      await(
          () -> persisted(id).runtimeStatus == WorkspaceRuntimeStatus.RUNNING,
          "the clone never finished");
      await(
          () ->
              replayOf(registry.find(first).orElseThrow()).frames.stream()
                  .anyMatch(f -> f.segment() != null && f.segment().startsWith("bootstrap")),
          "the bootstrap chain never began");
      assertFalse(registry.find(first).orElseThrow().isTerminal(), "the chain holds the start open");
      assertEquals(first, registry.activeFor(id).orElseThrow());

      workspaceService.stopContainer(id);

      TechnicalProcess ended = registry.find(first).orElseThrow();
      assertTrue(ended.isTerminal(), "the stop ends a start past its provision phase too");
      assertEquals("failed", doneFrame(replayOf(ended)).status());
      assertTrue(registry.activeFor(id).isEmpty());
      assertEquals(WorkspaceRuntimeStatus.STOPPED, persisted(id).runtimeStatus);

      String second = workspaceService.beginEnsureContainer(id);
      assertNotEquals(first, second, "the ensure must not answer the old start's id");
      assertEquals("ok", doneFrame(replayOf(awaitTerminal(second))).status());
      assertEquals(WorkspaceRuntimeStatus.RUNNING, persisted(id).runtimeStatus);
    } finally {
      Files.createFile(release); // drain the held step, so no host loop outlives the test
    }
  }

  /** Commit {@code yaml} as {@code .qits-config.yml} on the origin's master, before any fork. */
  private void commitBootstrapChain(String repoId, String yaml) throws Exception {
    Path origin = Path.of(dataDir, repoId, "origin");
    Path worktree = Files.createTempDirectory("qits-1076-config");
    TestGit.exec(null, "git", "clone", origin.toString(), worktree.toString());
    TestGit.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    TestGit.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(worktree.resolve(".qits-config.yml"), yaml);
    TestGit.exec(worktree.toFile(), "git", "add", ".qits-config.yml");
    TestGit.exec(worktree.toFile(), "git", "commit", "-m", "stage qits config");
    TestGit.exec(worktree.toFile(), "git", "push", "origin", "HEAD:master");
  }

  /**
   * A STOPPED row with a start still open — the instant between a stop and its ending of that
   * start, played here by a process registered straight on the registry — is a 409 naming the
   * process, never a 200 handing back a start that will not bring the container up.
   */
  @Test
  public void anEnsureOnAStoppedRowWithAStartStillOpenIsAConflictNamingTheProcess()
      throws Exception {
    String repoId = repoWithWorkspace("still-open");
    Long id = workspaceIds.of(repoId, "still-open");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, persisted(id).runtimeStatus);
    TechnicalProcess open = registry.begin(repoId, "still-open", id);
    try {
      ConflictException refused =
          assertThrows(ConflictException.class, () -> workspaceService.beginEnsureContainer(id));
      assertEquals(409, refused.statusCode());
      assertEquals("WORKSPACE_STOPPED_DURING_START", refused.code());
      assertTrue(refused.getMessage().contains(open.id()), refused.getMessage());
      assertTrue(
          refused.getMessage().contains("stopped while it was starting"), refused.getMessage());
      assertTrue(refused.getMessage().contains("retry"), refused.getMessage());
      assertEquals(0, containers.runCount(containers.containerName("still-open", repoId)));
    } finally {
      registry.end(open.id(), "test over");
    }
  }

  /**
   * qits-853 still holds: an ensure-container straight after the create joins the create's start —
   * before its thread has marked the row PROVISIONING and while it is cloning — and the container is
   * run once.
   */
  @Test
  public void anEnsureRightAfterTheCreateJoinsTheCreatesStart() throws Exception {
    provisioner.holdDirectRows();
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    WorkspaceService.CreatedWorkspace created =
        workspaceService.createAndStartWorkspace(
            repoId,
            "joined",
            "master",
            "joined",
            null,
            false,
            false,
            true,
            WorkspaceSubject.none(),
            null,
            null);
    Long id = created.workspace().id;
    assertNotNull(created.technicalProcessId());

    assertEquals(created.technicalProcessId(), workspaceService.beginEnsureContainer(id));
    await(
        () ->
            provisioner.isHolding(id)
                && persisted(id).runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING,
        "the create's start never reached its clone window");
    assertEquals(created.technicalProcessId(), workspaceService.beginEnsureContainer(id));

    provisioner.complete(id, ProvisionResult.ok(""));
    assertEquals(
        "ok", doneFrame(replayOf(awaitTerminal(created.technicalProcessId()))).status());
    assertEquals(1, containers.runCount(containers.containerName("joined", repoId)));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, persisted(id).runtimeStatus);
  }
}
