package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.TechnicalProcessFrame;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** Records a terminal process's full replay (attach on a terminal process replays + done). */
  private static final class Replay implements TechnicalProcess.Listener {
    final List<TechnicalProcessFrame> frames = new ArrayList<>();

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

  private String repoWithWorkspace(String workspaceId) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, workspaceId, "master", workspaceId));
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
}
