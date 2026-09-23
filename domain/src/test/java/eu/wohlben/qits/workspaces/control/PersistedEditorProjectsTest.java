package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The list the one shared editor clones: every project's wrapper, derived from the repositories this
 * platform is worked in.
 *
 * <p>Deliberately a {@code @QuarkusTest} against the real database and the real {@link
 * RepositoryLookup} fake, because <b>the hops between them are the whole feature</b> — the workspace
 * table names repositories, the registry turns a repository into a project, and only the registry
 * can say which of a project's repositories is its wrapper. A unit test over a stubbed composer
 * would assert the join this class exists to make.
 */
@QuarkusTest
public class PersistedEditorProjectsTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaces;
  @Inject EditorProjects editorProjects;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /**
   * One database for the module's suite, so a class about "every project on the platform" has to
   * start from a platform with nothing on it. Every ACTIVE row is resolved and the registry emptied.
   */
  @BeforeEach
  void anEmptyPlatform() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .find("status = ?1", WorkspaceStatus.ACTIVE)
                    .<Workspace>list()
                    .forEach(
                        row -> {
                          row.status = WorkspaceStatus.ABANDONED;
                          row.resolvedAt = Instant.now();
                        }));
    repositories.clear();
  }

  @Test
  void everyProjectsWrapperIsNamedOnce() throws Exception {
    // THE CLAIM THE FEATURE IS. Two projects, each with a wrapper and a submodule beside it, and a
    // workspace open on the SUBMODULE rather than on the wrapper — which is the ordinary case, and
    // the one a naive "the repositories with workspaces are the list" would get wrong. What comes
    // out is the two WRAPPERS, because a wrapper is what the editor checks out.
    String alphaWrapper = TestOrigin.create(dataDir);
    String alphaLib = TestOrigin.create(dataDir);
    String betaWrapper = TestOrigin.create(dataDir);
    repositories.registerWrapper(alphaWrapper, "alpha", "alpha-alpha");
    repositories.registerInProject(alphaLib, "alpha");
    repositories.registerWrapper(betaWrapper, "beta", "beta-beta");

    workspaceService.createWorkspace(alphaLib, "w1", "master", "task/one", null);
    workspaceService.createWorkspace(betaWrapper, "w2", "master", "task/two", null);

    assertEquals(List.of("alpha/alpha-alpha", "beta/beta-beta"), editorProjects.wrappers());
    assertEquals("alpha/alpha-alpha,beta/beta-beta", editorProjects.composed());
  }

  @Test
  void aProjectIsNamedOnceHoweverManyWorkspacesItHas() throws Exception {
    // The entry is per PROJECT, not per workspace and not per repository: an estate where five
    // people are working in one project must not tell the daemon to clone its wrapper five times.
    String wrapper = TestOrigin.create(dataDir);
    String lib = TestOrigin.create(dataDir);
    repositories.registerWrapper(wrapper, "alpha", "alpha-alpha");
    repositories.registerInProject(lib, "alpha");

    workspaceService.createWorkspace(wrapper, "w1", "master", "task/one", null);
    workspaceService.createWorkspace(wrapper, "w2", "master", "task/two", null);
    workspaceService.createWorkspace(lib, "w3", "master", "task/three", null);

    assertEquals(List.of("alpha/alpha-alpha"), editorProjects.wrappers());
  }

  @Test
  void aWorkspaceOnANONRootBranchStillPutsItsProjectIn() throws Exception {
    // THE REGRESSION THIS QUERY WAS WIDENED FOR. It used to read `parent is null`, from when the
    // per-project editor wrote one such row per project through createMainWorkspace. That door is
    // gone and createWorkspace ALWAYS sets a parent, so the narrow form would answer empty on a live
    // platform and the editor would clone nothing — the exact failure this feature exists to avoid.
    String wrapper = TestOrigin.create(dataDir);
    repositories.registerWrapper(wrapper, "alpha", "alpha-alpha");

    Workspace parent =
        workspaceService.createWorkspace(wrapper, "epic", "master", "epic/thing", null);
    workspaceService.createWorkspace(wrapper, "task", "epic/thing", "task/thing", parent.workspaceId);

    assertFalse(editorProjects.wrappers().isEmpty(), "a parented workspace names its project too");
    assertEquals(List.of("alpha/alpha-alpha"), editorProjects.wrappers());
  }

  @Test
  void aProjectWhoseWrapperTheRegistryDoesNotNameIsLeftOut() throws Exception {
    // The archetype is the whole test, and a project that answers with no wrapper simply is not in
    // the list. Deriving the name a wrapper carries (<slug>-<slug>) is what the PER-PROJECT editor
    // did, and it is a convention this context is not entitled to re-derive.
    String lib = TestOrigin.create(dataDir);
    repositories.registerInProject(lib, "alpha");

    workspaceService.createWorkspace(lib, "w1", "master", "task/one", null);

    assertEquals(List.of(), editorProjects.wrappers());
    assertEquals("", editorProjects.composed());
  }

  @Test
  void aRegistryThatCannotBeAskedCostsTheListAndNotTheEnsure() throws Exception {
    // The standing reading: an unreachable registry costs a label, never a workspace. Here it costs
    // the clones. Throwing instead would take the editor away from everybody on the platform the
    // moment qits-projects blinked, which is worse than an editor that comes up and re-clones on the
    // next press — the trade PersistedEditorProjects records, asserted rather than only written down.
    String wrapper = TestOrigin.create(dataDir);
    repositories.registerWrapper(wrapper, "alpha", "alpha-alpha");
    workspaceService.createWorkspace(wrapper, "w1", "master", "task/one", null);

    repositories.findOutage(true);
    try {
      assertEquals(List.of(), editorProjects.wrappers(), "nothing named, and nothing thrown");
    } finally {
      repositories.findOutage(false);
    }

    assertEquals(
        List.of("alpha/alpha-alpha"),
        editorProjects.wrappers(),
        "and it composes again the moment the registry answers");
  }

  @Test
  void theEditorsOwnRowNamesNoProject() {
    // Its repository_id is a SENTINEL that resolves to nothing, so asking about it would be a
    // registry round trip per ensure to learn that. Excluded in the query, which is also what stops
    // the editor from appearing in its own clone list.
    workspaceService.createEditorWorkspace();

    assertEquals(List.of(), editorProjects.wrappers());
    assertTrue(
        QuarkusTransaction.requiringNew().call(() -> workspaces.activeRepositoryIds()).isEmpty(),
        "the sentinel never reaches the registry");
  }

  @Test
  void aResolvedWorkspaceStopsNamingItsProject() throws Exception {
    // The list follows what the platform is worked in NOW. A project whose last workspace resolved
    // drops out on the next container recreate, which is the same mechanism by which a new project
    // appears — there is no polling and no watch, deliberately.
    String wrapper = TestOrigin.create(dataDir);
    repositories.registerWrapper(wrapper, "alpha", "alpha-alpha");
    Workspace only = workspaceService.createWorkspace(wrapper, "w1", "master", "task/one", null);
    assertEquals(List.of("alpha/alpha-alpha"), editorProjects.wrappers());

    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .findActiveById(only.id)
                    .ifPresent(
                        row -> {
                          row.status = WorkspaceStatus.ABANDONED;
                          row.resolvedAt = Instant.now();
                        }));

    assertEquals(List.of(), editorProjects.wrappers());
  }
}
