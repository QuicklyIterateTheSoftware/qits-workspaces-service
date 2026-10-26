package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who may declare, list and cancel a project front desk's waits (qits-1618): {@code qits:admin} and
 * {@code qits:system} for any project; {@code qits:desk-admin} only for the project its token's
 * {@code project} claim names; nobody else — not a workspace agent, not the delivery desk's plain
 * {@code qits:desk}, whatever its {@code context_kind}. Also the door's own answers: the agent door's
 * bodies with {@code agentId} null, the cap, the 400s and a 404 for a wait that is not this desk's.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@link DeskWriteAccessTest}: the
 * {@code project} claim is only readable off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DeskWaitAccessTest {

  private static String waits(String project) {
    return "/workspaces/api/desks/" + project + "/waits";
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
            List.of(List.of(Map.of("path", "releaseRequestId", "op", "exact", "value", "42")))),
        "timeout",
        "PT2H");
  }

  private static RequestSpecification bearer(
      String subject, Set<String> roles, Map<String, String> claims) {
    return given()
        .header(
            "Authorization",
            "Bearer " + DaemonMachineTokens.tokenWithClaims(subject, roles, claims, "qits-platform"))
        .contentType(ContentType.JSON);
  }

  /** The front desk of {@code project}: {@code qits:desk-admin}, bound by its {@code project} claim. */
  private static RequestSpecification asDesk(String project) {
    return bearer(
        "desk-session-" + project,
        Set.of("qits:agent", "qits:desk-admin"),
        Map.of("project", project, "context_kind", "desk-session"));
  }

  private static RequestSpecification asSystem() {
    return bearer("qits-projects", Set.of("qits:system"), Map.of());
  }

  @Test
  void theDeskDeclaresListsAndCancelsItsOwnProjectsWaits() {
    String project = UUID.randomUUID().toString();
    String id =
        asDesk(project)
            .body(aWait())
            .post(waits(project))
            .then()
            .statusCode(201)
            .body("state", equalTo("OPEN"))
            .body("agentId", nullValue())
            .body("label", equalTo("release-request 42"))
            .body("selection.event", equalTo("ReleaseRequestStateChanged"))
            .extract()
            .path("id");

    asDesk(project).get(waits(project) + "?state=OPEN").then().statusCode(200).body("waits", hasSize(1));
    asDesk(project)
        .delete(waits(project) + "/" + id)
        .then()
        .statusCode(200)
        .body("state", equalTo("CANCELLED"));
    asDesk(project).get(waits(project) + "?state=OPEN").then().statusCode(200).body("waits", hasSize(0));
  }

  @Test
  void anotherProjectsDeskIsRefusedEveryVerb() {
    String project = UUID.randomUUID().toString();
    String other = UUID.randomUUID().toString();
    String id = asSystem().body(aWait()).post(waits(project)).then().statusCode(201).extract().path("id");

    asDesk(other).body(aWait()).post(waits(project)).then().statusCode(403);
    asDesk(other).get(waits(project)).then().statusCode(403);
    asDesk(other).delete(waits(project) + "/" + id).then().statusCode(403);
    // A wait of another desk, asked for through the caller's own door, is not found there.
    asDesk(other).delete(waits(other) + "/" + id).then().statusCode(404);
  }

  @Test
  void aDeskAdminTokenWithNoProjectClaimIsRefused() {
    String project = UUID.randomUUID().toString();
    bearer("desk-unbound", Set.of("qits:desk-admin"), Map.of())
        .get(waits(project))
        .then()
        .statusCode(403);
  }

  @Test
  void neitherAWorkspaceAgentNorThePlainDeskIsAdmitted() {
    String project = UUID.randomUUID().toString();
    // A workspace agent's token, of the right project.
    bearer(
            "tok-workspace-agent-x",
            Set.of("qits:agent"),
            Map.of("project", project, "context_kind", "workspace", "agent", UUID.randomUUID().toString()))
        .body(aWait())
        .post(waits(project))
        .then()
        .statusCode(403);
    // An admin workspace's agent.
    bearer(
            "tok-workspace-admin-x",
            Set.of("qits:agent", "qits:admin-agent"),
            Map.of("project", project, "context_kind", "workspace-admin"))
        .get(waits(project))
        .then()
        .statusCode(403);
    // The delivery desk, and a desk-session kind without the desk-admin role: the kind admits nothing.
    bearer(
            "desk-delivery-x",
            Set.of("qits:agent", "qits:desk"),
            Map.of("project", project, "context_kind", "desk-delivery"))
        .body(aWait())
        .post(waits(project))
        .then()
        .statusCode(403);
    bearer(
            "desk-session-x",
            Set.of("qits:agent"),
            Map.of("project", project, "context_kind", "desk-session"))
        .get(waits(project))
        .then()
        .statusCode(403);
  }

  @Test
  void thePlatformActsOnAnyProjectsDesk() {
    String project = UUID.randomUUID().toString();
    asSystem().body(aWait()).post(waits(project)).then().statusCode(201);
    bearer("alice", Set.of("qits:admin"), Map.of())
        .get(waits(project))
        .then()
        .statusCode(200)
        .body("waits", hasSize(1));
  }

  @Test
  void aDeskHoldsAtMostEightOpenWaits() {
    String project = UUID.randomUUID().toString();
    for (int i = 0; i < 8; i++) {
      asDesk(project).body(aWait()).post(waits(project)).then().statusCode(201);
    }
    asDesk(project)
        .body(aWait())
        .post(waits(project))
        .then()
        .statusCode(409)
        .body("code", equalTo("TOO_MANY_WAITS"));
  }

  @Test
  void anInvalidSelectionTimeoutOrProjectIsA400() {
    String project = UUID.randomUUID().toString();
    asDesk(project)
        .body(Map.of("selection", Map.of("event", "X"), "timeout", "P31D"))
        .post(waits(project))
        .then()
        .statusCode(400);
    asDesk(project)
        .body(Map.of("selection", Map.of("event", "X"), "timeout", "two hours"))
        .post(waits(project))
        .then()
        .statusCode(400);
    asDesk(project).body(Map.of("label", "x")).post(waits(project)).then().statusCode(400);
    asDesk(project).get(waits(project) + "?state=SOMETIMES").then().statusCode(400);
    asSystem().get(waits("not-a-project")).then().statusCode(400);
  }
}
