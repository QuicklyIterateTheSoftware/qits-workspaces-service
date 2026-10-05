package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The aggregate create form: one branch name, taken in the wrapper and in every registered
 * repository its committed {@code .gitmodules} reaches, so a workspace container can check the same
 * branch out everywhere.
 *
 * <p>What the cases here are really about is the two ways a tree can be wrong. A tree that refuses
 * <em>before</em> it starts costs nothing; a tree that breaks half way through would leave branches
 * behind, and those branches would fail the next attempt's collision check — spending the name for
 * good, on a service that cannot tell its own leftovers from somebody else's ref.
 */
@QuarkusTest
public class BranchTreeWorkspaceTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Test
  public void branchesEveryRegisteredSubmoduleAndPublishesTheGuide() throws Exception {
    String library = TestOrigin.create(dataDir);
    String wrapper = wrapperOver(library, "ghost");
    TestOrigin.recordPushOptions(dataDir, library);

    Workspace workspace =
        workspaceService.createWorkspace(
            wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true);

    assertEquals(WorkspaceStatus.ACTIVE, workspace.status);
    assertEquals("adhoc-changes", workspace.branch);
    assertTrue(TestOrigin.hasBranch(dataDir, wrapper, "adhoc-changes"), "the wrapper is branched");
    assertTrue(
        TestOrigin.hasBranch(dataDir, library, "adhoc-changes"),
        "a registered submodule is branched under the same name — the daemon looks for exactly it");
    assertTrue(
        TestOrigin.fileAtBranch(dataDir, wrapper, "adhoc-changes", "WORKSPACE.md")
            .contains("aggregate workspace"),
        "the hand-off document rides the wrapper's branch");
    String guide = TestOrigin.fileAtBranch(dataDir, wrapper, "adhoc-changes", "WORKSPACE.md");
    // The guide tells the truth on both placements (qits-817): public names and this container's
    // bearer, never a wire alias, a hand-minted pair token or a browser session.
    assertTrue(guide.contains("PROJECTS=https://projects.qits.$QITS_DOMAIN/projects/api"), guide);
    assertTrue(guide.contains("Authorization: Bearer $(qits-token qits-platform)"), guide);
    assertTrue(guide.contains("https://ci.qits.$QITS_DOMAIN/ci/api/runs/active"), guide);
    assertTrue(guide.contains("accepts this container's bearer on every service vhost"), guide);
    assertFalse(guide.contains("browser session"), guide);
    assertFalse(guide.contains("<tier>-qits-"), guide);
    assertFalse(guide.contains("QITS_GIT_AUTH_TOKEN_URL"), guide);
    assertTrue(
        TestOrigin.pushOptionsFor(dataDir, library, "refs/heads/adhoc-changes").isEmpty(),
        "a branch create is an ordinary push — CI is gated only by release requests, so there is"
            + " nothing left for a push to suppress");
  }

  /** An unregistered submodule is skipped rather than guessed at: nothing names it here. */
  @Test
  public void branchesOnlyWhatTheProjectRegisters() throws Exception {
    String library = TestOrigin.create(dataDir);
    String unregistered = TestOrigin.create(dataDir);
    String wrapper = wrapperOver(library, FakeRepositoryLookup.nameOf(unregistered));

    workspaceService.createWorkspace(
        wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true);

    assertTrue(TestOrigin.hasBranch(dataDir, library, "adhoc-changes"));
    assertFalse(
        TestOrigin.hasBranch(dataDir, unregistered, "adhoc-changes"),
        "a submodule the project does not register is not this service's to branch");
  }

  /**
   * The collision check covers the whole tree and runs before the first push, so a name already
   * taken anywhere refuses the request while it still costs nothing.
   */
  @Test
  public void refusesTheWholeTreeWhenOneRepositoryAlreadyHasTheBranch() throws Exception {
    String library = TestOrigin.create(dataDir);
    String wrapper = wrapperOver(library, "ghost");
    workspaceService.createWorkspace(library, "solo", null, "adhoc-changes", null, false);

    ConflictException refused =
        assertThrows(
            ConflictException.class,
            () ->
                workspaceService.createWorkspace(
                    wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true));

    assertEquals(409, refused.statusCode());
    assertFalse(
        TestOrigin.hasBranch(dataDir, wrapper, "adhoc-changes"),
        "a refused tree must not have started: the wrapper keeps its ref namespace clean");
  }

  /**
   * The failure this feature has to survive: the tree breaks after the first push. Everything it
   * created comes back off, and the same request then succeeds — which is the only reason the
   * collision check above can stay strict.
   */
  @Test
  public void rollsBackTheBranchesItCreatedWhenOneRepositoryRefuses() throws Exception {
    String library = TestOrigin.create(dataDir);
    String wrapper = wrapperOver(library, "ghost");
    TestOrigin.refusePushes(dataDir, library);

    assertThrows(
        RuntimeException.class,
        () ->
            workspaceService.createWorkspace(
                wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true));

    assertFalse(
        TestOrigin.hasBranch(dataDir, wrapper, "adhoc-changes"),
        "the wrapper branch was created before the failure and has to be taken back");
    assertFalse(TestOrigin.hasBranch(dataDir, library, "adhoc-changes"));

    TestOrigin.acceptPushes(dataDir, library);
    Workspace retried =
        workspaceService.createWorkspace(
            wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true);

    assertEquals("adhoc-changes", retried.branch);
    assertTrue(TestOrigin.hasBranch(dataDir, library, "adhoc-changes"));
  }

  /**
   * The fork point may already carry the guide verbatim — a previous workspace released it into the
   * parent branch. A clean index is then nothing to publish, not a failure; before this case the
   * whole creation died on {@code git commit} refusing an empty commit.
   */
  @Test
  public void createsTheTreeWhenTheParentAlreadyCarriesTheGuide() throws Exception {
    String library = TestOrigin.create(dataDir);
    String wrapper = wrapperOver(library, "ghost");
    TestOrigin.commitOnBranch(
        dataDir,
        wrapper,
        "master",
        "WORKSPACE.md",
        WorkspaceService.WORKSPACE_GUIDE,
        "docs: add workspace development flow");

    Workspace workspace =
        workspaceService.createWorkspace(
            wrapper, "adhoc-changes", null, "adhoc-changes", null, false, true);

    assertEquals(WorkspaceStatus.ACTIVE, workspace.status);
    assertTrue(TestOrigin.hasBranch(dataDir, wrapper, "adhoc-changes"));
    assertTrue(TestOrigin.hasBranch(dataDir, library, "adhoc-changes"));
    assertTrue(
        TestOrigin.fileAtBranch(dataDir, wrapper, "adhoc-changes", "WORKSPACE.md")
            .contains("aggregate workspace"),
        "the guide the parent already carried still rides the workspace branch");
  }

  /** Adoption is a single-repository idea: there is no existing tree to adopt. */
  @Test
  public void refusesToAdoptAnExistingBranch() throws Exception {
    String wrapper = TestOrigin.create(dataDir);
    repositories.register(wrapper);

    assertThrows(
        RuntimeException.class,
        () ->
            workspaceService.createWorkspace(
                wrapper, "adhoc-changes", null, "adhoc-changes", null, true, true));
  }

  /**
   * A wrapper origin whose {@code master} carries a {@code .gitmodules} naming {@code library} by
   * the name the project registers it under, plus one more entry by the given name — the second is
   * how a submodule this service must not touch gets into the fixture.
   */
  private String wrapperOver(String library, String strangerName) throws Exception {
    String wrapper = TestOrigin.create(dataDir);
    repositories.register(wrapper);
    repositories.register(library);
    TestOrigin.commitOnBranch(
        dataDir,
        wrapper,
        "master",
        ".gitmodules",
        """
        [submodule "library"]
        \tpath = libs/library
        \turl = ../%s.git
        [submodule "stranger"]
        \tpath = libs/stranger
        \turl = ../%s.git
        """
            .formatted(FakeRepositoryLookup.nameOf(library), strangerName),
        "chore: register the submodules");
    return wrapper;
  }
}
