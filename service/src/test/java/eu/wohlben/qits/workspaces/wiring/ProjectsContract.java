package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.control.AgentWaitingReporter;
import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.control.WaitSubjectResolver;
import eu.wohlben.qits.workspaces.control.WaitSubjects;
import eu.wohlben.qits.workspaces.control.WorkFiler;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import java.time.Instant;
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
 * {@code listByProject} — and {@link HttpWorkFiler} one, {@code createWork}. The trigger tells the
 * rows apart (qits-1152):
 *
 * <ul>
 *   <li>{@code dispatchAgent} — {@code POST /workspaces/api/agent-dispatches}: {@code
 *       AgentService.dispatch} opens with {@code repositories.require(repositoryId)}, and its 404 is
 *       "No such repository. Nothing was created".
 *   <li>{@code createAgent} — {@code POST /workspaces/api/agents}, a person's door: the same {@code
 *       require}.
 *   <li>{@code workspace-daemon:agent-branch-pushed} — an {@code AgentBranchPushed} frame names the
 *       repository by its git-host name; {@code AgentService.onBranchPushed} reads the wrapper
 *       ({@code find}) and lists its project ({@code listByProject}) to map the name to an id.
 *   <li>{@code captureWork} — {@code POST /workspaces/api/capture} (hidden from openapi.yml):
 *       {@code CaptureService.capture} → {@code require(repoId)} for the project, then {@code
 *       createWork} files the ticket (D25).
 *   <li>{@code workspace-daemon:agent-waiting} — the daemon reports that the agent waits, and
 *       {@link HttpAgentWaitingReporter} relays it with {@code reportWorkAgentWaiting}. The answer
 *       is read by its status alone. {@code waitingOn} names the agent's open waits (qits-1153,
 *       W5).
 *   <li>{@code createAgentWait} — an agent declares a wait, usually mid-turn: the same call with
 *       {@code waiting: false} and the label in {@code waitingOn}, which every relay now carries
 *       whether or not the agent waits for input (qits-1460). And since epic qits-1681 the wait's
 *       subject is looked up once the wait is committed ({@link HttpWaitSubjectResolver}): a release
 *       request with {@code getProjectReleaseRequest} under the agent's project, a work item (or its
 *       comments) with {@code getWork}.
 * </ul>
 */
final class ProjectsContract {

  static final String REPOSITORY_EXISTS = "a repository exists";
  static final String NO_REPOSITORY = "no repository with the given id";
  static final String PROJECT_WITH_REPOSITORIES = "a project with 3 repositories";

  static final String GET_REPOSITORY = "getRepository";
  static final String LIST_PROJECT_REPOSITORIES = "listProjectRepositories";

  /**
   * What {@link HttpRepositoryLookup} binds of a repository: {@code ProjectsRepositories.Repository}
   * and nothing else. {@code cloneUrl}, {@code component}, {@code backupUrl} and {@code lastBackup}
   * come back too and stay out of the pact, so qits-projects may change them.
   */
  private static final List<String> REPOSITORY_FIELDS =
      List.of("id", "name", "mainBranch", "projectId", "archetype");

  /** {@code getRepository}: the one repository's fields. */
  static final List<String> READS_REPOSITORY =
      REPOSITORY_FIELDS.stream().map(f -> "$.repository." + f).toList();

  /** {@code listProjectRepositories}: the same fields of every entry; {@code wrapper} is not read. */
  static final List<String> READS_ENTRIES =
      REPOSITORY_FIELDS.stream().map(f -> "$.entries[*].repository." + f).toList();

  /** A 404 is read by its status alone. */
  static final List<String> READS_NOTHING = List.of();

  static final String NO_WORK = "a project with no work";
  static final String CREATE_WORK = "createWork";

  static final String DISPATCHED_TICKET = "a ticket with a dispatched agent";
  static final String REPORT_AGENT_WAITING = "reportWorkAgentWaiting";

  static final String REQUESTS_IN_EVERY_STATE = "a project with release requests in every state";
  static final String GET_PROJECT_RELEASE_REQUEST = "getProjectReleaseRequest";

  static final String BUG_TICKET = "a bug ticket in detail";
  static final String GET_WORK = "getWork";

