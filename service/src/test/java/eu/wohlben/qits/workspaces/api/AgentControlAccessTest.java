package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may call an agent's controls and reads (epic qits-1667, the owner's ruling on qits-1575): the
 * machine and person roles and the desk (bound to its project) everything; an agent reads any
 * agent's sessions, transcripts and active process, and yields only its own slot — no prompt,
 * interrupt, stop or restart, not even of itself.
 *
 * <p>Role × door. Most rows address a REMOVED agent, so an admitted write is answered 409 and
 * changes nothing; what matters is that it is not 401 or 403. Under {@link
 * DaemonControlSocketMachineAuthTest.GateOn}, as {@link DeskWriteAccessTest}: the desk's {@code
 * project} claim and an agent's {@code sub} are read off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class AgentControlAccessTest {

  private static final String API = "/workspaces/api/agents/";

  private static final String PROJECT = "proj-agent-control";

  private static final List<String> WRITES = List.of("prompt", "interrupt", "stop", "restart");

  @Inject FakeRepositoryLookup repositories;

  @Inject AgentWorktreeRepository agents;

  @Inject WorkspaceRepository workspaces;

  private String repoId;

  /** The agent the doors address: REMOVED, so an admitted write is a 409 and nothing more. */
  private UUID agentId;

  private String subject;

  private final List<UUID> rows = new ArrayList<>();

  private final List<Long> slots = new ArrayList<>();

  @BeforeEach
  void anAgent() {
    repoId = "agent-control-" + UUID.randomUUID();
    repositories.registerWrapper(repoId, PROJECT, "wrapper-of-" + PROJECT);
    agentId = agent(a -> a.state = AgentState.REMOVED);
    subject = subjectOf(agentId);
  }

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              rows.forEach(
                  id ->
                      agents
                          .findByIdOptional(id)
                          .ifPresent(
                              a -> {
                                a.state = AgentState.REMOVED;
                                a.removedAt = Instant.now();
                              }));
              slots.forEach(
                  id ->
                      workspaces
                          .findByIdOptional(id)
                          .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED));
            });
    rows.clear();
    slots.clear();
  }

  private UUID agent(Consumer<AgentWorktree> shape) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repoId;
              row.workId = "w-control-" + id;
              row.wrapperBranch = "ticket/w-control-" + id;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = subjectOf(id);
              shape.accept(row);
              agents.persist(row);
            });
    rows.add(id);
    return id;
  }

  private static String subjectOf(UUID agentId) {
    return "tok-workspace-agent-" + agentId;
  }

  /** A TARGET slot of the test's wrapper, made by hand: no lease runs it, so nothing starts. */
  private Long slot() {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row = new Workspace();
                  row.repositoryId = repoId;
                  row.workspaceId = "ws-" + UUID.randomUUID().toString().substring(0, 8);
                  row.status = WorkspaceStatus.ACTIVE;
                  row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  row.placement = WorkspacePlacement.TARGET;
                  workspaces.persist(row);
                  return row.id;
                });
    slots.add(id);
    return id;
  }

  private static Matcher<Integer> admitted() {
    return not(anyOf(is(401), is(403)));
  }

  private static RequestSpecification as(String subject, Set<String> roles, Map<String, String> claims) {
    return given()
        .header(
            "Authorization",
            "Bearer " + DaemonMachineTokens.tokenWithClaims(subject, roles, claims, "qits-platform"))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification as(String subject, String... roles) {
    return as(subject, Set.of(roles), Map.of());
  }

  /** Every role that acts on any agent, the desk of the agent's project among them. */
  private static List<Supplier<RequestSpecification>> everyoneButAnAgent() {
    return List.of(
        () -> as("alice", "qits:admin"),
        () -> as("tok-workspace-admin-x", "qits:admin-agent", "qits:agent"),
        () -> as("qits-projects", "qits:system"),
        () -> as("desk-admin", Set.of("qits:desk-admin"), Map.of("project", PROJECT)),
        () -> as("desk-" + PROJECT, Set.of("qits:agent", "qits:desk"), Map.of("project", PROJECT)));
  }

  private static Map<String, Object> body(String door) {
    return door.equals("prompt") ? Map.of("text", "carry on") : Map.of();
  }

  private String door(UUID agent, String door) {
    return API + agent + "/" + door;
  }

  @Test
  void anonymousIsRefusedEveryDoor() {
    for (String door : WRITES) {
      given().contentType(ContentType.JSON).body(body(door)).post(door(agentId, door)).then().statusCode(401);
    }
    given().contentType(ContentType.JSON).body(Map.of()).post(door(agentId, "yield")).then().statusCode(401);
    given().get(door(agentId, "sessions")).then().statusCode(401);
    given().get(door(agentId, "active-process")).then().statusCode(401);
  }

  @Test
  void everyoneButAnAgentPassesEveryWriteDoor() {
    for (Supplier<RequestSpecification> caller : everyoneButAnAgent()) {
      for (String door : WRITES) {
        caller.get().body(body(door)).post(door(agentId, door)).then().statusCode(admitted());
      }
      caller.get().body(Map.of()).post(door(agentId, "yield")).then().statusCode(admitted());
    }
  }

  @Test
  void anAgentPromptsInterruptsStopsAndRestartsNoAgentNotEvenItself() {
    UUID other = agent(a -> a.state = AgentState.REMOVED);
    for (String door : WRITES) {
      as(subject, "qits:agent").body(body(door)).post(door(agentId, door)).then().statusCode(403);
      as(subject, "qits:agent").body(body(door)).post(door(other, door)).then().statusCode(403);
    }
  }

  @Test
  void anAgentYieldsItsOwnSlotAndNoOtherAgents() {
    Long slot = slot();
    UUID own =
        agent(
            a -> {
              a.workspaceId = slot;
              a.runState = AgentRunState.RUNNING;
            });
    UUID other =
        agent(
            a -> {
              a.workspaceId = slot;
              a.runState = AgentRunState.RUNNING;
            });

    as(subjectOf(own), "qits:agent").body(Map.of()).post(door(other, "yield")).then().statusCode(403);
    as(subjectOf(own), "qits:agent")
        .body(Map.of())
        .post(door(own, "yield"))
        .then()
        .statusCode(200)
        .body("agentId", equalTo(own.toString()))
        .body("runState", equalTo("YIELDED"));
    // Its own, but no longer running: admitted, and refused for what it is.
    as(subjectOf(own), "qits:agent").body(Map.of()).post(door(own, "yield")).then().statusCode(409);
  }

  @Test
  void theDeskOfAnotherProjectIsRefusedEveryWrite() {
    Supplier<RequestSpecification> elsewhere =
        () ->
            as(
                "desk-elsewhere",
                Set.of("qits:agent", "qits:desk"),
                Map.of("project", "proj-elsewhere"));
    for (String door : WRITES) {
      elsewhere.get().body(body(door)).post(door(agentId, door)).then().statusCode(403);
    }
    elsewhere.get().body(Map.of()).post(door(agentId, "yield")).then().statusCode(403);
  }

  @Test
  void everyoneReadsEveryAgentsSessionsTranscriptsAndActiveProcess() {
    List<Supplier<RequestSpecification>> readers = new ArrayList<>(everyoneButAnAgent());
    readers.add(() -> as(subject, "qits:agent"));
    readers.add(() -> as("tok-workspace-agent-other", "qits:agent"));
    readers.add(
        () ->
            as(
                "desk-elsewhere",
                Set.of("qits:agent", "qits:desk"),
                Map.of("project", "proj-elsewhere")));
    for (Supplier<RequestSpecification> reader : readers) {
      reader.get().get(door(agentId, "sessions")).then().statusCode(200);
      // A session that is not the agent's answers as one that never existed.
      reader.get().get(door(agentId, "sessions/no-such/transcript")).then().statusCode(404);
      reader.get().get(door(agentId, "active-process")).then().statusCode(200);
    }
  }

  @Test
  void aRoleThatIsNoneOfThemIsRefused() {
    as("bob", "qits:reader").get(door(agentId, "sessions")).then().statusCode(403);
    as("bob", "qits:reader").body(Map.of()).post(door(agentId, "stop")).then().statusCode(403);
  }

  @Test
  void anUnknownAgentIsA404AndAQueuedOneHasNoActiveProcess() {
    String nobody = "00000000-0000-4000-8000-00000000dead";
    as("alice", "qits:admin").body(Map.of()).post(API + nobody + "/stop").then().statusCode(404);
    as("alice", "qits:admin").body(Map.of()).post(API + "not-a-uuid/stop").then().statusCode(404);
    as("alice", "qits:admin").get(API + nobody + "/sessions").then().statusCode(404);

    as("alice", "qits:admin")
        .get(door(agentId, "active-process"))
        .then()
        .statusCode(200)
        .body("technicalProcessId", nullValue());
  }

  @Test
  void aStoppedAgentStoppedAgainIsUnchanged() {
    UUID stopped = agent(a -> a.runState = AgentRunState.STOPPED);
    as("alice", "qits:admin")
        .body(Map.of())
        .post(door(stopped, "stop"))
        .then()
        .statusCode(200)
        .body("runState", equalTo("STOPPED"));
    as("alice", "qits:admin")
        .body(body("prompt"))
        .post(door(agentId, "prompt"))
        .then()
        .statusCode(409)
        .body("code", equalTo("AGENT_REMOVED"));
  }
}
