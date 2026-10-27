package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A token holding {@code qits:desk-admin} without {@code qits:desk} (security review, qits-1621):
 * the desk keeps the coordinator's doors, but within its own project. {@link DeskBinding} binds it
 * by its {@code project} claim exactly as it binds {@code qits:desk}, so another project's
 * workspace and agent are 403 and nothing happens — the binding never rests on the delivery desk's
 * token happening to carry both roles.
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@link DeskAdminAccessTest}: the
 * {@code project} claim is only readable off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DeskAdminOnlyBindingTest {

  private static final String API = "/workspaces/api";

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject AgentWorktreeRepository agentRows;

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

  /** {@code qits:agent} + {@code qits:desk-admin} and a project claim: no {@code qits:desk}. */
  private static RequestSpecification asDeskAdminOnly(String project) {
    return given()
        .header(
            "Authorization",
            "Bearer "
                + DaemonMachineTokens.tokenWithClaims(
                    "desk-admin-only-" + project,
                    Set.of("qits:agent", "qits:desk-admin"),
                    Map.of("project", project),
                    "qits-platform"))
        .contentType(ContentType.JSON);
  }

  private String repositoryOf(String project) {
    String repoId = "desk-admin-only-" + UUID.randomUUID();
    repositories.registerWrapper(repoId, project, "repo-of-" + project);
    return repoId;
  }

  private UUID agentOf(String project) {
    UUID id = UUID.randomUUID();
    String repoId = repositoryOf(project);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repoId;
              row.workId = "w-desk-admin-only-" + id;
              row.wrapperBranch = "ticket/w-desk-admin-only";
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              row.commissionedTokenSubject = "tok-workspace-agent-" + id;
              agentRows.persist(row);
            });
    return id;
  }

  private Long workspaceOf(String project) {
    String repoId = repositoryOf(project);
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  workspace.workspaceId = "dao" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.repositoryId = repoId;
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

  @Test
  void anotherProjectsWorkspaceIsRefusedAndLeftAlone() {
    Long id = workspaceOf("project-b");

    asDeskAdminOnly("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(403)
        .body("message", equalTo(DeskBinding.OWN_PROJECT_ONLY));
    asDeskAdminOnly("project-a")
        .body(Map.of())
        .post(API + "/workspaces/" + id + "/discard")
        .then()
        .statusCode(403);
    Workspace after =
        QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(id));
    assertEquals(WorkspaceRuntimeStatus.RUNNING, after.runtimeStatus, "nothing was stopped");
    assertEquals(WorkspaceStatus.ACTIVE, after.status, "nothing was discarded");
  }

  @Test
  void itsOwnProjectsWorkspacePassesTheBinding() {
    Long id = workspaceOf("project-a");

    asDeskAdminOnly("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(200);
  }

  @Test
  void anotherProjectsAgentIsRefused() {
    UUID agent = agentOf("project-b");

    asDeskAdminOnly("project-a")
        .get(API + "/agents/" + agent + "/waits")
        .then()
        .statusCode(403);
  }

  @Test
  void theDeskOnlyRefusalsHoldWithoutQitsDesk() {
    asDeskAdminOnly("project-a")
        .body(Map.of("dryRun", false, "repositories", List.of()))
        .post(API + "/gc/branches")
        .then()
        .statusCode(403);
    asDeskAdminOnly("project-a")
        .body(
            Map.of(
                "repositoryId",
                repositoryOf("project-b"),
                "workId",
                "w-" + UUID.randomUUID(),
                "admin",
                true))
        .post(API + "/agents")
        .then()
        .statusCode(403);
  }
}
