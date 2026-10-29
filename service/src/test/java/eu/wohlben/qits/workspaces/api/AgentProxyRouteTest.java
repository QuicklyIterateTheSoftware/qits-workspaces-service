package eu.wohlben.qits.workspaces.api;

import static eu.wohlben.qits.workspaces.api.AgentProxyFixture.bearer;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The agent proxy's path map (epic qits-1667): {@code /workspaces/agent/{agentId}/…} reaches the
 * daemon of the agent's CURRENT workspace, on the daemon path each sub-route maps to — asserted at
 * the stub daemon, which echoes the URI it was called on — and every 404 and 409 it answers instead.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@link
 * ContainerProxyAuthorizationTest}, so every caller is a real bearer; here it is always {@code
 * qits:admin}, and {@link AgentProxyAuthorizationTest} is about who else gets through.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class AgentProxyRouteTest {

  private static final String ADMIN = bearer("a-person", "qits:admin");

  @Inject Vertx vertx;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;
  @Inject AgentWorktreeRepository agents;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @ConfigProperty(name = "qits.workspace.daemon-api-token")
  String daemonToken;

  @TestHTTPResource("/")
  URI base;

  private AgentProxyFixture fixture;

  @BeforeEach
  void stubDaemonAndTwoWorkspaces() throws Exception {
    fixture =
        AgentProxyFixture.start(
            daemonToken, dataDir, repositories, workspaceService, workspaceIds, agents);
  }

  @AfterEach
  void stopTheStubDaemon() throws Exception {
    fixture.stop();
  }

  private static String agentPath(UUID agentId, String rest) {
    return "/workspaces/agent/" + agentId + "/" + rest;
  }

  private String daemonBase(Long rowId) {
    return "/workspaces/container/" + rowId + "/";
  }

  private void assertMapped(UUID agentId, String rest, String daemonUri) {
    given()
        .header("Authorization", ADMIN)
        .urlEncodingEnabled(false)
        .get(agentPath(agentId, rest))
        .then()
        .statusCode(200)
        .body(equalTo("daemon:" + daemonUri));
  }

  private void assertAnswered(UUID agentId, String rest, int status, String message) {
    given()
        .header("Authorization", ADMIN)
        .get(agentPath(agentId, rest))
        .then()
        .statusCode(status)
        .body(containsString(message));
  }

  private String upgrade(UUID agentId, String rest) throws Exception {
    return AgentProxyFixture.upgrade(vertx, base, agentPath(agentId, rest), ADMIN);
  }

  @Test
  void theTerminalIsTheRunningCommandsTerminal() throws Exception {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-1", null);

    assertEquals(
        "ws-daemon:" + daemonBase(fixture.rowA) + "terminal/commands/cmd-1",
        upgrade(agent, "terminal"));
  }

  @Test
  void theChatIsTheRunningCommandsChat() throws Exception {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-2", null);

    assertEquals(
        "ws-daemon:" + daemonBase(fixture.rowA) + "chat/commands/cmd-2", upgrade(agent, "chat"));
  }

  @Test
  void theLogIsTheCommandsLogWithItsQuery() {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-3", null);

    assertMapped(agent, "log?tail=100", daemonBase(fixture.rowA) + "commands/cmd-3/log?tail=100");
    assertEquals("Bearer " + daemonToken, fixture.lastAuthorization(), "qits' own bearer");
  }

  @Test
  void aStoppedOrYieldedAgentsLastLogIsStillReadable() {
    UUID stopped = fixture.agent(fixture.rowA, AgentRunState.STOPPED, "cmd-4", null);
    UUID yielded = fixture.agent(fixture.rowA, AgentRunState.YIELDED, "cmd-5", null);

    assertMapped(stopped, "log", daemonBase(fixture.rowA) + "commands/cmd-4/log");
    assertMapped(yielded, "log", daemonBase(fixture.rowA) + "commands/cmd-5/log");
  }

  @Test
  void theWorktreeRoutesAreTheAgentsWorktree() {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.YIELDED, null, null);
    String worktree = daemonBase(fixture.rowA) + "agent-worktrees/" + agent + "/";

    assertMapped(agent, "files", worktree + "files");
    assertMapped(agent, "files/src/Main.java?ref=head", worktree + "files/src/Main.java?ref=head");
    assertMapped(agent, "files?path=README", worktree + "files?path=README");
    assertMapped(agent, "detection", worktree + "detection");
    assertMapped(agent, "component-map", worktree + "component-map");
  }

  @Test
  void aPromptRefinementIsTheContainersHarnessWhateverTheAgentsRunState() {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.YIELDED, null, null);

    given()
        .header("Authorization", ADMIN)
        .contentType("application/json")
        .body("{\"text\":\"tidy this\"}")
        .post(agentPath(agent, "prompt-refinements"))
        .then()
        .statusCode(200)
        .body(equalTo("daemon:" + daemonBase(fixture.rowA) + "prompt-refinements"));
  }

  @Test
  void aCallerSuppliedAuthorizationNeverReachesTheDaemon() {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-6", null);

    given().header("Authorization", ADMIN).get(agentPath(agent, "detection")).then().statusCode(200);

    assertEquals("Bearer " + daemonToken, fixture.lastAuthorization());
  }

  @Test
  void anAgentMovedToAnotherWorkspaceIsReachedThere() {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-7", null);
    assertMapped(agent, "files", daemonBase(fixture.rowA) + "agent-worktrees/" + agent + "/files");

    fixture.moveTo(agent, fixture.rowB);

    // The same caller, the same address: the route follows the agent's row.
    assertMapped(agent, "files", daemonBase(fixture.rowB) + "agent-worktrees/" + agent + "/files");
  }

  @Test
  void anythingElseIsNoSuchAgentRoute() throws Exception {
    UUID agent = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-8", null);

    assertAnswered(agent, "bogus", 404, "No such agent route");
    assertAnswered(agent, "", 404, "No such agent route");
    assertAnswered(agent, "detection/extra", 404, "No such agent route");
    // The WebSocket routes are upgrades only, and the HTTP routes are never upgraded.
    assertAnswered(agent, "terminal", 404, "No such agent route");
    assertEquals("status:404", upgrade(agent, "files"));
    given()
        .header("Authorization", ADMIN)
        .get("/workspaces/agent/" + agent)
        .then()
        .statusCode(404)
        .body(containsString("No such agent route"));
    assertEquals(0, fixture.hits(), "none of these may reach a daemon");
  }

  @Test
  void unknownNonUuidAndRemovedAgentsAre404() {
    UUID removed = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-9", null);
    fixture.remove(removed);

    assertAnswered(UUID.randomUUID(), "files", 404, "No agent here.");
    assertAnswered(removed, "files", 404, "No agent here.");
    given()
        .header("Authorization", ADMIN)
        .get("/workspaces/agent/not-a-uuid/files")
        .then()
        .statusCode(404);
    given().header("Authorization", ADMIN).get("/workspaces/agent/").then().statusCode(404);
    given().header("Authorization", ADMIN).get("/workspaces/agent").then().statusCode(404);
    assertEquals(0, fixture.hits());
  }

  @Test
  void aQueuedAgentIsWaitingForAWorkspace() throws Exception {
    UUID queued = fixture.agent(null, AgentRunState.QUEUED, null, null);

    assertAnswered(queued, "files", 409, "The agent is waiting for a workspace");
    assertAnswered(queued, "log", 409, "The agent is waiting for a workspace");
    assertEquals("status:409", upgrade(queued, "terminal"));
    assertEquals(0, fixture.hits());
  }

  @Test
  void theSocketsNeedARunningSession() throws Exception {
    UUID yielded = fixture.agent(fixture.rowA, AgentRunState.YIELDED, "cmd-10", null);
    UUID stopped = fixture.agent(fixture.rowA, AgentRunState.STOPPED, "cmd-11", null);
    UUID unstarted = fixture.agent(fixture.rowA, AgentRunState.RUNNING, null, null);

    assertEquals("status:409", upgrade(yielded, "terminal"));
    assertEquals("status:409", upgrade(stopped, "chat"));
    assertEquals("status:409", upgrade(unstarted, "terminal"));
    // A log needs a command too, whatever the run state.
    assertAnswered(unstarted, "log", 409, "The agent has no running session (runState RUNNING)");
    assertEquals(0, fixture.hits());
  }

  @Test
  void theRefusalNamesTheRunState() {
    UUID stopped = fixture.agent(null, AgentRunState.STOPPED, null, null);

    // Stopped while queued: no workspace, and no session either.
    assertAnswered(stopped, "log", 409, "The agent is waiting for a workspace");
    given()
        .header("Authorization", ADMIN)
        .header("Upgrade", "websocket")
        .header("Connection", "Upgrade")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
        .get(agentPath(stopped, "terminal"))
        .then()
        .statusCode(409)
        .body(containsString("The agent has no running session (runState STOPPED)"));
  }
}
