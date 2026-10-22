package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The agent doors qits-projects presses (qits-1152), over HTTP: a dispatch with no runner queues
 * the agent and answers it, a re-press answers the same agent, the references read finds it by its
 * work item, and delivery, entity and blocked find it by its work item too — or answer "nobody"
 * without creating anything. What the scheduler then does with the daemon is {@code
 * AgentServiceTest}'s, in the domain module.
 */
@QuarkusTest
@TestProfile(AgentDispatchControllerTest.TestProfile.class)
public class AgentDispatchControllerTest {

  /**
   * The bearer this class's profile configures. Package-private for {@link ContainerProxyRouteTest},
   * which shares the profile rather than writing a second one.
   */
  static final String TOKEN = "test-dispatch-daemon-token";

  /** The key the latched port travels on. See {@link #latchedPort()}. */
  private static final String PORT_PROPERTY = "qits.test.agent-dispatch.daemon-port";

  /**
   * A port for a fake daemon, latched in a system property so every caller in the JVM agrees on it
   * ({@link ContainerProxyRouteTest} stubs a daemon on it).
   */
  static synchronized int latchedPort() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (Exception e) {
      throw new IllegalStateException("no free port for the fake daemon", e);
    }
  }

  /** The daemon bearer is configuration fixed before boot, so it can live nowhere but a profile. */
  public static class TestProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.workspace.daemon-api-token", TOKEN,
          "qits.workspace.agent-dispatch.launch-window-ms", "20000");
    }
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject AgentWorktreeRepository agentRows;

  private static final String API = "/workspaces/api/agent-dispatches";

  private String repository() {
    String repoId = "dispatch-" + UUID.randomUUID();
    repositories.register(repoId, "main");
    return repoId;
  }

  private static Map<String, Object> dispatch(String repoId, String workId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("repositoryId", repoId);
    body.put("wrapperBranch", "ticket/" + workId);
    body.put("workId", workId);
    body.put("entityId", workId);
    body.put("entityTitle", "Fix it");
    body.put("entityStatus", "REFINED");
    body.put("entityBlocked", false);
    body.put("ticketId", workId);
    body.put("instruction", "implement");
    return body;
  }

  @Test
  void aDispatchWithNoRunnerQueuesTheAgentAndARepressAnswersIt() {
    String repoId = repository();
    String workId = "w-" + UUID.randomUUID();

    String agentId =
        given()
            .contentType(ContentType.JSON)
            .body(dispatch(repoId, workId))
            .post(API)
            .then()
            .statusCode(200)
            .body("fresh", equalTo(true))
            .body("agentLaunch", equalTo("QUEUED"))
            .body("agent.workspaceRowId", nullValue())
            .body("agent.repositoryId", equalTo(repoId))
            .body("agent.wrapperBranch", equalTo("ticket/" + workId))
            .body("agent.state", equalTo("ACTIVE"))
            .body("agent.runState", equalTo("QUEUED"))
            .extract()
            .path("agent.agentId");

    given()
        .contentType(ContentType.JSON)
        .body(dispatch(repoId, workId))
        .post(API)
        .then()
        .statusCode(200)
        .body("fresh", equalTo(false))
        .body("agent.agentId", equalTo(agentId));

    given()
        .get(API + "/references?workId=" + workId)
        .then()
        .statusCode(200)
        .body("entries", hasSize(1))
        .body("entries[0].agent.agentId", equalTo(agentId))
        .body("entries[0].agent.workId", equalTo(workId));
    given().get("/workspaces/api/agents/" + agentId).then().statusCode(200).body("agentId", equalTo(agentId));
    given()
        .get("/workspaces/api/agents?workId=" + workId)
        .then()
        .statusCode(200)
        .body("agents.agentId", contains(agentId));
  }

  @Test
  void anUnknownRepositoryIs404AndABlankWorkItemIs400() {
    given()
        .contentType(ContentType.JSON)
        .body(dispatch("no-such-" + UUID.randomUUID(), "w-x"))
        .post(API)
        .then()
        .statusCode(404);
    Map<String, Object> noWork = dispatch(repository(), "w-y");
    noWork.put("workId", " ");
    given().contentType(ContentType.JSON).body(noWork).post(API).then().statusCode(400);
  }

  @Test
  void deliveryEntityAndBlockedFindTheAgentByItsWorkItem() {
    String repoId = repository();
    String workId = "w-" + UUID.randomUUID();
    String agentId =
        given()
            .contentType(ContentType.JSON)
            .body(dispatch(repoId, workId))
            .post(API)
            .then()
            .statusCode(200)
            .extract()
            .path("agent.agentId");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "go on", "compactFirst", false))
        .post(API + "/delivery")
        .then()
        .statusCode(200)
        .body("agentId", equalTo(agentId))
        .body("launched", equalTo(true))
        .body("detail", notNullValue());

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "title", "Fix it", "status", "IMPLEMENTING", "blocked", false))
        .post(API + "/entity")
        .then()
        .statusCode(200)
        .body("agentId", equalTo(agentId))
        .body("applied", equalTo(false));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "blocked", true))
        .post(API + "/blocked")
        .then()
        .statusCode(200)
        .body("agentId", equalTo(agentId));

    given()
        .get("/workspaces/api/agents/" + agentId)
        .then()
        .body("entityStatus", equalTo("IMPLEMENTING"))
        .body("entityBlocked", equalTo(true));
  }

  @Test
  void entityAndBlockedStoreWhoSetTheBlock() {
    // qits-1511: qits-projects names the subject of an EXPLICIT (or BOTH) block in blockedBy.
    String repoId = repository();
    String workId = "w-" + UUID.randomUUID();
    String agentId =
        given()
            .contentType(ContentType.JSON)
            .body(dispatch(repoId, workId))
            .post(API)
            .then()
            .statusCode(200)
            .extract()
            .path("agent.agentId");

    Map<String, Object> entity = new LinkedHashMap<>();
    entity.put("workId", workId);
    entity.put("status", "IMPLEMENTING");
    entity.put("blocked", true);
    entity.put("blockSource", "EXPLICIT");
    entity.put("blockedBy", "subject-a");
    given().contentType(ContentType.JSON).body(entity).post(API + "/entity").then().statusCode(200);
    assertEquals("subject-a", blockAuthorOf(agentId));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "blocked", true, "blockSource", "BOTH", "blockedBy", "subject-b"))
        .post(API + "/blocked")
        .then()
        .statusCode(200)
        .body("agentId", equalTo(agentId));
    assertEquals("subject-b", blockAuthorOf(agentId));

    // A caller that names nobody: no author.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "blocked", true, "blockSource", "EXPLICIT"))
        .post(API + "/blocked")
        .then()
        .statusCode(200);
    assertNull(blockAuthorOf(agentId));
  }

  private String blockAuthorOf(String agentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> agentRows.findById(UUID.fromString(agentId)).blockAuthor);
  }

  @Test
  void anItemNobodyWorksOnIsAnsweredNotRefused() {
    String workId = "w-nobody-" + UUID.randomUUID();
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "text", "hello", "compactFirst", false))
        .post(API + "/delivery")
        .then()
        .statusCode(200)
        .body("agentId", nullValue())
        .body("delivered", equalTo(false));
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("workId", workId, "blocked", false))
        .post(API + "/entity")
        .then()
        .statusCode(200)
        .body("agentId", nullValue());
    given().get(API + "/references?workId=" + workId).then().body("entries", hasSize(0));
  }

  @Test
  void aReleasedWrapperBranchMakesTheAgentObsolete() {
    String repoId = repository();
    String workId = "w-" + UUID.randomUUID();
    String agentId =
        given()
            .contentType(ContentType.JSON)
            .body(dispatch(repoId, workId))
            .post(API)
            .then()
            .extract()
            .path("agent.agentId");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "ticket/" + workId, "target", "2026.1.1", "commit", "abc"))
        .post("/workspaces/api/branches/resolution?repositoryId=" + repoId)
        .then()
        .statusCode(200)
        .body("resolved", equalTo(true))
        .body("agentIds", contains(agentId));
    given().get("/workspaces/api/agents/" + agentId).then().body("state", equalTo("OBSOLETE"));

    // A second call finds nothing ACTIVE on it, which is the ordinary answer.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "ticket/" + workId))
        .post("/workspaces/api/branches/resolution?repositoryId=" + repoId)
        .then()
        .statusCode(200)
        .body("resolved", equalTo(false));
  }
}
