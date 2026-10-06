package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Regression: a blank workspace parent / merge target defaults to the repository's
 * <em>configured</em> main branch ({@code Repository.mainBranch}), not a hardcoded "master". A
 * repository whose default branch is "main" (or anything non-master) failed to create a workspace
 * with {@code fatal: not a valid object name: 'master'}.
 */
@QuarkusTest
public class WorkspaceDefaultMainBranchTest {

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
    return repoId;
  }

  private String revParse(String repoId, String ref) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return TestGit.exec(originPath.toFile(), "git", "rev-parse", ref).trim();
  }

  @Test
  public void blankParentForksFromTheConfiguredMainBranch() throws Exception {
    String repoId = clonedRepo();
    // A repository whose configured main branch is NOT "master" (the fixture's 'feature' branch
    // diverges from 'master', so the fork point is distinguishable).
    repositories.setMainBranch(repoId, "feature");

    Workspace ws = workspaceService.createWorkspace(repoId, "ws", null, null, null);

    assertEquals("feature", ws.parent);
    assertEquals("ws", ws.branch);
    assertEquals(
        revParse(repoId, "refs/heads/feature"),
        revParse(repoId, "refs/heads/ws"),
        "the new branch forks from the configured main branch's tip");
    assertNotEquals(
        revParse(repoId, "refs/heads/master"),
        revParse(repoId, "refs/heads/ws"),
        "the fork point is not master");
  }

  /**
   * The other half of the same claim, now that the default branch is written by integrate alone: a
   * blank merge target still resolves to the repository's <em>configured</em> main branch rather
   * than to a hardcoded "master" — which is exactly why the merge is refused here and names
   * integrate. Asserting the refusal is asserting the resolution, and master staying untouched is
   * what says the resolution did not fall back to it.
   */
  @Test
  public void aBlankMergeTargetResolvesToTheConfiguredMainBranchAndIsRefusedThere()
      throws Exception {
    String repoId = clonedRepo();
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feeder", null, "feeder", null);
    String masterBefore = revParse(repoId, "refs/heads/master");
    String featureBefore = revParse(repoId, "refs/heads/feature");

    ConflictException refusal =
        assertThrows(
            ConflictException.class,
            () -> workspaceService.mergeWorkspace(workspaceIds.of(repoId, "feeder"), null));

    assertTrue(
        refusal.getMessage().contains("'feature'"),
        "the refusal names the CONFIGURED main branch: " + refusal.getMessage());
    assertTrue(
        refusal.getMessage().contains("/integrate"),
        "a refusal a caller cannot act on is worse than the merge: " + refusal.getMessage());
    assertEquals(featureBefore, revParse(repoId, "refs/heads/feature"), "nothing merged");
    assertEquals(
        masterBefore,
        revParse(repoId, "refs/heads/master"),
        "master is untouched — it is not this repository's main branch");
  }

  /**
   * And merging into a branch that merely <em>looks</em> like a default branch is untouched: {@code
   * master} is an ordinary branch in this repository, so the merge endpoint still serves it. The
   * rule is about the repository's own default branch, not about a name.
   */
  @Test
  public void mergingIntoANonDefaultBranchStillWorksAndRecordsIt() throws Exception {
    String repoId = clonedRepo();
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feeder", null, "feeder", null);
    String masterBefore = revParse(repoId, "refs/heads/master");

    workspaceService.mergeWorkspace(workspaceIds.of(repoId, "feeder"), "master");

    assertNotEquals(masterBefore, revParse(repoId, "refs/heads/master"), "master advanced");
    Long historyId =
        workspaceHistoryService.list(repoId).stream()
            .filter(h -> "feeder".equals(h.workspaceId()))
            .findFirst()
            .orElseThrow()
            .id();
    assertTrue(
        workspaceHistoryService.get(historyId).events().stream()
            .anyMatch(e -> e.type() == WorkspaceEventType.MERGED && "master".equals(e.target())),
        "the MERGED event names the branch that was merged into");
  }
}
