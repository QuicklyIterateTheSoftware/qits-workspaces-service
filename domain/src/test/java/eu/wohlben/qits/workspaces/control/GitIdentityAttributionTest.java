package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * End-to-end attribution: a configured {@code qits.git.*} identity is what both synthetic-commit
 * paths — the host-side integration merge and the container-side parent merge — author and commit
 * as. Runs against a real cloned-fixture repo through {@link FakeContainerRuntime} (which applies
 * the container-level identity env exactly like a real container), so {@code git log} verifies the
 * attribution for real.
 */
@QuarkusTest
// The identity below is pinned by the shared profile, which is also where two other classes' equally
// uncontentious overrides live — one Quarkus application rather than three (see
// SharedTestOverridesProfile). {@link #IDENTITY} must stay in step with the two values it sets.
@TestProfile(SharedTestOverridesProfile.class)
public class GitIdentityAttributionTest {

  private static final String IDENTITY = "qits-bot <qits-bot@example.com>";

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject ContainerRuntime containers;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private String clonedRepo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  /** Author and committer of the tip commit of {@code ref} in the repo's bare origin. */
  private String tipAttribution(String repoId, String ref) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return TestGit.exec(originPath.toFile(), "git", "log", "-1", "--format=%an <%ae>|%cn <%ce>", ref)
        .trim();
  }

  @Test
  public void hostSideMergeCommitsAsTheConfiguredIdentity() throws Exception {
    String repoId = clonedRepo();
    // The seed shape: fork off the fixture's 'feature' branch and integrate it into master via
    // the host-side merge (no container involved) — the path that used to depend on ambient
    // ~/.gitconfig and failed with "Committer identity unknown" in identity-less environments.
    // master is an ordinary branch here, not this repository's default one: the default branch
    // is written by integrate alone now, and its 409 would stand in front of what this test is
    // about. Repointing main is one line and leaves the merge under test byte-for-byte the same.
    repositories.setMainBranch(repoId, "feature");
    workspaceService.createWorkspace(repoId, "feeder", "feature", "feeder", null);

    workspaceService.mergeWorkspace(workspaceIds.of(repoId, "feeder"), "master");

    assertEquals(
        IDENTITY + "|" + IDENTITY,
        tipAttribution(repoId, "refs/heads/master"),
        "the host-side synthetic merge is authored and committed by the configured identity");
  }

  // MOVED: containerMergeCommitsAsTheConfiguredIdentityEvenOverStaleCloneConfig.
  // It asserted that the container-side merge commit carries the configured GIT_* identity
  // rather than a stale user.* left in the clone's .git/config -- an upgrade-path regression
  // guard. It drove updateWorkspaceFromParent, which is now a workspace-daemon route, and the
  // merge it checked runs in the daemon's own process. The host-side half
  // (hostSideMergeCommitsAsTheConfiguredIdentity, above) still covers the bare-origin merge.
  // The container-side assertion is currently unowned and belongs in qits-workspace-daemon.


  /** Commits {@code file} in the container's /workspace (identity comes from the container env). */
  private void commitFile(String container, String file) {
    containers.exec(
        container,
        "/workspace",
        Map.of(),
        "bash",
        "-lc",
        "echo hi > " + file + " && git add " + file + " && git commit -m 'add " + file + "'");
  }
}
