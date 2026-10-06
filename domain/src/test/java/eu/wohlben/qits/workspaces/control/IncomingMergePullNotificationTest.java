package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * A host-side integration/merge that advances a branch <em>owned by a live workspace</em> must
 * notify that workspace's daemon to pull the update into its container — otherwise the container's
 * checkout lags origin until the next host git op reactively syncs it
 * (docs/epics/qits-workspace-daemon/features/2026-07-25_daemon-bidirectional-auto-sync.md). Runs
 * against a real cloned fixture through {@link FakeContainerRuntime}; the notification itself is
 * captured by {@link FakeWorkspaceGitSync} (the real daemon send lives in {@code service}).
 */
@QuarkusTest
public class IncomingMergePullNotificationTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;
  @Inject FakeWorkspaceGitSync gitSync;

  @BeforeEach
  void clearNotifications() {
    gitSync.clear();
  }

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  @Test
  public void mergeIntoAWorkspaceBackedTargetNotifiesItsDaemon() throws Exception {
    String repoId = clonedRepo();
    // A workspace owns the target branch; integrating the fixture's 'feature' into it advances its
    // origin ref out-of-band, so its container must be told to pull.
    workspaceService.createWorkspace(repoId, "target-ws", "master", "target-branch", null);

    workspaceService.mergeBranch(repoId, "feature", "target-branch");

    assertEquals(
        1, gitSync.pulls().size(), "exactly one incoming-pull notification: " + gitSync.pulls());
    // The daemon is told which workspace by id — the label it used to carry was not one.
    assertEquals(
        workspaceIds.of(repoId, "target-ws") + " target-branch", gitSync.pulls().get(0));
  }

  @Test
  public void mergeIntoAPlainBranchNotifiesNoOne() throws Exception {
    String repoId = clonedRepo();
    // The fixture's 'feature' branch exists but no workspace owns it (a clone only creates a
    // workspace for the main branch), so integrating 'master' into it has nothing to pull — the
    // notification must not fire.
    workspaceService.mergeBranch(repoId, "master", "feature");

    assertTrue(gitSync.pulls().isEmpty(), "no workspace owns the target: " + gitSync.pulls());
  }
}
