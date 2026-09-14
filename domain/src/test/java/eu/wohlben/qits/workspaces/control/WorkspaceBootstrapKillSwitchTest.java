package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * {@code qits.bootstrap.autorun-enabled=false} suppresses the provision-time chain — a fresh
 * provision passes straight through to service auto-start with no run recorded, even when the
 * checkout declares a chain. Manual runs stay available (not covered here; they don't consult the
 * switch by construction).
 */
@QuarkusTest
// The autorun switch this class is about lives in AutoStartOffProfile, which is where its own
// profile folded to. Auto-start being off besides is immaterial here: the assertion is on
// readyRecorder, and the runner fires that event itself on the pass-through path — whether the
// coupler then launches anything is ServiceAutoStartKillSwitchTest's question, not this one's.
@TestProfile(AutoStartOffProfile.class)
public class WorkspaceBootstrapKillSwitchTest {

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject BootstrapRunService bootstrapRunService;
  @Inject WorkspaceReadyForServicesRecorder readyRecorder;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Test
  public void killSwitchPassesFreshProvisionStraightThrough() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    // A declared chain exists (committed before the workspace fork, so the checkout carries it) —
    // the kill switch, not its absence, is what suppresses the run.
    Path origin = Path.of(dataDir, repoId, "origin");
    Path worktree = Files.createTempDirectory("qits-config-commit");
    TestGit.exec(null, "git", "clone", origin.toString(), worktree.toString());
    TestGit.exec(worktree.toFile(), "git", "config", "user.email", "t@example.com");
    TestGit.exec(worktree.toFile(), "git", "config", "user.name", "Test");
    Files.writeString(
        worktree.resolve(".qits-config.yml"),
        "version: 1\nbootstrap:\n  - name: 'install'\n    execute: 'echo hi'\n");
    TestGit.exec(worktree.toFile(), "git", "add", ".qits-config.yml");
    TestGit.exec(worktree.toFile(), "git", "commit", "-m", "stage qits config");
    TestGit.exec(worktree.toFile(), "git", "push", "origin", "HEAD:master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    readyRecorder.clear();

    workspaceService.ensureContainer(workspaceIds.of(repoId, "work"));

    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (System.currentTimeMillis() < deadline && readyRecorder.countFor(repoId, "work") == 0) {
      Thread.sleep(50);
    }
    assertTrue(
        readyRecorder.countFor(repoId, "work") >= 1,
        "the switched-off runner still releases service auto-start");
    assertTrue(
        bootstrapRunService.listForWorkspace(workspaceIds.of(repoId, "work")).isEmpty(),
        "no bootstrap command ran");
  }
}
