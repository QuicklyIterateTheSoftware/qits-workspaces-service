package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import eu.wohlben.qits.workspaces.security.NoDevUserProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:admin-agent} is admitted wherever {@code qits:admin} is (qits-628 follow-up, owner's
 * request 2026-10-07). qits-idp issues it, beside {@code qits:agent}, to an ADMIN workspace's
 * container credential. Every caller here holds {@code qits:admin-agent} ALONE — no {@code
 * qits:admin}, no {@code qits:agent} — so a pass proves the role is named at the door rather than
 * inherited from another; and the same writes stay a 403 for {@code qits:agent} alone, so the
 * widening reached the admin-agent and nobody else.
 *
 * <p>The ids and repositories name nothing, so an admitted write answers 400/404 — what matters is
 * that it is not 401 or 403. Under {@link NoDevUserProfile}, so the roles are exactly the ones the
 * request sends. When a door is narrowed back to people only, its line here moves to the refused
 * side.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class AdminAgentAccessTest {

  private static final String NO_ROW = "999999999";

  /** An agent id nobody holds: past the role gate it is a 404. */
  private static final String NO_AGENT = "00000000-0000-4000-8000-00000000dead";

  private static Matcher<Integer> admitted() {
    return not(anyOf(is(401), is(403)));
  }

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "admin-agent-1").header("X-Qits-Roles", role);
  }

  private static RequestSpecification asAdminAgent() {
    return as("qits:admin-agent");
  }

  private static RequestSpecification asAgent() {
    return as("qits:agent");
  }

  /** The representative admin write: a person's agent for a work item, AgentController's class role. */
  @Test
  void anAdminAgentCreatesAnAgentAndAnAgentStillMayNot() {
    Map<String, Object> body = Map.of("repositoryId", "no-such", "workId", "w-x");
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/agents")
        .then()
        .statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/agents")
        .then()
        .statusCode(403);
  }

  @Test
  void anAdminAgentReadsTheWorkspaces() {
    asAdminAgent()
        .get("/workspaces/api/workspaces?repositoryId=no-such")
        .then()
        .statusCode(admitted());
    asAdminAgent().get("/workspaces/api/workspaces/" + NO_ROW).then().statusCode(admitted());
  }

  /** BranchController: the class role AND the programmatic person-only check in the body. */
  @Test
  void anAdminAgentPassesTheBranchDoorsBodyCheckAndAnAgentDoesNot() {
    Map<String, Object> body = Map.of("source", "feature/x");
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/branches/merge?repositoryId=no-such")
        .then()
        .statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/branches/merge?repositoryId=no-such")
        .then()
        .statusCode(403);
  }

  @Test
  void anAdminAgentEditsHistoryAndTheDraft() {
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("preamble", "x"))
        .patch("/workspaces/api/history/" + NO_ROW)
        .then()
        .statusCode(admitted());
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("content", "x"))
        .put("/workspaces/api/agents/" + NO_AGENT + "/prompt-draft")
        .then()
        .statusCode(admitted());
    asAdminAgent()
        .delete("/workspaces/api/agents/" + NO_AGENT + "/prompt-attachments/some-id")
        .then()
        .statusCode(admitted());
  }

  @Test
  void anAdminAgentDispatchesAndAnAgentStillMayNot() {
    Map<String, Object> body =
        Map.of("repositoryId", "no-such", "wrapperBranch", "ticket/x", "workId", "w-x", "instruction", "go");
    asAdminAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(body)
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(403);
  }

  /** The control: the gate still judges roles, and a near-miss spelling is not the role. */
  @Test
  void aRoleThatOnlyLooksLikeItIsRefused() {
    as("qits:admin-agents")
        .contentType(ContentType.JSON)
        .body(Map.of("repositoryId", "no-such", "workId", "w-x"))
        .post("/workspaces/api/agents")
        .then()
        .statusCode(403);
  }
}
