package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Recreate-container: the operation that rolls a workspace onto a newer workspace-daemon build by
 * tearing its container down and re-provisioning it from the durable branch
 * (docs/epics/qits-workspace-registry/). Verifies the registry-only clean gate (only a
 * daemon-reported clean tree passes; dirty <em>and</em> unknown are both rejected 400) and the
 * teardown+reprovision mechanic (a fresh container onto the current image; the persistent
 * /workspace volume — and committed work pushed before teardown — carry the checkout across).
 */
@QuarkusTest
public class WorkspaceRecreateContainerServiceTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject FakeContainerRuntime fakeContainers;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject FakeWorkspaceGitStatus gitStatus;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * A repository with a bare origin on disk and a resolvable id. Replaces the monorepo's
   * clone-the-submodule-fixture setup, which needed the repositories context and a
   * build-time fixture-derivation step, neither of which exists here.
   */
  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    // cloneRepository used to register the main branch's workspace row as part of cloning; that
    // call lives in this context, so the fixture makes it directly.
    workspaceService.createMainWorkspace(repoId, "master");
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
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
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

    // Daemon reports the tree clean → the gate passes and recreate runs.
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
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
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
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
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
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


  @Test
  public void recreateRefusesADirtyWorkspace() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");

    gitStatus.report(workspaceIds.of(repoId, "feat"), false);
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat")));
    assertTrue(ex.getMessage().contains("dirty"), ex.getMessage());

    // The rejected recreate never touched the container — the marker (and the container) survive.
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
  }

  @Test
  public void recreateRefusesAnUnknownWorkingTree() throws Exception {
    String repoId = clonedRepo();
    LegacyDirectRows.direct(() ->
        workspaceService.createWorkspace(repoId, "feat", "master", "feat", null));
    ensureRunning(repoId, "feat");
    String container = containers.containerName("feat", repoId);
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > marker.txt");

    // No daemon has reported cleanliness → UNKNOWN, which recreate must reject just like dirty:
    // an unknowable tree is not a safe basis to destroy a container.
    gitStatus.forget(workspaceIds.of(repoId, "feat"));
    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat")));
    assertTrue(ex.getMessage().contains("unknown"), ex.getMessage());
    assertEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "test", "-f", "marker.txt").exitCode(),
        "a rejected recreate leaves the container untouched");
  }

  @Test
  public void recreateThrows404ForAnUnknownWorkspace() throws Exception {
    clonedRepo();
    assertThrows(
        NotFoundException.class,
        () -> workspaceService.beginRecreateContainer(-1L));
  }
}
