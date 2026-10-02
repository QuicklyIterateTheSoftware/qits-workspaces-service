package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-workspaces asks qits-projects, and why</b> — the one table both {@code
 * ProjectsConsumerPactTest} (each row against a pact mock server) and {@code ProjectsPactFileTest}
 * (the committed {@code pacts/qits-workspaces-service_qits-projects-service.json}) are built
 * from, so the file and the verified behaviour cannot drift apart.
 *
 * <p><b>One row per (trigger, call).</b> {@link HttpRepositoryLookup} makes two calls — {@code
 * getRepository} behind {@code find}/{@code require} and {@code listProjectRepositories} behind
 * {@code listByProject} — and several entry points reach each. The trigger is what tells those
 * rows apart, and each is the workspaces door a caller actually presses, traced down from the
 * {@link RepositoryLookup} call site:
 *
 * <ul>
 *   <li>{@code dispatchAgent} — {@code POST /workspaces/api/agent-dispatches}: {@code
 *       DispatchService.dispatch} opens with {@code repositories.require(repositoryId)}, and its
 *       documented 404 is "No such repository. Nothing was created" — so the {@code no repository
 *       with the given id} row hangs here. With {@code branchTree} it goes on to {@code
 *       WorkspaceService.createBranchTree → submoduleClosure → listByProject}.
 *   <li>{@code captureWorkspace} — {@code POST /workspaces/api/capture} ({@code CaptureResource},
 *       hidden from openapi.yml but carrying the operationId): {@code CaptureService.capture} →
 *       {@code require(repoId)}.
 *   <li>{@code mergeWorkspace} — {@code POST /workspaces/api/workspaces/{id}/merge}: {@code
 *       WorkspaceService.mergeIntoTarget → RepoMirror.refreshNow → GitHostAddress.fetchUrl}, which
 *       is {@code ConfiguredGitHostAddress.resolve → find(repoId)} for the {@code (projectId,
 *       name)} the git host addresses a repository by.
 *   <li>{@code createWorkspace} — {@code POST /workspaces/api/workspaces}: {@code recordWorkspace}
 *       writes the row and calls {@code GitRefScopes.narrowFor}, whose {@code sameProject} asks
 *       {@code find(otherRepoId)} for every other open workspace that may push the new branch.
 *   <li>{@code ensureEditor} — {@code POST /workspaces/api/editor/ensure}: the editor container's
 *       spec carries {@code EditorProjects.composed()}, which {@code PersistedEditorProjects} builds
 *       with {@code find} (each active workspace's project) and {@code listByProject} (that
 *       project's wrapper).
 * </ul>
 *
 * <p>Every trigger is an {@code operation}: no event consumer or scheduled job in this service
 * reaches {@link RepositoryLookup} today (the two {@code @Scheduled} methods — the agent-activity
 * sweep and the commission reconcile — do not).
 */
final class ProjectsContract {

  static final String REPOSITORY_EXISTS = "a repository exists";
  static final String NO_REPOSITORY = "no repository with the given id";
  static final String PROJECT_WITH_REPOSITORIES = "a project with 3 repositories";

  static final String GET_REPOSITORY = "getRepository";
  static final String LIST_PROJECT_REPOSITORIES = "listProjectRepositories";

  /** What the consumer does with the lookup for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(RepositoryLookup lookup, Map<String, String> params);
  }

  /** One (trigger, call). */
  record Case(Trigger trigger, String state, String operationId, Call call) {
    String description() {
      return GoldenMasters.description(operationId, trigger);
    }
  }

  /** {@code require}: the guard dispatch and capture open with. Reads id and main branch. */
  private static final Call REQUIRE =
      (lookup, params) -> {
        RepositoryLookup.RepositoryView view = lookup.require(params.get("repositoryId"));
        assertRecordedRepository(view);
      };

  /** {@code find}: the soft read the git-host address, the ref narrowing and the editor make. */
  private static final Call FIND =
      (lookup, params) -> {
        RepositoryLookup.RepositoryView view =
            lookup.find(params.get("repositoryId")).orElseThrow(() -> new AssertionError("empty"));
        assertRecordedRepository(view);
      };

  /** A 404 is "no such repository", and {@code require} turns it into the domain's 404. */
  private static final Call REQUIRE_UNKNOWN =
      (lookup, params) -> {
        String id = params.get("repositoryId");
        assertTrue(lookup.find(id).isEmpty(), "a 404 reads as no such repository");
        assertThrows(NotFoundException.class, () -> lookup.require(id));
      };

  /** {@code listByProject}: every entry bound, each in the asked project. */
  private static final Call LIST =
      (lookup, params) -> {
        String projectId = params.get("projectId");
        List<RepositoryLookup.RepositoryView> listed = lookup.listByProject(projectId);
        JsonNode recorded = GoldenMasters.json(PROJECT_WITH_REPOSITORIES, LIST_PROJECT_REPOSITORIES);
        assertEquals(recorded.path("entries").size(), listed.size(), "one view per entry");
        for (RepositoryLookup.RepositoryView view : listed) {
          assertEquals(projectId, view.projectId());
          assertTrue(view.id() != null && !view.id().isBlank(), "every entry carries an id");
          assertTrue(view.name() != null && !view.name().isBlank(), "every entry carries a name");
        }
      };

  static final List<Case> CASES =
      List.of(
          new Case(Trigger.operation("dispatchAgent"), REPOSITORY_EXISTS, GET_REPOSITORY, REQUIRE),
          new Case(Trigger.operation("dispatchAgent"), NO_REPOSITORY, GET_REPOSITORY, REQUIRE_UNKNOWN),
          new Case(
              Trigger.operation("dispatchAgent"),
              PROJECT_WITH_REPOSITORIES,
              LIST_PROJECT_REPOSITORIES,
              LIST),
          new Case(Trigger.operation("captureWorkspace"), REPOSITORY_EXISTS, GET_REPOSITORY, REQUIRE),
          new Case(Trigger.operation("mergeWorkspace"), REPOSITORY_EXISTS, GET_REPOSITORY, FIND),
          new Case(Trigger.operation("createWorkspace"), REPOSITORY_EXISTS, GET_REPOSITORY, FIND),
          new Case(Trigger.operation("ensureEditor"), REPOSITORY_EXISTS, GET_REPOSITORY, FIND),
          new Case(
              Trigger.operation("ensureEditor"),
              PROJECT_WITH_REPOSITORIES,
              LIST_PROJECT_REPOSITORIES,
              LIST));

  private ProjectsContract() {}

  /** The whole contract as one V4 pact, interactions in table order (the file test sorts). */
  static V4Pact pact() {
    return pact(CASES);
  }

  /** A pact holding only {@code cases} — one mock server per row, see the pact test. */
  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, GoldenMasters.PROVIDER, PactSpecVersion.V4);
    for (Case c : cases) {
      GoldenMasters.interaction(builder, c.state(), c.operationId(), c.trigger());
    }
    return builder.toPact();
  }

  /** The view carries what the recording says the repository is. */
  private static void assertRecordedRepository(RepositoryLookup.RepositoryView view) {
    JsonNode repository = GoldenMasters.json(REPOSITORY_EXISTS, GET_REPOSITORY).path("repository");
    assertEquals(repository.path("id").asText(), view.id());
    assertEquals(repository.path("name").asText(), view.name());
    assertEquals(repository.path("projectId").asText(), view.projectId());
    assertEquals(repository.path("mainBranch").asText(), view.mainBranch());
    assertEquals(repository.path("archetype").asText(), view.archetype());
  }
}
