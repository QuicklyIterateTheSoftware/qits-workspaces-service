package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDto;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Verifies workspace soft-delete against a real cloned-fixture repo: cleanup/discard keeps the row
 * as history (with status, events and result) and workspace ids can be reused after resolution.
 *
 * <p>The monorepo also asserted here that a workspace which ran a command could still be discarded
 * — a command FK used to pin the row. That is structurally impossible now: commands live in another
 * context against another datasource, so no FK into workspace can exist to pin anything.
 */
@QuarkusTest
public class WorkspaceHistoryServiceTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceHistoryService workspaceHistoryService;

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

  private WorkspaceHistoryDto historyFor(String repoId, String workspaceId) {
    return workspaceHistoryService.list(repoId).stream()
        .filter(h -> workspaceId.equals(h.workspaceId()))
        .findFirst()
        .orElseThrow();
  }

  private boolean activeContains(String repoId, String workspaceId) {
    return workspaceService.listWorkspaces(repoId).stream()
        .anyMatch(w -> workspaceId.equals(w.workspaceId()));
  }

  @Test
  public void discardKeepsTheRowAsAbandonedHistory() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", "build the feature");
    assertTrue(activeContains(repoId, "feat"));

    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"), "did not work out");

    assertFalse(activeContains(repoId, "feat"), "discarded workspace leaves the active list");
    WorkspaceHistoryDetailDto detail =
        workspaceHistoryService.get(historyFor(repoId, "feat").id());
    assertEquals(WorkspaceStatus.ABANDONED, detail.status());
    assertEquals("build the feature", detail.preamble());
    assertEquals("did not work out", detail.result());
    assertEquals(WorkspacePlacement.DIRECT, detail.placement(), "where the container ran");
    assertTrue(detail.events().stream().anyMatch(e -> e.type() == WorkspaceEventType.CREATED));
    assertTrue(detail.events().stream().anyMatch(e -> e.type() == WorkspaceEventType.ABANDONED));
  }

  @Test
  public void cleanupResolvesAsIntegrated() throws Exception {
    String repoId = clonedRepo();
    // A freshly forked workspace has no commits ahead of master and a clean tree → cleanable.
    workspaceService.createWorkspace(repoId, "ff", "master", "ff", null);

    workspaceService.cleanupBranch(repoId, "ff", "merged upstream");

    assertFalse(activeContains(repoId, "ff"));
    assertEquals(WorkspaceStatus.INTEGRATED, historyFor(repoId, "ff").status());
  }

  @Test
  public void workspaceIdCanBeReusedAfterResolution() throws Exception {
    String repoId = clonedRepo();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.discardWorkspace(workspaceIds.of(repoId, "feat"), null);

    // Reuse the id — only an ACTIVE duplicate is rejected, so this succeeds.
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);

    assertTrue(activeContains(repoId, "feat"));
    long featRows =
        workspaceHistoryService.list(repoId).stream()
            .filter(h -> "feat".equals(h.workspaceId()))
            .count();
    assertEquals(2, featRows, "both the resolved and the new workspace share the id in history");
  }

}
