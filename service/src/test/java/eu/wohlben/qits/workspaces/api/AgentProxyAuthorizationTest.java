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
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
 * Who may reach an agent through {@code /workspaces/agent/{agentId}/*} (epic qits-1667, "Who may
 * call what"): {@code qits:system}, {@code qits:admin} and {@code qits:admin-agent} every agent and
 * route; {@code qits:agent} its own agent only (its subject is the agent's token subject, not the
 * workspace's); {@code qits:desk} and {@code qits:desk-admin} the GET routes of every agent and no
 * socket; anyone else 403, and an anonymous caller 401.
 *
 * <p>Under the gate-on profile of {@link DaemonControlSocketMachineAuthTest}, as {@link
 * ContainerProxyAuthorizationTest}: every caller is a real RS256 bearer, and the stub daemon counts
 * what reached it, so a refusal is also an assertion that nothing was forwarded.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class AgentProxyAuthorizationTest {

  @Inject Vertx vertx;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;
  @Inject AgentWorktreeRepository agents;
  @Inject WorkspaceRepository workspaceRepository;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @ConfigProperty(name = "qits.workspace.daemon-api-token")
  String daemonToken;

  @TestHTTPResource("/")
  URI base;

  private AgentProxyFixture fixture;
  private UUID agentA;
  private UUID agentB;
  private String subjectA;
  private String subjectB;

  @BeforeEach
  void twoAgentsInOneWorkspace() throws Exception {
    fixture =
        AgentProxyFixture.start(
            daemonToken, dataDir, repositories, workspaceService, workspaceIds, agents);
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    subjectA = "tok-agent-a-" + suffix;
    subjectB = "tok-agent-b-" + suffix;
    agentA = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-a", subjectA);
    agentB = fixture.agent(fixture.rowA, AgentRunState.RUNNING, "cmd-b", subjectB);
  }

  @AfterEach
  void stopTheStubDaemon() throws Exception {
    fixture.stop();
  }

  private static String files(UUID agentId) {
    return "/workspaces/agent/" + agentId + "/files";
  }

  private static String terminal(UUID agentId) {
    return "/workspaces/agent/" + agentId + "/terminal";
  }

  private String worktreeFiles(UUID agentId) {
    return "daemon:/workspaces/container/" + fixture.rowA + "/agent-worktrees/" + agentId + "/files";
  }

  private String terminalOf(String commandId) {
    return "ws-daemon:/workspaces/container/" + fixture.rowA + "/terminal/commands/" + commandId;
  }

  private void assertForwarded(UUID agentId, String authorization) {
    given()
        .header("Authorization", authorization)
        .get(files(agentId))
        .then()
        .statusCode(200)
        .body(equalTo(worktreeFiles(agentId)));
    assertEquals(1, fixture.hits());
  }

  private void assertRefused(UUID agentId, String authorization, int status) {
    given()
        .header("Authorization", authorization)
        .get(files(agentId))
        .then()
        .statusCode(status);
    assertEquals(0, fixture.hits(), "a refused caller must reach no daemon");
  }

  private String upgrade(String path, String authorization) throws Exception {
    return AgentProxyFixture.upgrade(vertx, base, path, authorization);
  }

  @Test
  void anAnonymousRequestIs401AndReachesNoDaemon() {
    given().get(files(agentA)).then().statusCode(401);
    assertEquals(0, fixture.hits());
  }

  @Test
  void anAnonymousUpgradeIs401() throws Exception {
    assertEquals("status:401", upgrade(terminal(agentA), null));
    assertEquals(0, fixture.hits());
  }

  @Test
  void anAgentReachesItsOwnAgentsFiles() {
    assertForwarded(agentA, bearer(subjectA, "qits:agent"));
  }

  @Test
  void anAgentOpensItsOwnTerminal() throws Exception {
    assertEquals(terminalOf("cmd-a"), upgrade(terminal(agentA), bearer(subjectA, "qits:agent")));
  }

  @Test
  void anAgentDoesNotReachAnotherAgentInItsOwnWorkspace() throws Exception {
    given()
        .header("Authorization", bearer(subjectA, "qits:agent"))
        .get(files(agentB))
        .then()
        .statusCode(403)
        .body(containsString("Not permitted to reach this agent."));
    assertEquals("status:403", upgrade(terminal(agentB), bearer(subjectA, "qits:agent")));
    assertEquals(0, fixture.hits());
  }

  /** The workspace's own bound subject is not an agent's: qits-1575 calls that rule too wide. */
  @Test
  void theWorkspacesBoundSubjectDoesNotReachItsAgents() {
    String slotSubject = "tok-workspace-" + fixture.rowA + "-" + UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var row = workspaceRepository.findActiveById(fixture.rowA).orElseThrow();
              row.commissionedTokenId = "tok-id-" + fixture.rowA;
              row.commissionedTokenSubject = slotSubject;
              row.commissionedToken = "qits_tok_" + fixture.rowA;
            });

    assertRefused(agentA, bearer(slotSubject, "qits:agent"), 403);
  }

  @Test
  void anUnknownAgentIs404ForAnAgent() {
    assertRefused(UUID.randomUUID(), bearer(subjectA, "qits:agent"), 404);
  }

  @Test
  void theDeskReadsEveryAgentsGetRoutes() {
    String desk = bearer("tok-desk-" + UUID.randomUUID(), "qits:agent", "qits:desk");

    given()
        .header("Authorization", desk)
        .get("/workspaces/agent/" + agentB + "/log?tail=5")
        .then()
        .statusCode(200)
        .body(equalTo("daemon:/workspaces/container/" + fixture.rowA + "/commands/cmd-b/log?tail=5"));
    given()
        .header("Authorization", desk)
        .get("/workspaces/agent/" + agentA + "/detection")
        .then()
        .statusCode(200);
    assertEquals(2, fixture.hits());
  }

  @Test
  void theDeskAdminReadsEveryAgentsGetRoutes() {
    assertForwarded(agentB, bearer("tok-desk-admin-" + UUID.randomUUID(), "qits:desk-admin"));
  }

  @Test
  void theDeskOpensNoSocket() throws Exception {
    String desk = bearer("tok-desk-" + UUID.randomUUID(), "qits:agent", "qits:desk");

    assertEquals("status:403", upgrade(terminal(agentA), desk));
    assertEquals(
        "status:403",
        upgrade("/workspaces/agent/" + agentA + "/chat", bearer("desk-admin", "qits:desk-admin")));
    assertEquals(0, fixture.hits());
  }

  @Test
  void theDeskWritesNothing() {
    given()
        .header("Authorization", bearer("tok-desk-" + UUID.randomUUID(), "qits:agent", "qits:desk"))
        .body("x")
        .put(files(agentA) + "/README")
        .then()
        .statusCode(403);
    assertEquals(0, fixture.hits());
  }

  @Test
  void anOwnAgentMayWriteItsFiles() {
    given()
        .header("Authorization", bearer(subjectA, "qits:agent"))
        .body("x")
        .put(files(agentA) + "/README")
        .then()
        .statusCode(200);
    assertEquals("PUT", fixture.lastMethod());
  }

  @Test
  void aCiRunIs403() {
    assertRefused(agentA, bearer(subjectA, "qits:ci-run"), 403);
  }

  @Test
  void aSystemCallerReachesAnyAgent() throws Exception {
    assertForwarded(agentA, bearer("some-service", "qits:system"));
    assertEquals(terminalOf("cmd-b"), upgrade(terminal(agentB), bearer("svc", "qits:system")));
  }

  @Test
  void anAdminReachesAnyAgent() {
    assertForwarded(agentB, bearer("a-person", "qits:admin"));
  }

  @Test
  void anAdminAgentReachesAnyAgentEvenBesideTheAgentRole() throws Exception {
    String adminAgent = bearer("tok-workspace-admin-elsewhere", "qits:admin-agent", "qits:agent");
    assertForwarded(agentA, adminAgent);
    assertEquals(terminalOf("cmd-b"), upgrade(terminal(agentB), adminAgent));
  }
}
