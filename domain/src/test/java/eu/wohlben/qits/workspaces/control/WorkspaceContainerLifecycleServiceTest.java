package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies the disposable-container lifecycle against a real cloned-fixture repo (Fake runtime):
 * creation is lazy (durable state only — the container materializes on first use), a lost container
 * is re-provisioned from the durable branch, its runtime status is surfaced, and a workspace is
 * abandoned only when the branch itself is gone.
 */
@QuarkusTest
public class WorkspaceContainerLifecycleServiceTest {

  // NOT covered here: startup reconciliation of workspaces against the live container set
  // (containerless-but-live-branch -> STOPPED, dangling-volume reaping). That logic lives in the
  // repositories context's RepositoryDiscoveryService, which walks the repositories data dir; this
  // context has no reconciler of its own to drive.

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;
  @Inject WorkspaceContainerStartedRecorder startedRecorder;
  @Inject WorkspaceContainerStoppingRecorder stoppingRecorder;
  @Inject FakeWorkspaceGitStatus gitStatus;
  @Inject FakeWorkspaceAgentActivity agentActivity;

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

  @Test
  public void createWorkspaceDoesNotProvisionAContainerUntilFirstUse() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    String container = containers.containerName("feat", repoId);

    // Creation writes only durable state: the branch ref in origin and the STOPPED row.
    assertFalse(containers.exists(container), "creation does not run a container");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        "the branch ref exists in origin at the parent's commit");
    // The runtime mirrors docker: touching the not-yet-provisioned container fails, it doesn't
    // silently run elsewhere — a use-site that forgot ensureContainer becomes a test failure.
    assertNotEquals(
        0,
        containers.exec(container, "/workspace", Map.of(), "git", "status").exitCode(),
        "exec against the never-provisioned container fails cleanly");

    // First use provisions: container up, branch checked out, status live.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.exists(container), "first use provisions the container");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/feat").trim(),
        containerHead(container),
        "the provisioned container has the branch checked out");
  }

  @Test
  public void cleanFlagIsSurfacedOnlyWhileRunningAndReported() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    // STOPPED (not provisioned): the daemon can't be connected, so clean stays unknown even if a
    // stale value were reported.
    gitStatus.report(workspaceIds.of(repoId, "feat"), false);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());
    assertNull(workspaceDto(repoId, "feat").clean(), "no clean/dirty badge while not RUNNING");

    // RUNNING + reported dirty → clean == false.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(Boolean.FALSE, workspaceDto(repoId, "feat").clean());

    // RUNNING + reported clean → clean == true.
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
    assertEquals(Boolean.TRUE, workspaceDto(repoId, "feat").clean());

    // RUNNING but the daemon hasn't reported (yet) → unknown (null), not a stale value.
    gitStatus.forget(workspaceIds.of(repoId, "feat"));
    assertNull(workspaceDto(repoId, "feat").clean(), "RUNNING but unreported ⇒ unknown");
  }

  @Test
  public void agentActivityIsSurfacedOnlyWhileRunningAndReported() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    // STOPPED: no daemon, so activity stays unknown even if a stale value were reported.
    agentActivity.report(workspaceIds.of(repoId, "feat"), AgentActivityState.BUSY);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());
    assertNull(workspaceDto(repoId, "feat").agentActivity(), "no activity while not RUNNING");

    // RUNNING + reported BUSY → surfaced.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(AgentActivityState.BUSY, workspaceDto(repoId, "feat").agentActivity());

    // A later WAITING flip is reflected.
    agentActivity.report(workspaceIds.of(repoId, "feat"), AgentActivityState.WAITING);
    assertEquals(AgentActivityState.WAITING, workspaceDto(repoId, "feat").agentActivity());

    // RUNNING but nothing reported (no active agent) → null.
    agentActivity.forget(workspaceIds.of(repoId, "feat"));
    assertNull(workspaceDto(repoId, "feat").agentActivity(), "RUNNING but unreported ⇒ null");
  }

  @Test
  public void mainWorkspaceIsCreatedLazilyToo() throws Exception {
    String repoId = clonedRepo();
    // cloneRepository created the main workspace ("master") — a row only, no container.
    String container = containers.containerName("master", repoId);
    assertFalse(containers.exists(container), "the main workspace starts without a container");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "master").runtimeStatus());

    // First use checks out the existing main branch (no branch ref to create).
    workspaceService.ensureContainer(workspaceIds.of(repoId, "master"));
    assertTrue(containers.exists(container));
    Path originPath = Path.of(dataDir, repoId, "origin");
    assertEquals(
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        containerHead(container));
  }

  @Test
  public void mergeWorkspaceSucceedsWithoutASourceContainer() throws Exception {
    String repoId = clonedRepo();
    // The seed path: fork off the fixture's 'feature' branch (which carries a commit master
    // lacks) and merge it into master — all host-side, the workspace never provisioned.
    // master is an ordinary branch here, not this repository's default one: the default branch
    // is written by integrate alone now, and its 409 would stand in front of what this test is
    // about. Repointing main is one line and leaves the merge under test byte-for-byte the same.
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feeder", "feature", "feeder", null);
    Path originPath = Path.of(dataDir, repoId, "origin");
    String masterBefore =
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim();

    var result = workspaceService.mergeWorkspace(workspaceIds.of(repoId, "feeder"), "master");

    assertFalse(result.hasConflicts(), "the host-side merge succeeds without a container");
    assertNotEquals(
        masterBefore,
        TestGit.exec(originPath.toFile(), "git", "rev-parse", "refs/heads/master").trim(),
        "origin's target ref advanced");
    assertFalse(
        containers.exists(containers.containerName("feeder", repoId)),
        "merging never provisioned a container");
  }

  @Test
  public void aNeverProvisionedWorkspaceIsCleanable() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    // A fresh fork has nothing to lose: no container means no dirty tree and no unpushed commits,
    // so cleanup must be offered exactly as for a provisioned-but-level workspace.
    assertTrue(
        workspaceService.canCleanupBranch(repoId, "feat", "master"),
        "a never-provisioned fresh fork is cleanable");
  }

  @Test
  public void ensureContainerRecreatesALostContainerFromTheBranch() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    assertTrue(containers.exists(container));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());

    // Container vanishes out-of-band; the branch — the real work — is untouched in origin.
    containers.rm(container);
    assertFalse(containers.exists(container));
    assertEquals(WorkspaceRuntimeStatus.STOPPED, workspaceDto(repoId, "feat").runtimeStatus());

    // ensureContainer re-provisions from the durable branch and the workspace stays ACTIVE.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.exists(container), "container is re-provisioned");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, dto.runtimeStatus());
  }

  @Test
  public void ensureContainerIsANoOpWhenAlreadyRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    // Should not throw and should leave the (same) container running.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.exists(containers.containerName("feat", repoId)));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
  }

  @Test
  public void ensureContainerRestartsAnExitedContainerInPlaceKeepingUnpushedWork()
      throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "unpushed.txt");

    // A host/docker restart leaves the container present but Exited (its /workspace clone intact) —
    // the exact state DockerExecutor.exists() couldn't distinguish from "running".
    ((FakeContainerRuntime) containers).markExited(container);
    assertFalse(containers.isRunning(container));
    assertTrue(containers.exists(container), "the exited container is still present");

    // ensureContainer must start it back up in place — NOT no-op on mere presence, NOT re-clone —
    // so the unpushed commit survives and the runtime status reflects the live container.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.isRunning(container), "the exited container is started back up");
    assertEquals(WorkspaceRuntimeStatus.RUNNING, workspaceDto(repoId, "feat").runtimeStatus());
    assertEquals(head, containerHead(container), "restart-in-place keeps the unpushed commit");
  }

  @Test
  public void ensureContainerAbandonsWhenTheBranchIsGone() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);

    // Both the container AND the durable branch disappear: the work no longer exists anywhere.
    containers.rm(container);
    Path originPath = Path.of(dataDir, repoId, "origin");
    TestGit.exec(originPath.toFile(), "git", "branch", "-D", "--", "feat");

    assertThrows(NotFoundException.class, () -> workspaceService.ensureContainer(workspaceIds.of(repoId, "feat")));

    // The workspace is abandoned (the only path to abandonment) and drops off the active list.
    assertFalse(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "a workspace with no branch to recreate from is abandoned");
  }


  @Test
  public void stopContainerPausesInPlaceKeepingTheWorkspaceActive() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);

    workspaceService.stopContainer(workspaceIds.of(repoId, "feat"));

    // A graceful stop PAUSES in place (docker stop), it does not remove the container: the
    // container
    // survives (present but not running) so its /workspace clone is kept for a lossless resume.
    assertTrue(containers.exists(container), "graceful stop keeps the container present");
    assertFalse(containers.isRunning(container), "graceful stop leaves it not running");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status(), "the workspace stays active");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());

    // ...and it can be brought straight back — resumed in place (start), not re-provisioned.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.isRunning(container));
  }

  @Test
  public void stopContainerPreservesUncommittedWorkingTreeChanges() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);

    // Working-tree state that is NOT a pushed commit — the exact thing the old stop (docker rm -f +
    // re-clone) silently destroyed: an untracked file. A real pause must keep it.
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo draft > scratch.txt");

    workspaceService.stopContainer(workspaceIds.of(repoId, "feat"));
    // Resumed in place (same container, not a fresh clone).
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    assertEquals(
        "draft",
        containers.exec(container, "/workspace", Map.of(), "cat", "scratch.txt").output().trim(),
        "an untracked working-tree file survives stop -> resume");
  }

  // MOVED: gracefulStopPushesUnpushedWorkSoRecreationIsLossless.
  // It asserted that stopContainer pushes committed-but-unpushed work to origin before pausing, so
  // a later recreate is lossless. The host no longer pushes: the daemon auto-pushes committed work
  // as it lands, so origin is already current by the time a stop arrives. The lossless-recreate
  // guarantee is real but is now the daemon's, and asserting it needs a live daemon -- unowned here.


  @Test
  public void deleteContainerRemovesTheContainerButKeepsBranchAndWorkspace() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    // The realistic entry point: the UI offers "Delete container" on a stopped workspace, whose
    // container is still present (docker stop, not rm).
    workspaceService.stopContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.exists(container), "a stopped container is still present");

    workspaceService.deleteContainer(workspaceIds.of(repoId, "feat"));

    // The container is torn down (docker rm)...
    assertFalse(containers.exists(container), "delete removes the container");
    // ...but the durable branch and the ACTIVE workspace row survive (unlike discard/Abandon, which
    // deletes the branch and soft-deletes the row).
    assertTrue(workspaceService.branchExists(repoId, "feat"), "the branch is kept");
    WorkspaceDto dto = workspaceDto(repoId, "feat");
    assertEquals(WorkspaceStatus.ACTIVE, dto.status(), "the workspace stays active");
    assertEquals(WorkspaceRuntimeStatus.STOPPED, dto.runtimeStatus());

    // ...and Start recreates a fresh container from the branch.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(containers.isRunning(container), "Start recreates the container from the branch");
  }

  @Test
  public void deleteContainerFiresStoppingImmediatelyBeforeRm() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    stoppingRecorder.clear();

    workspaceService.deleteContainer(workspaceIds.of(repoId, "feat"));

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), "deleteContainer fires exactly one stopping event");
    assertFalse(seen.get(0).event().graceful(), "a teardown settles bookkeeping-only (immediate)");
    assertTrue(
        seen.get(0).containerExistedWhenObserved(),
        "the stopping event fires before containers.rm");
  }

  @Test
  public void deleteContainerOnAnUnknownWorkspaceIs404() throws Exception {
    // An id no workspace has — the label is no longer what identifies one.
    clonedRepo();
    assertThrows(NotFoundException.class, () -> workspaceService.deleteContainer(-1L));
  }

  @Test
  public void unpushedWorkSurvivesAnUnexpectedlyRemovedContainerOnThePersistentVolume()
      throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "survivor.txt");

    // Unexpected death (no graceful stop, no push, no deleteContainer) — exactly the incidental
    // recreation the persistent /workspace volume is meant to survive. The commit was never pushed,
    // so its survival is proof the volume (not origin) carried it: rm keeps the volume, and the
    // next
    // ensureContainer reattaches it (the daemon skips re-clone on the populated /workspace).
    containers.rm(container);
    assertTrue(workspaceVolumeExists("feat"), "an incidental rm keeps the per-workspace volume");
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertEquals(
        head,
        containerHead(container),
        "an unpushed commit survives an unexpected container death on the persistent volume");
  }

  @Test
  public void anIdleStoppedEditorWorkspaceResumesInPlaceWithItsVolume() throws Exception {
    // THE REOPEN PATH, and the whole reason it needs no code. qits-containers' IdleSweep STOPS an
    // idle-stopped place — it does not delete it — so what the editor comes back to is the ladder's
    // second rung, which every workspace has always had: the container is present but not running,
    // and ensureContainer starts it back up where it stands rather than re-cloning. The daemon then
    // finds a populated /workspace and clones nothing.
    //
    // The workspace here is THE EDITOR'S, because that is the only workspace an idle-stop policy is
    // ever asked for — see WorkspaceContainers.lifetime. It is one row for the whole platform now,
    // so it is created by its own verb and belongs to no repository: its container carries whatever
    // its /workspace volume holds and nothing was cloned into it.
    Long rowId = workspaceService.createEditorWorkspace().id;
    workspaceService.ensureContainer(rowId);
    String container =
        containers.containerName(EditorWorkspace.WORKSPACE_ID, EditorWorkspace.REPOSITORY_ID);
    // What survives is the VOLUME's contents, and for the editor that is not a checkout: it clones
    // nothing (it belongs to no repository), so what a reader loses if the resume re-provisions is
    // whatever they left in /workspace. A file is therefore the honest witness here, where an
    // ordinary workspace's is an unpushed commit.
    containers.exec(
        container, "/workspace", Map.of(), "bash", "-lc", "echo hi > editor-scratch.txt");

    // What the sweep does: a stop, in place. The container and its volume are both still there.
    ((FakeContainerRuntime) containers).markExited(container);
    assertFalse(containers.isRunning(container));
    assertTrue(containers.exists(container), "an idle stop leaves the container present");
    assertTrue(
        workspaceVolumeExists(EditorWorkspace.WORKSPACE_ID), "and its /workspace volume");

    // Reopening the editor is one ensure — the same call the door makes.
    workspaceService.ensureContainer(rowId);
    assertTrue(containers.isRunning(container), "the stopped editor is started back up");
    assertEquals(
        "hi",
        containers
            .exec(container, "/workspace", Map.of(), "cat", "editor-scratch.txt")
            .output()
            .trim(),
        "with what was in /workspace still in it");
  }

  @Test
  public void ensureContainerFiresStartedOnFreshProvision() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    startedRecorder.clear();

    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "a fresh cold->RUNNING provision fires WorkspaceContainerStarted");
  }

  @Test
  public void ensureContainerFiresStartedOnExitedRestart() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    ((FakeContainerRuntime) containers).markExited(container);
    startedRecorder.clear();

    // Restart-in-place of an Exited container is the second cold->RUNNING transition.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "restarting an Exited container fires WorkspaceContainerStarted");
  }

  @Test
  public void ensureContainerDoesNotFireStartedWhenAlreadyRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    // Wait for the fresh provision's (async) event to land before clearing, so a late delivery of
    // it
    // can't masquerade as a second fire below.
    assertTrue(startedRecorder.awaitCount(repoId, "feat", 1, 5_000));
    startedRecorder.clear();

    // The already-running short-circuit must NOT fire — this is what terminates the auto-start
    // reentrancy loop.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    Thread.sleep(500); // give any (erroneous) async fire time to land
    assertEquals(
        0,
        startedRecorder.countFor(repoId, "feat"),
        "a no-op ensureContainer on a live container fires nothing");
  }

  @Test
  public void stopContainerFiresStoppingWhileTheContainerIsStillRunning() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    stoppingRecorder.clear();

    workspaceService.stopContainer(workspaceIds.of(repoId, "feat"));

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), "stopContainer fires exactly one stopping event");
    assertTrue(seen.get(0).event().graceful(), "a graceful stop asks for a graceful settle");
    assertTrue(
        seen.get(0).containerExistedWhenObserved(),
        "the stopping event fires before containers.stop — services settle while the container is"
            + " still present");
  }

  @Test
  public void discardFiresStoppingImmediatelyBeforeRm() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    gitStatus.report(workspaceIds.of(repoId, "feat"), true); // a discard is destructive: only an explicit CLEAN permits it
    stoppingRecorder.clear();

    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"));

    var seen = stoppingRecorder.forKey(repoId, "feat");
    assertEquals(1, seen.size(), "discard fires exactly one stopping event");
    assertFalse(seen.get(0).event().graceful(), "discard settles bookkeeping-only (immediate)");
    assertTrue(
        seen.get(0).containerExistedWhenObserved(),
        "the stopping event fires before containers.rm");
  }

  // --- Dirty-tree guards: merges/abandon are refused server-side when the working tree is dirty,
  // matching the UI that hides/reroutes those actions on a daemon-reported dirty workspace.

  // MOVED: fastForwardRefusesADirtyWorkspace / updateFromParentRefusesADirtyWorkspace.
  // Both drove WorkspaceService.fastForwardWorkspace / updateWorkspaceFromParent, which are now the
  // workspace-daemon's HTTP routes -- the git they ran was `docker exec` inside the container. The
  // dirty-tree guard they asserted still exists here (requireCleanWorkingTree, now fail-closed on
  // the daemon's report) and is covered by the abandon/integrate cases below; what is NOT covered
  // anywhere is that fast-forward and update-from-parent themselves refuse a dirty tree. That
  // assertion belongs with the routes, in qits-workspace-daemon.

  @Test
  public void integrateRefusesADirtyWorkspaceBranch() throws Exception {
    String repoId = clonedRepo();
    // master is an ordinary branch here, not this repository's default one: the default branch
    // is written by integrate alone now, and its 409 would stand in front of what this test is
    // about. Repointing main is one line and leaves the merge under test byte-for-byte the same.
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    makeDirty(containers.containerName("feat", repoId));
    gitStatus.report(workspaceIds.of(repoId, "feat"), false); // the daemon is what tells the host a tree is dirty

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> workspaceService.mergeBranch(repoId, "feat", "master", null));
    assertTrue(ex.getMessage().contains("uncommitted changes"), ex.getMessage());
  }

  @Test
  public void abandonRefusesADirtyWorkspace() throws Exception {
    String repoId = clonedRepo();
    // master is an ordinary branch here, not this repository's default one: the default branch
    // is written by integrate alone now, and its 409 would stand in front of what this test is
    // about. Repointing main is one line and leaves the merge under test byte-for-byte the same.
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    makeDirty(containers.containerName("feat", repoId));
    gitStatus.report(workspaceIds.of(repoId, "feat"), false); // the daemon is what tells the host a tree is dirty

    BadRequestException ex =
        assertThrows(
            BadRequestException.class, () -> workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat")));
    assertTrue(ex.getMessage().contains("uncommitted changes"), ex.getMessage());
    assertTrue(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "the refused abandon left the workspace in place");
  }

  /**
   * The override behind the UI's second confirmation: the same dirty workspace the guard just
   * refused is discarded when the caller spells {@code force} — the person has read what will be
   * thrown away. Everything else about the discard is the ordinary one.
   */
  @Test
  public void abandonWithForceDiscardsADirtyWorkspace() throws Exception {
    String repoId = clonedRepo();
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    makeDirty(containers.containerName("feat", repoId));
    gitStatus.report(workspaceIds.of(repoId, "feat"), false);
    Long id = workspaceIds.of(repoId, "feat");

    assertThrows(BadRequestException.class, () -> workspaceService.discardWorkspace(id));

    workspaceService.discardWorkspace(id, "abandoning half-done work on purpose", true);
    assertFalse(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "the forced abandon resolved the workspace despite the dirty tree");
  }

  @Test
  public void abandonSucceedsOnACleanWorkspace() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    gitStatus.report(workspaceIds.of(repoId, "feat"), true); // "clean" is now a daemon report, not a host git status

    // A clean working tree passes the guard: abandon proceeds and drops the workspace off the list.
    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"));
    assertFalse(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "a clean workspace is abandoned normally");
  }

  @Test
  public void beginRecreateContainerKeepsThePersistentVolumeAndItsCheckout() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    String head = commitInContainer(container, "keep.txt"); // committed ⇒ working tree clean
    // Recreate's gate admits only an explicit-clean daemon report; a committed tree qualifies.
    gitStatus.report(workspaceIds.of(repoId, "feat"), true);
    assertTrue(
        workspaceVolumeExists("feat"), "precondition: the workspace has a persistent volume");
    startedRecorder.clear();

    // An image-update recreate tears the container down and provisions a fresh one — but must KEEP
    // the volume (the core win), so the checkout is reattached, not re-cloned from scratch.
    workspaceService.beginRecreateContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(
        startedRecorder.awaitCount(repoId, "feat", 1, 5_000),
        "recreate re-provisions the container");

    assertTrue(workspaceVolumeExists("feat"), "recreate keeps the per-workspace volume");
    assertEquals(head, containerHead(container), "the checkout is preserved across the recreate");
  }

  @Test
  public void deleteContainerRemovesThePersistentVolumeSoStartReClonesFresh() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    String container = containers.containerName("feat", repoId);
    String unpushed = commitInContainer(container, "doomed.txt");
    assertTrue(
        workspaceVolumeExists("feat"), "precondition: the workspace has a persistent volume");

    // The one deliberate reset: delete-container drops the container AND its volume.
    workspaceService.deleteContainer(workspaceIds.of(repoId, "feat"));
    assertFalse(workspaceVolumeExists("feat"), "delete-container removes the persistent volume");

    // Start therefore re-creates an empty volume and re-clones a fresh checkout from origin — so
    // the
    // never-pushed commit is gone, honoring the verb's "loses uncommitted changes" contract.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(workspaceVolumeExists("feat"), "Start re-creates the volume");
    assertNotEquals(
        unpushed, containerHead(container), "the fresh clone does not carry the discarded commit");
  }

  @Test
  public void discardWorkspaceRemovesThePersistentVolume() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));
    assertTrue(
        workspaceVolumeExists("feat"), "precondition: the workspace has a persistent volume");
    gitStatus.report(workspaceIds.of(repoId, "feat"), true); // a discard is destructive: only an explicit CLEAN permits it

    // Abandon (discard) throws the work away: container + branch + volume all go.
    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"));

    assertFalse(workspaceVolumeExists("feat"), "discard removes the persistent volume");
    assertFalse(
        workspaceService.listWorkspaces(repoId).stream()
            .anyMatch(w -> "feat".equals(w.workspaceId())),
        "the workspace is abandoned");
  }


  /**
   * Whether the fake runtime is currently tracking a per-workspace volume for {@code workspaceId}.
   */
  private boolean workspaceVolumeExists(String workspaceId) {
    return containers.listWorkspaceVolumes().stream()
        .anyMatch(v -> workspaceId.equals(v.workspaceId()));
  }

  /**
   * Leaves an untracked file in the container's /workspace so `git status --porcelain` is dirty.
   */
  private void makeDirty(String container) {
    containers.exec(container, "/workspace", Map.of(), "bash", "-lc", "echo wip > uncommitted.txt");
  }

  /** Makes a commit in the container's /workspace without pushing it, returning the new HEAD. */
  private String commitInContainer(String container, String file) {
    containers.exec(
        container,
        "/workspace",
        Map.of(),
        "bash",
        "-lc",
        "echo hi > " + file + " && git add " + file + " && git commit -m local");
    return containerHead(container);
  }

  private String containerHead(String container) {
    return containers
        .exec(container, "/workspace", Map.of(), "git", "rev-parse", "HEAD")
        .output()
        .trim();
  }
}
