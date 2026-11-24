package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.workspaces.control.FakeWorkItemLookup;
import eu.wohlben.qits.workspaces.control.WorkItemLookup;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Who may declare, list and cancel a project agent's waits (qits-2596): only a project agent —
 * {@code qits:agent}, {@code context_kind} {@code project-agent} and a {@code project} claim — and
 * only its own, each held for an EPIC or a TICKET of its own project. Not a workspace agent, not a
 * desk, not the platform. Also the door's own answers: the agent door's body plus {@code work}, the
 * per-item cap, the 400s and a 404 for another holder's wait.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@code DeskWaitAccessTest}: the
 * claims are only readable off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class ProjectAgentWaitAccessTest {

  private static final String WAITS = "/workspaces/api/project-agent/waits";

  private static final String DEVELOPER = "tok-project-agent-qits-developer";

  @Inject FakeWorkItemLookup workItems;

  private final List<WorkItemLookup.WorkItem> added = new ArrayList<>();

  @AfterEach
  void forget() {
    added.forEach(workItems::forget);
  }

  private WorkItemLookup.WorkItem item(String project, String archetype) {
    WorkItemLookup.WorkItem item =
        new WorkItemLookup.WorkItem(
            UUID.randomUUID().toString(),
            "pa-" + UUID.randomUUID().toString().substring(0, 8),
            archetype,
            project);
    workItems.add(item);
    added.add(item);
    return item;
  }

  private static Map<String, Object> aWait(String work) {
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
        "PT2H",
        "work",
        work);
  }

  private static RequestSpecification bearer(
      String subject, Set<String> roles, Map<String, String> claims) {
    return given()
        .header(
            "Authorization",
            "Bearer " + DaemonMachineTokens.tokenWithClaims(subject, roles, claims, "qits-platform"))
        .contentType(ContentType.JSON);
  }

  private static RequestSpecification asProjectAgent(String subject, String project) {
    return bearer(
        subject, Set.of("qits:agent"), Map.of("project", project, "context_kind", "project-agent"));
  }

  @Test
  void aProjectAgentDeclaresListsAndCancelsItsOwnWaitsForAWorkItem() {
    String project = UUID.randomUUID().toString();
    WorkItemLookup.WorkItem ticket = item(project, "TICKET");
    String id =
        asProjectAgent(DEVELOPER, project)
            .body(aWait(ticket.qualifiedId()))
            .post(WAITS)
            .then()
            .statusCode(201)
            .body("state", equalTo("OPEN"))
            .body("agentId", nullValue())
            .body("label", equalTo("release-request 42"))
            .body("selection.event", equalTo("ReleaseRequestStateChanged"))
            .body("work", equalTo(ticket.qualifiedId()))
            .body("workId", equalTo(ticket.id()))
            .extract()
            .path("id");

    // Named by its uuid too, on an epic.
    WorkItemLookup.WorkItem epic = item(project, "EPIC");
    asProjectAgent(DEVELOPER, project).body(aWait(epic.id())).post(WAITS).then().statusCode(201)
        .body("work", equalTo(epic.qualifiedId()));

    asProjectAgent(DEVELOPER, project).get(WAITS).then().statusCode(200).body("waits", hasSize(2));
    asProjectAgent(DEVELOPER, project)
        .get(WAITS + "?work=" + ticket.qualifiedId())
        .then()
        .statusCode(200)
        .body("waits", hasSize(1))
        .body("waits[0].id", equalTo(id));
    asProjectAgent(DEVELOPER, project).get(WAITS + "?work=" + ticket.id()).then().body("waits", hasSize(1));
    // Another project agent's subject sees none of them, nor may it cancel one.
    asProjectAgent("tok-project-agent-qits-reviewer", project).get(WAITS).then().body("waits", hasSize(0));
    asProjectAgent("tok-project-agent-qits-reviewer", project).delete(WAITS + "/" + id).then().statusCode(404);

    asProjectAgent(DEVELOPER, project).delete(WAITS + "/" + id).then().statusCode(204);
    asProjectAgent(DEVELOPER, project).delete(WAITS + "/" + id).then().statusCode(204);
    asProjectAgent(DEVELOPER, project)
        .get(WAITS + "?work=" + ticket.qualifiedId())
        .then()
        .body("waits", hasSize(0));
    asProjectAgent(DEVELOPER, project)
        .get(WAITS + "?work=" + ticket.qualifiedId() + "&state=all")
        .then()
        .body("waits", hasSize(1))
        .body("waits[0].state", equalTo("CANCELLED"));
    asProjectAgent(DEVELOPER, project).get(WAITS + "?state=sometimes").then().statusCode(400);
    asProjectAgent(DEVELOPER, project).delete(WAITS + "/not-a-wait").then().statusCode(404);
  }

  @Test
  void aWaitWithoutItsWorkItemIsA400() {
    String project = UUID.randomUUID().toString();
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("label", "x", "timeout", "PT1H"))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", equalTo("a project agent's wait names its work item: work"));
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("label", "x", "timeout", "PT1H", "work", " "))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", equalTo("a project agent's wait names its work item: work"));
  }

  @Test
  void anotherProjectsItemOrOneThatIsNoEpicOrTicketIsA400AndAnUnknownOneA404() {
    String project = UUID.randomUUID().toString();
    WorkItemLookup.WorkItem foreign = item(UUID.randomUUID().toString(), "TICKET");
    asProjectAgent(DEVELOPER, project)
        .body(aWait(foreign.qualifiedId()))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", containsString("not of this agent's project"));
    WorkItemLookup.WorkItem task = item(project, "TASK");
    asProjectAgent(DEVELOPER, project)
        .body(aWait(task.qualifiedId()))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", containsString("EPIC or a TICKET"));
    asProjectAgent(DEVELOPER, project).body(aWait("qits-nowhere-1")).post(WAITS).then().statusCode(404);
  }

  @Test
  void neitherAWorkspaceAgentNorADeskNorThePlatformIsAdmitted() {
    String project = UUID.randomUUID().toString();
    WorkItemLookup.WorkItem ticket = item(project, "TICKET");
    String expected = "only a project's long-running agent holds these waits";
    // A workspace agent's token, of the right project.
    bearer(
            "tok-workspace-agent-x",
            Set.of("qits:agent"),
            Map.of("project", project, "context_kind", "workspace", "agent", UUID.randomUUID().toString()))
        .body(aWait(ticket.qualifiedId()))
        .post(WAITS)
        .then()
        .statusCode(403)
        .body("message", equalTo(expected));
    // The front desk and the delivery desk.
    bearer(
            "desk-session-x",
            Set.of("qits:agent", "qits:desk-admin"),
            Map.of("project", project, "context_kind", "desk-session"))
        .body(aWait(ticket.qualifiedId()))
        .post(WAITS)
        .then()
        .statusCode(403)
        .body("message", equalTo(expected));
    bearer(
            "desk-delivery-x",
            Set.of("qits:agent", "qits:desk"),
            Map.of("project", project, "context_kind", "desk-delivery"))
        .get(WAITS)
        .then()
        .statusCode(403);
    // A project agent's kind with no project, or a project that is no uuid.
    bearer(DEVELOPER, Set.of("qits:agent"), Map.of("context_kind", "project-agent"))
        .get(WAITS)
        .then()
        .statusCode(403);
    bearer(DEVELOPER, Set.of("qits:agent"), Map.of("context_kind", "project-agent", "project", "*"))
        .get(WAITS)
        .then()
        .statusCode(403);
    // The platform holds no such waits either: the role is not even admitted.
    bearer("qits-projects", Set.of("qits:system"), Map.of()).get(WAITS).then().statusCode(403);
    bearer("alice", Set.of("qits:admin"), Map.of()).get(WAITS).then().statusCode(403);
  }

  @Test
  void aNinthOpenWaitOnOneItemIsRefusedWhileAnotherItemStillTakesOne() {
    String project = UUID.randomUUID().toString();
    WorkItemLookup.WorkItem ticket = item(project, "TICKET");
    for (int i = 0; i < 8; i++) {
      asProjectAgent(DEVELOPER, project).body(aWait(ticket.qualifiedId())).post(WAITS).then().statusCode(201);
    }
    asProjectAgent(DEVELOPER, project)
        .body(aWait(ticket.qualifiedId()))
        .post(WAITS)
        .then()
        .statusCode(409)
        .body("code", equalTo("TOO_MANY_WAITS"));
    WorkItemLookup.WorkItem other = item(project, "TICKET");
    asProjectAgent(DEVELOPER, project).body(aWait(other.qualifiedId())).post(WAITS).then().statusCode(201);
  }

  @Test
  void theAgentDoorsRulesHold() {
    String project = UUID.randomUUID().toString();
    WorkItemLookup.WorkItem ticket = item(project, "TICKET");
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("selection", Map.of("event", "BuildStatusChanged"), "timeout", "PT25H", "work", ticket.id()))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", containsString("24 hours"));
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("selection", Map.of("event", "ReleaseReqeustChanged"), "work", ticket.id()))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", containsString("did you mean 'ReleaseRequestChanged'?"));
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("label", "x", "work", ticket.id()))
        .post(WAITS)
        .then()
        .statusCode(400)
        .body("message", containsString("for a timer, a timeout"));
    // A timer.
    asProjectAgent(DEVELOPER, project)
        .body(Map.of("timeout", "PT45M", "work", ticket.id()))
        .post(WAITS)
        .then()
        .statusCode(201)
        .body("subjectKind", equalTo("TIMER"))
        .body("selection", nullValue())
        .body("label", containsString("timer until "));
  }
}
