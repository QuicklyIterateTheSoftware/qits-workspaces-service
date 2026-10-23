package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/agent-dispatches/delivery} binds a {@code qits:desk} caller to its
 * own project (qits-1493): the delivery desk may prompt only the agents of the project its token's
 * {@code project} claim names, read off the item's ACTIVE agent through {@code
 * AgentService.projectOfActiveAgent}. {@code qits:admin}, {@code qits:admin-agent} and {@code
 * qits:system} stay unbound — {@link AgentDispatchDoorRolesTest} already proves the dispatch
 * door's own role list; this class is {@code deliver}'s.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, reused ({@code
 * AdminAgentAccessTest} binds its own): a desk token's {@code project} claim is only
 * readable off a validated bearer's {@code JsonWebToken} principal, and with the machine gate off
 * there is none to read it from. A person's or a peer service's role still arrives as the edge
 * asserts it, in {@code X-Qits-Roles}.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class AgentDispatchDeliveryDeskTest {

  private static final String API = "/workspaces/api/agent-dispatches";

  @Inject FakeRepositoryLookup repositories;

  @Inject AgentWorktreeRepository agents;

  private static Map<String, Object> dispatchBody(String repoId, String workId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryId", repoId);
    body.put("wrapperBranch", "ticket/" + workId);
    body.put("workId", workId);
    body.put("entityId", workId);
    return body;
  }

  /**
   * A QUEUED agent on a fresh repository of {@code project}: no runner is needed to prove the
   * binding, since {@code deliver} answers a QUEUED agent too (its first turn, once it is placed).
   */
  private String anAgentOf(String project) {
    String repoId = "desk-" + UUID.randomUUID();
    repositories.registerWrapper(repoId, project, "repo-of-" + project);
    String workId = "w-" + UUID.randomUUID();
    given()
        .header("X-Qits-User", "qits-projects")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON)
        .body(dispatchBody(repoId, workId))
        .when()
        .post(API)
        .then()
        .statusCode(200);
    return workId;
  }

  private static String deskBearer(String project) {
    return "Bearer "
        + DaemonMachineTokens.tokenWithClaims(
            "desk-" + project,
            Set.of("qits:agent", "qits:desk"),
            Map.of("project", project),
            "qits-platform");
  }

  private String pendingTextOf(String workId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> agents.findActiveByWorkId(workId).map(a -> a.pendingText).orElse(null));
  }

  @Test
  void aDeskTokenDeliversToItsOwnProjectsAgent() {
    String workId = anAgentOf("project-a");

    given()
        .header("Authorization", deskBearer("project-a"))
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "carry on", "compactFirst", false))
        .when()
        .post(API + "/delivery")
        .then()
        .statusCode(200)
        .body("agentId", notNullValue());

    assertEquals("carry on", pendingTextOf(workId));
  }

  @Test
  void aDeskTokenOfAnotherProjectIsRefusedAndDeliversNothing() {
    String workId = anAgentOf("project-b");

    given()
        .header("Authorization", deskBearer("project-a"))
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "carry on", "compactFirst", false))
        .when()
        .post(API + "/delivery")
        .then()
        .statusCode(403)
        .body("message", equalTo("The delivery desk may prompt only the agents of its own project."));

    assertNull(pendingTextOf(workId), "the mismatched desk's text was never said");
  }

  @Test
  void aPlainAgentRoleIsRefused() {
    String workId = anAgentOf("project-a");
    String bearer =
        "Bearer " + DaemonMachineTokens.tokenWithRoles("agent-x", Set.of("qits:agent"), "qits-platform");

    given()
        .header("Authorization", bearer)
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "carry on", "compactFirst", false))
        .when()
        .post(API + "/delivery")
        .then()
        .statusCode(403);

    assertNull(pendingTextOf(workId));
  }

  /** The machine role qits-projects' phase turns use, unbound to any one project. */
  @Test
  void theSystemRoleIsUnboundToAnyProject() {
    String workId = anAgentOf("project-a");

    given()
        .header("X-Qits-User", "qits-projects")
        .header("X-Qits-Roles", "qits:system")
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "carry on", "compactFirst", false))
        .when()
        .post(API + "/delivery")
        .then()
        .statusCode(200)
        .body("agentId", notNullValue());

    assertEquals("carry on", pendingTextOf(workId));
  }
}
