package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The registry-only "latest agent version" badge computation ({@code WorkspaceDto.daemonOutdated}):
 * a RUNNING workspace whose daemon build is strictly older than the newest one connected anywhere
 * is flagged, ordered by build time (docs/epics/qits-workspace-registry/). The newest, a single
 * daemon, and daemons that report no build time are never flagged.
 */
@QuarkusTest
public class WorkspaceDaemonOutdatedTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceDaemonInfo daemonInfo;

  private static final Instant OLD = Instant.ofEpochSecond(1_000);
  private static final Instant NEW = Instant.ofEpochSecond(2_000);

  /**
   * A repository with a bare origin on disk and a resolvable id. Replaces the monorepo's
   * clone-the-submodule-fixture setup, which needed the repositories context and a
   * build-time fixture-derivation step, neither of which exists here.
   */
  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  /** An admin workspace, running on the direct path (admin and editor only, qits-780). */
  private WorkspaceDto runningWorkspace(String repoId, String id, String parent) {
    workspaceService.createWorkspace(repoId, id, parent, id, null, false, false, true);
    workspaceService.ensureContainer(workspaceIds.of(repoId, id));
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> id.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  private Boolean outdated(String repoId, String id) {
    return workspaceService.listWorkspaces(repoId).stream()
        .filter(w -> id.equals(w.workspaceId()))
        .findFirst()
        .orElseThrow()
        .daemonOutdated();
  }

  @Test
  public void olderDaemonIsFlaggedOutdatedAgainstTheNewest() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "old-ws", "master");
    runningWorkspace(repoId, "new-ws", "master");

    daemonInfo.report(workspaceIds.of(repoId, "old-ws"), "1.0.0", OLD);
    daemonInfo.report(workspaceIds.of(repoId, "new-ws"), "1.0.0", NEW);

    assertEquals(Boolean.TRUE, outdated(repoId, "old-ws"), "the older build is outdated");
    assertNull(outdated(repoId, "new-ws"), "the newest build is not outdated");
  }

  @Test
  public void aSingleDaemonIsNeverOutdated() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "solo", "master");
    daemonInfo.report(workspaceIds.of(repoId, "solo"), "1.0.0", OLD);

    assertNull(outdated(repoId, "solo"), "the only daemon connected is by definition the latest");
  }

  @Test
  public void aDaemonWithNoBuildTimeIsNeverOutdatedNorTheLatest() throws Exception {
    String repoId = clonedRepo();
    runningWorkspace(repoId, "timed", "master");
    runningWorkspace(repoId, "untimed", "master");

    // 'untimed' reports a version but no build time (an older image). It can't be ordered, so it
    // neither becomes "the latest" (which would wrongly flag 'timed') nor is itself flagged.
    daemonInfo.report(workspaceIds.of(repoId, "timed"), "1.0.0", OLD);
    daemonInfo.report(workspaceIds.of(repoId, "untimed"), "1.0.0", null);

    assertNull(
        outdated(repoId, "timed"), "the only build-timed daemon is the latest, not outdated");
    assertNull(outdated(repoId, "untimed"), "a daemon with no build time is never flagged");
  }

  @Test
  public void aStoppedWorkspaceCarriesNoOutdatedFlag() throws Exception {
    String repoId = clonedRepo();
    // Created but never provisioned → STOPPED, so no daemon is connected and no badge is computed,
    // even if a stale registry entry existed.
    workspaceService.createWorkspace(repoId, "stopped", "master", "stopped", null);
    daemonInfo.report(workspaceIds.of(repoId, "stopped"), "0.9.0", OLD);
    runningWorkspace(repoId, "live", "master");
    daemonInfo.report(workspaceIds.of(repoId, "live"), "1.0.0", NEW);

    assertNull(outdated(repoId, "stopped"), "no outdated badge while not RUNNING");
  }
}
