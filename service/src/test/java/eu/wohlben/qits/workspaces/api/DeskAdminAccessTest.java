package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery desk as {@code qits:desk-admin} (qits-1621): its token holds {@code qits:agent} +
 * {@code qits:desk} + {@code qits:desk-admin} and a {@code project} claim. It reaches the doors the
 * owner's coordinator ({@code qits:admin-agent}) reaches, but it is still a bound desk: another
 * project's workspace is 403, and every refusal that stops a push stays.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@link DeskWriteAccessTest}: the
 * {@code project} claim is only readable off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DeskAdminAccessTest {

  private static final String API = "/workspaces/api";

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject AgentWorktreeRepository agentRows;

  @TestHTTPResource("/workspaces/api/events")
  URL globalEvents;

  private final List<Long> rows = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaceRepository
                            .findByIdOptional(id)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    rows.clear();
  }

  private static String deskAdminToken(String project) {
    return DaemonMachineTokens.tokenWithClaims(
        "desk-" + project,
        Set.of("qits:agent", "qits:desk", "qits:desk-admin"),
        Map.of("project", project),
        "qits-platform");
  }

  private static RequestSpecification asDeskAdmin(String project) {
    return given()
        .header("Authorization", "Bearer " + deskAdminToken(project))
        .contentType(ContentType.JSON);
  }

  private String repositoryOf(String project) {
    String repoId = "desk-admin-" + UUID.randomUUID();
    repositories.registerWrapper(repoId, project, "repo-of-" + project);
    return repoId;
  }

  /** An ACTIVE agent of {@code project}'s repository, whose token is not the desk's. */
  private UUID agentOf(String project) {
    UUID id = UUID.randomUUID();
    String repoId = repositoryOf(project);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repoId;
              row.workId = "w-desk-admin-" + id;
              row.wrapperBranch = "ticket/w-desk-admin";
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              agentRows.persist(row);
            });
    return id;
  }

  /** As {@code DeskWriteAccessTest}: an ACTIVE DIRECT admin row with its container up. */
  private Long workspaceOf(String project, String branch) {
    String repoId = repositoryOf(project);
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  workspace.workspaceId = "da" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.repositoryId = repoId;
                  workspace.branch = branch;
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.placement = WorkspacePlacement.DIRECT;
                  workspace.admin = true;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  return workspace.id;
                });
    rows.add(id);
    return id;
  }

  private static Map<String, Object> aWait() {
    return Map.of(
        "label",
        "release-request 42",
        "selection",
        Map.of("event", "ReleaseRequestStateChanged", "when", List.of()),
        "timeout",
        "PT2H");
  }

  @Test
  void theDeskAdminOpensAndListsTheWaitsOfAnAgentOfItsProject() {
    UUID agent = agentOf("project-a");
    String waits = API + "/agents/" + agent + "/waits";

    asDeskAdmin("project-a")
        .body(aWait())
        .post(waits)
        .then()
        .statusCode(201)
        .body("state", equalTo("OPEN"));
    asDeskAdmin("project-a").get(waits).then().statusCode(200).body("waits", hasSize(1));
  }

  @Test
  void theDeskAdminMayNotTouchTheWaitsOfAnotherProjectsAgent() {
    UUID agent = agentOf("project-b");
    String waits = API + "/agents/" + agent + "/waits";

    asDeskAdmin("project-a").body(aWait()).post(waits).then().statusCode(403);
    asDeskAdmin("project-a").get(waits).then().statusCode(403);
  }

  /** The control: without {@code qits:desk-admin} the desk is bound to its own agent, of which it has none. */
  @Test
  void aPlainDeskIsStillBoundToItsOwnAgent() {
    UUID agent = agentOf("project-a");

    given()
        .header(
            "Authorization",
            "Bearer "
                + DaemonMachineTokens.tokenWithClaims(
                    "desk-project-a",
                    Set.of("qits:agent", "qits:desk"),
                    Map.of("project", "project-a"),
                    "qits-platform"))
        .get(API + "/agents/" + agent + "/waits")
        .then()
        .statusCode(403);
  }

  @Test
  void theDeskAdminReadsTheGlobalEventStream() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(globalEvents.toString()))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + deskAdminToken("project-a"))
            .GET()
            .build();
    HttpResponse<InputStream> response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream stream = response.body()) {
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void theDeskAdminIsStillBoundOnAnotherProjectsWorkspace() {
    Long id = workspaceOf("project-b", null);

    asDeskAdmin("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(403)
        .body("message", equalTo(DeskBinding.OWN_PROJECT_ONLY));
    assertEquals(
        WorkspaceRuntimeStatus.RUNNING,
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findById(id).runtimeStatus),
        "nothing was stopped");
  }

  @Test
  void theDeskAdminStopsAWorkspaceOfItsOwnProject() {
    Long id = workspaceOf("project-a", null);

    asDeskAdmin("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(200);
  }

  /** Every refusal that stops a push stays: the desk-admin never pushes. */
  @Test
  void theDeskAdminNeverReachesADoorThatPushes() {
    String repoId = repositoryOf("project-a");

    asDeskAdmin("project-a")
        .body(Map.of("source", "feature/x"))
        .post(API + "/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(403);
    asDeskAdmin("project-a")
        .body(Map.of("branch", "feature/x"))
        .post(API + "/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(403);
    asDeskAdmin("project-a")
        .body(Map.of("dryRun", false, "repositories", List.of()))
        .post(API + "/gc/branches")
        .then()
        .statusCode(403);
    asDeskAdmin("project-a")
        .body(Map.of("repositoryId", repositoryOf("project-a"), "workId", "w-" + UUID.randomUUID(), "admin", true))
        .post(API + "/agents")
        .then()
        .statusCode(403);
    asDeskAdmin("project-a")
        .body(Map.of())
        .post(API + "/workspaces/" + workspaceOf("project-a", "feature/x") + "/discard")
        .then()
        .statusCode(403)
        .body("message", equalTo(WorkspaceController.DESK_DISCARD_DELETES_BRANCH));
  }
}
