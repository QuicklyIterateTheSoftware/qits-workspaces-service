package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.security.NoDevUserProfile;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Who may declare, list and cancel an agent's waits (qits-1153, W4) — the stated exception to
 * "agents get no write doors here": {@code qits:agent} and {@code qits:admin-agent} only for the
 * agent their token belongs to (principal = the agent's token subject), {@code qits:admin} and
 * {@code qits:system} for any. Also the door's own 400s. Under {@link NoDevUserProfile}, so the
 * roles are exactly the ones a request sends.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class AgentWaitAccessTest {

  @Inject AgentWorktreeRepository agents;

  private UUID agentId;
  private String subject;

  @BeforeEach
  void anAgent() {
    agentId = UUID.randomUUID();
    subject = "tok-workspace-agent-" + agentId;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = agentId;
              row.repositoryId = "wait-access-wrapper";
              row.workId = "w-wait-access-" + agentId;
              row.wrapperBranch = "ticket/w-wait-access";
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = subject;
              agents.persist(row);
            });
  }

  private String waits() {
    return "/workspaces/api/agents/" + agentId + "/waits";
  }

  private static Map<String, Object> aWait() {
    return Map.of(
        "label",
        "release-request 42",
        "selection",
        Map.of(
            "event",
            "ReleaseRequestStateChanged",
            "when",
            List.of(
                List.of(
                    Map.of("path", "releaseRequestId", "op", "exact", "value", "42"),
                    Map.of("path", "state", "op", "exact", "value", "FINALIZED")))),
        "timeout",
        "PT2H");
  }

  private static RequestSpecification as(String user, String roles) {
    return given()
        .header("X-Qits-User", user)
        .header("X-Qits-Roles", roles)
        .contentType(ContentType.JSON);
  }

  @Test
  void anonymousIsRefused() {
    given().contentType(ContentType.JSON).body(aWait()).post(waits()).then().statusCode(401);
  }

  @Test
  void theAgentItselfMayDeclareListAndCancelItsWaits() {
    String id =
        as(subject, "qits:agent")
            .body(aWait())
            .post(waits())
            .then()
            .statusCode(201)
            .body("agentId", equalTo(agentId.toString()))
            .body("state", equalTo("OPEN"))
            .body("label", equalTo("release-request 42"))
            .body("selection.event", equalTo("ReleaseRequestStateChanged"))
            .body("selection.when[0][1].value", equalTo("FINALIZED"))
            .extract()
            .path("id");

    as(subject, "qits:agent").get(waits() + "?state=OPEN").then().statusCode(200).body("waits", hasSize(1));
    as(subject, "qits:agent")
        .get("/workspaces/api/agents/" + agentId)
        .then()
        .statusCode(200)
        .body("waitingOn", equalTo("release-request 42"));
    as(subject, "qits:agent")
        .delete(waits() + "/" + id)
        .then()
        .statusCode(200)
        .body("state", equalTo("CANCELLED"));
  }

  @Test
  void anotherAgentsCredentialIsRefusedEveryVerb() {
    String id =
        as("qits-projects", "qits:system").body(aWait()).post(waits()).then().statusCode(201).extract().path("id");

    as("tok-workspace-agent-other", "qits:agent").body(aWait()).post(waits()).then().statusCode(403);
    as("tok-workspace-agent-other", "qits:agent").get(waits()).then().statusCode(403);
    as("tok-workspace-agent-other", "qits:agent").delete(waits() + "/" + id).then().statusCode(403);
    // An admin agent is an agent here, not a person: bound like one.
    as("tok-workspace-admin-other", "qits:admin-agent,qits:agent")
        .body(aWait())
        .post(waits())
        .then()
        .statusCode(403);
  }

  @Test
  void aPersonAndThePlatformActOnAnyAgent() {
    as("alice", "qits:admin").body(aWait()).post(waits()).then().statusCode(201);
    as("qits-projects", "qits:system").get(waits()).then().statusCode(200).body("waits", hasSize(1));
  }

  @Test
  void anUnprivilegedRoleIsRefused() {
    as("bob", "qits:reader").get(waits()).then().statusCode(403);
  }

  @Test
  void anInvalidSelectionOrTimeoutIsA400WithTheReason() {
    as("alice", "qits:admin")
        .body(Map.of("selection", Map.of("event", "*")))
        .post(waits())
        .then()
        .statusCode(400)
        .body("message", containsString("Invalid selection"));
    as("alice", "qits:admin")
        .body(
            Map.of(
                "selection",
                Map.of("event", "BuildStatusChanged", "when", List.of(List.of(Map.of("path", "runId", "op", "near", "value", "1"))))))
        .post(waits())
        .then()
        .statusCode(400)
        .body("message", containsString("not an operator"));
    as("alice", "qits:admin")
        .body(Map.of("selection", Map.of("event", "BuildStatusChanged"), "timeout", "2 hours"))
        .post(waits())
        .then()
        .statusCode(400)
        .body("message", containsString("ISO-8601"));
    as("alice", "qits:admin").body(Map.of("label", "x")).post(waits()).then().statusCode(400);
  }

  @Test
  void anUnknownAgentIsA404() {
    as("alice", "qits:admin")
        .get("/workspaces/api/agents/" + UUID.randomUUID() + "/waits")
        .then()
        .statusCode(404);
  }
}