  /** The clients a row drives, each built against the row's mock server. */
  record Clients(
      RepositoryLookup lookup,
      WorkFiler filer,
      AgentWaitingReporter reporter,
      WaitSubjectResolver subjects) {}

  /** What the consumer does with the clients for one row, asserting what that code path reads. */
  @FunctionalInterface
  interface Call {
    void run(Clients clients, Map<String, String> params);
  }

  /**
   * One (trigger, call), the body this consumer sends (null for a GET) and the answer's body paths
   * that call reads.
   */
  record Case(
      Trigger trigger,
      String state,
      String operationId,
      DslPart requestBody,
      List<String> consumes,
      Call call) {

    Case(Trigger trigger, String state, String operationId, List<String> consumes, Call call) {
      this(trigger, state, operationId, null, consumes, call);
    }

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }
  }

  /** {@code require}: the guard dispatch and capture open with. Reads id and main branch. */
  private static final Call REQUIRE =
      (clients, params) -> {
        RepositoryLookup lookup = clients.lookup();
        RepositoryLookup.RepositoryView view = lookup.require(params.get("repositoryId"));
        assertRecordedRepository(view);
      };

  /** {@code find}: the soft read the git-host address and the editor make. */
  private static final Call FIND =
      (clients, params) -> {
        RepositoryLookup lookup = clients.lookup();
        RepositoryLookup.RepositoryView view =
            lookup.find(params.get("repositoryId")).orElseThrow(() -> new AssertionError("empty"));
        assertRecordedRepository(view);
      };

  /** A 404 is "no such repository", and {@code require} turns it into the domain's 404. */
  private static final Call REQUIRE_UNKNOWN =
      (clients, params) -> {
        RepositoryLookup lookup = clients.lookup();
        String id = params.get("repositoryId");
        assertTrue(lookup.find(id).isEmpty(), "a 404 reads as no such repository");
        assertThrows(NotFoundException.class, () -> lookup.require(id));
      };

  /** {@code listByProject}: every entry bound, each in the asked project. */
  private static final Call LIST =
      (clients, params) -> {
        RepositoryLookup lookup = clients.lookup();
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

  private static final Call FILE =
      (clients, params) -> {
        WorkFiler.Filed filed =
            clients.filer().file(params.get("projectId"), "Captured: Greeting", "# Captured page");
        assertTrue(filed.id() != null && !filed.id().isBlank(), "the ticket is named");
      };

  /** The relay of a declared wait. The mock server checks the request; the answer is read by status. */
  private static final Call REPORT =
      (clients, params) ->
          clients
              .reporter()
              .report(params.get("ticketId"), true, "Wait", "release-request 42", "s-1", 1234L);

  /**
   * The relay of a wait declared mid-turn (qits-1460): the agent is not waiting for input, and the
   * label rides along all the same.
   */
  private static final Call REPORT_BUSY =
      (clients, params) ->
          clients
              .reporter()
              .report(params.get("ticketId"), false, "Wait", "ci-run 7", "s-1", 1234L);

  /** A wait on a release request: its id, qualified id, state and when that state began. */
  private static final Call RESOLVE_REQUEST =
      (clients, params) -> {
        WaitSubjectResolver.Resolved resolved =
            clients
                .subjects()
                .resolve(
                    WaitSubjects.Kind.RELEASE_REQUEST,
                    params.get("requestQualifiedId"),
                    params.get("projectId"))
                .orElseThrow();
        JsonNode request =
            GoldenMasters.json(REQUESTS_IN_EVERY_STATE, GET_PROJECT_RELEASE_REQUEST).path("request");
        assertEquals(request.path("id").asText(), resolved.subjectId());
        assertEquals(request.path("qualifiedId").asText(), resolved.subjectQualifiedId());
        assertEquals(request.path("state").asText(), resolved.state());
        assertEquals(Instant.parse(request.path("updatedAt").asText()), resolved.stateAt());
      };

  /** A wait on a work item: its id, qualified id, status and when it last changed. */
  private static final Call RESOLVE_ITEM =
      (clients, params) -> {
        WaitSubjectResolver.Resolved resolved =
            clients
                .subjects()
                .resolve(WaitSubjects.Kind.WORK_ITEM, params.get("qualifiedId"), null)
                .orElseThrow();
        JsonNode item = GoldenMasters.json(BUG_TICKET, GET_WORK);
        assertEquals(item.path("id").asText(), resolved.subjectId());
        assertEquals(item.path("qualifiedId").asText(), resolved.subjectQualifiedId());
        assertEquals(item.path("status").asText(), resolved.state());
        assertEquals(Instant.parse(item.path("updatedAt").asText()), resolved.stateAt());
      };

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.operation("dispatchAgent"),
              REPOSITORY_EXISTS,
              GET_REPOSITORY,
              READS_REPOSITORY,
              REQUIRE),
          new Case(
              Trigger.operation("dispatchAgent"),
              NO_REPOSITORY,
              GET_REPOSITORY,
              READS_NOTHING,
              REQUIRE_UNKNOWN),
          new Case(
              Trigger.operation("createAgent"),
              REPOSITORY_EXISTS,
              GET_REPOSITORY,
              READS_REPOSITORY,
              REQUIRE),
          // An agent's push names the repository by its name on the git host (D22): the wrapper is
          // read, and the project's repositories are listed to map the name to an id.
          new Case(
              Trigger.event("workspace-daemon:agent-branch-pushed"),
              REPOSITORY_EXISTS,
              GET_REPOSITORY,
              READS_REPOSITORY,
              FIND),
          new Case(
              Trigger.event("workspace-daemon:agent-branch-pushed"),
              PROJECT_WITH_REPOSITORIES,
              LIST_PROJECT_REPOSITORIES,
              READS_ENTRIES,
              LIST),
          new Case(
              Trigger.operation("captureWork"),
              REPOSITORY_EXISTS,
              GET_REPOSITORY,
              READS_REPOSITORY,
              REQUIRE),
          new Case(
              Trigger.operation("captureWork"),
              NO_WORK,
              CREATE_WORK,
              new PactDslJsonBody()
                  .stringValue("archetype", "TICKET")
                  .stringType("project", "00000000-0000-4000-8000-000000000001")
                  .stringType("title", "Captured: Greeting")
                  .stringType("impetus", "# Captured page")
                  .stringValue("ticketType", "IMPROVEMENT"),
              List.of("$.id", "$.qualifiedId"),
              FILE),
          new Case(
              Trigger.event("workspace-daemon:agent-waiting"),
              DISPATCHED_TICKET,
              REPORT_AGENT_WAITING,
              new PactDslJsonBody()
                  .booleanType("waiting", true)
                  .stringType("cause", "Wait")
                  .stringType("sessionId", "s-1")
                  .integerType("at", 1234L)
                  .stringType("waitingOn", "release-request 42"),
              READS_NOTHING,
              REPORT),
          new Case(
              Trigger.operation("createAgentWait"),
              DISPATCHED_TICKET,
              REPORT_AGENT_WAITING,
              new PactDslJsonBody()
                  .booleanType("waiting", false)
                  .stringType("cause", "Wait")
                  .stringType("sessionId", "s-1")
                  .integerType("at", 1234L)
                  .stringType("waitingOn", "ci-run 7"),
              READS_NOTHING,
              REPORT_BUSY),
          // Epic qits-1681: the declared wait's subject, looked up once the wait is committed.
          new Case(
              Trigger.operation("createAgentWait"),
              REQUESTS_IN_EVERY_STATE,
              GET_PROJECT_RELEASE_REQUEST,
              List.of("$.request.id", "$.request.qualifiedId", "$.request.state", "$.request.updatedAt"),
              RESOLVE_REQUEST),
          new Case(
              Trigger.operation("createAgentWait"),
              BUG_TICKET,
              GET_WORK,
              List.of("$.id", "$.qualifiedId", "$.status", "$.updatedAt"),
              RESOLVE_ITEM));

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
      GoldenMasters.interaction(
          builder,
          GoldenMasters.PROJECTS,
          c.state(),
          c.operationId(),
          c.trigger(),
          c.requestBody(),
          c.consumes());
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
