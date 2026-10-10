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
    return repoId;
  }

  /** Author and committer of the tip commit of {@code ref} in the repo's bare origin. */
  private String tipAttribution(String repoId, String ref) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return TestGit.exec(originPath.toFile(), "git", "log", "-1", "--format=%an <%ae>|%cn <%ce>", ref)
        .trim();
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
