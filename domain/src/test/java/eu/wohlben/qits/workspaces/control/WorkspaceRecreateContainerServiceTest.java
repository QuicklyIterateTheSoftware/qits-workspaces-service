package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Recreate-container: the operation that rolls a workspace onto a newer workspace-daemon build by
 * tearing its container down and re-provisioning it from the durable branch
 * (docs/epics/qits-workspace-registry/). Verifies the agent-worktree gate (qits-1263: every live
 * agent on the slot must answer its cleanup check clean; leftover work, a busy harness and an
 * agent whose daemon cannot be asked are each refused 400 naming the agent; a slot with no agent
 * passes even with no daemon connected) and the teardown+reprovision mechanic (a fresh container
 * onto the current image; the persistent /workspace volume — and committed work pushed before
 * teardown — carry the checkout across).
 */
@QuarkusTest
public class WorkspaceRecreateContainerServiceTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject FakeContainerRuntime fakeContainers;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject WorkspaceContainerStoppingRecorder stoppingRecorder;
  @Inject FakeAgentDaemon agentDaemon;
  @Inject TestAgents agents;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * A repository with a bare origin on disk and a resolvable id. Replaces the monorepo's
   * clone-the-submodule-fixture setup, which needed the repositories context and a
   * build-time fixture-derivation step, neither of which exists here.
   */
  @BeforeEach
  void resetDaemon() {
    agentDaemon.reset();
  }

  @AfterEach
  void retireAgents() {
    agents.retireAll();
    agentDaemon.reset();
  }

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  private WorkspaceDto workspaceDto(String repoId, String workspaceId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> workspaceId.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  /**
   * ensureContainer, then wait for its async started event to land so a later await isn't fooled.
   */
  private void ensureRunning(String repoId, String workspaceId) throws InterruptedException {
    workspaceService.ensureContainer(workspaceIds.of(repoId, workspaceId));
    assertTrue(startedRecorder.awaitCount(repoId, workspaceId, 1, 5_000));
    startedRecorder.clear();
  }

  @Test
  public void recreateTearsDownAndReprovisionsAFreshContainerWhenClean() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);

    // An untracked marker in the working tree. Recreate re-provisions a FRESH container but keeps
    // the persistent /workspace volume (docs/epics/qits-workspaces/features/
    // 2026-07-25_persistent-workspace-volume.md — "recreation now preserves the working tree"), so
    // the checkout is reattached to the new container rather than re-cloned: the marker survives.
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode());

    // No agent on the slot → nothing of anybody's to lose: the gate passes and recreate runs.
    workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "recreate fires a fresh-provision started event");

    assertTrue(containers.exists(container), "a fresh container is running after recreate");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "recreate keeps the persistent /workspace volume, so the untracked file survives the"
            + " teardown+reprovision — the checkout is reattached, not re-cloned");
  }

  @Test
  public void recreateStopsTheOldContainerBeforeRemovingIt() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    fakeContainers.clearTeardownCalls();

    workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000), "recreate re-provisions");

    assertEquals(
        java.util.List.of("stop:" + container, "rm:" + container),
        fakeContainers.teardownCalls(),
        "recreate stops the old container gracefully (SIGTERM, so an agent inside can archive its"
            + " remote-control session) before the rm that would otherwise SIGKILL it");
  }

  @Test
  public void recreateReprovisionsEvenWhenTheGracefulStopOfTheOldContainerFails() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    fakeContainers.throwOnNextStop(container);

    workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat"));

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "a failed stop does not block the rm/reprovision that follows");
  }

  // MOVED: recreatePreservesCommittedWorkByPushingBeforeTeardown.
  // Same guarantee, same new owner as gracefulStopPushes... in WorkspaceContainerLifecycleServiceTest:
  // beginRecreateContainer no longer pushes before destroying the container, because the daemon has
  // already pushed anything committed. Unowned here; belongs with the daemon's auto-push.


  /**
   * The qits-1263 regression: the slot hosts no agent and its daemon is not connected — what every
   * slot looked like to the old gate, which waited on a GitStatus frame no daemon sends and refused
   * them all as "unknown". Nothing of anybody's is in the container, so the recreate runs.
   */
  @Test
  public void recreateRunsOnASlotWithNoAgentsEvenWithItsDaemonDisconnected() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    agentDaemon.disconnect(rowId);

    workspaceService.beginRecreateContainer(rowId);

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "a slot with no agent is recreated whatever its daemon's state");
  }

  /**
   * Agents whose worktrees answer clean — or that the daemon does not know, so they have no
   * worktree there — hold nothing the recreate could lose. Each one was asked.
   */
  @Test
  public void recreateRunsWhenEveryAgentOnTheSlotIsClean() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    UUID clean = agents.placeOn(repoId, rowId, "qits-1");
    UUID unknown = agents.placeOn(repoId, rowId, "qits-2");
    agentDaemon.forget(unknown.toString());

    workspaceService.beginRecreateContainer(rowId);

    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000), "recreate re-provisions");
    assertTrue(
        agentDaemon.callsFor(clean.toString()).contains("cleanup-check " + rowId + " " + clean),
        agentDaemon.callsFor(clean.toString()).toString());
    assertTrue(
        agentDaemon.callsFor(unknown.toString()).contains("cleanup-check " + rowId + " " + unknown),
        agentDaemon.callsFor(unknown.toString()).toString());
  }

  /**
   * qits-1745: the stopping edge names the live agents on the slot, read before the container goes;
   * a REMOVED agent is not among them.
   */
  @Test
  public void recreateNamesTheSlotsLiveAgentsOnItsStoppingEvent() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    UUID first = agents.placeOn(repoId, rowId, "qits-11");
    UUID second = agents.placeOn(repoId, rowId, "qits-12");
    UUID removed = agents.placeOn(repoId, rowId, "qits-13");
    agents.remove(removed);
    assertEquals(
        List.of(first.toString(), second.toString()), workspaceService.liveAgentIds(rowId));
    stoppingRecorder.clear();

    workspaceService.beginRecreateContainer(rowId);
    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000), "recreate re-provisions");

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), seen.toString());
    assertEquals(List.of(first.toString(), second.toString()), seen.get(0).event().agentIds());
  }

  @Test
  public void liveAgentIdsIsEmptyForASlotWithNoAgentAndForAnUnknownRow() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    assertEquals(List.of(), workspaceService.liveAgentIds(workspaceIds.of(repoId, "feat")));
    assertEquals(List.of(), workspaceService.liveAgentIds(-1L));
    assertEquals(List.of(), workspaceService.liveAgentIds(null));
  }

  @Test
  public void recreateRefusesAnAgentWithLeftoverWorkAndNamesIt() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");
    agents.placeOn(repoId, rowId, "qits-7");
    UUID holding = agents.placeOn(repoId, rowId, "qits-8");
    agentDaemon.leftover(
        holding.toString(),
        new AgentDaemon.CleanupCheck(
            false,
            List.of("qits-ci"),
            List.of(new AgentDaemon.CleanupCheck.Unpushed("qits-ci", "ticket/qits-8", 2))));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> workspaceService.beginRecreateContainer(rowId));

    assertEquals(400, ex.statusCode());
    assertEquals("AGENT_HAS_LEFTOVER_WORK", ex.code());
    assertTrue(ex.getMessage().contains("agent " + holding), ex.getMessage());
    assertTrue(ex.getMessage().contains("work item qits-8"), ex.getMessage());
    assertTrue(ex.getMessage().contains("uncommitted changes in qits-ci"), ex.getMessage());
    assertTrue(
        ex.getMessage().contains("2 unpushed commit(s) in qits-ci on ticket/qits-8"),
        ex.getMessage());
    assertFalse(ex.getMessage().contains("work item qits-7"), "the clean agent is not named");
    // The rejected recreate never touched the container — the marker (and the container) survive.
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
  }

  /** Recreate kills the container, and with it a harness still working in the background. */
  @Test
  public void recreateRefusesAnAgentBusyInTheBackground() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    UUID busy = agents.placeOn(repoId, rowId, "qits-9");
    agentDaemon.leftover(
        busy.toString(), new AgentDaemon.CleanupCheck(true, List.of(), List.of(), true));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> workspaceService.beginRecreateContainer(rowId));

    assertEquals("AGENT_BUSY", ex.code());
    assertTrue(ex.getMessage().contains("agent " + busy), ex.getMessage());
  }

  /**
   * Fail-closed: the slot hosts an agent, and its daemon cannot be asked whether that agent's
   * worktrees hold unpushed work. An unknowable worktree is not a safe basis to destroy a
   * container.
   */
  @Test
  public void recreateRefusesWhenTheAgentsCannotBeChecked() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");
    UUID agent = agents.placeOn(repoId, rowId, "qits-10");
    agentDaemon.disconnect(rowId);

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> workspaceService.beginRecreateContainer(rowId));

    assertEquals("AGENT_DAEMON_UNREACHABLE", ex.code());
    assertTrue(ex.getMessage().contains("agent " + agent), ex.getMessage());
    assertTrue(ex.getMessage().contains("not connected"), ex.getMessage());
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
  }

  /**
   * qits-1750 (ws-6054): an agent placed on the slot that never started there has nothing in the
   * container, so a daemon that cannot be asked about it does not stand in the recreate's way.
   */
  @Test
  public void recreateRunsPastAnAgentThatNeverStartedOnTheSlotWithItsDaemonDisconnected()
      throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null, false, false, true);
    ensureRunning(repoId, "feat");
    Long rowId = workspaceIds.of(repoId, "feat");
    UUID unstarted = agents.placeUnstartedOn(repoId, rowId, "qits-11");
    agentDaemon.disconnect(rowId);

    workspaceService.beginRecreateContainer(rowId);

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "an agent with no worktree on the slot does not hold the recreate");
    assertTrue(
        agentDaemon.callsFor(unstarted.toString()).isEmpty(),
        agentDaemon.callsFor(unstarted.toString()).toString());
  }

  @Test
  public void recreateThrows404ForAnUnknownWorkspace() throws Exception {
    clonedRepo();
    assertThrows(
        NotFoundException.class,
        () -> workspaceService.beginRecreateContainer(-1L));
  }
}
