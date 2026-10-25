package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The delivery desk ({@code qits:agent} + {@code qits:desk}, a {@code project} claim, no Git refs)
 * holds the coordinator's writes here, bound to its own project (qits-1596): admitted on a
 * workspace or agent of its project, 403 on another project's, and refused outright where the
 * write would mint the coordinator's rights (an ADMIN agent) or push (branch merge and cleanup, a
 * real branch sweep, a discard that deletes a branch).
 *
 * <p>Under {@link DaemonControlSocketMachineAuthTest.GateOn}, as {@link
 * AgentDispatchDeliveryDeskTest}: the {@code project} claim is only readable off a validated bearer.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DeskWriteAccessTest {

  private static final String API = "/workspaces/api";

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

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

  private static Matcher<Integer> admitted() {
    return not(anyOf(is(401), is(403)));
  }

  private static RequestSpecification asDesk(String project) {
    return given()
        .header(
            "Authorization",
            "Bearer "
                + DaemonMachineTokens.tokenWithClaims(
                    "desk-" + project,
                    Set.of("qits:agent", "qits:desk"),
                    Map.of("project", project),
                    "qits-platform"))
        .contentType(ContentType.JSON);
  }

  private String repositoryOf(String project) {
    String repoId = "desk-" + UUID.randomUUID();
    repositories.registerWrapper(repoId, project, "repo-of-" + project);
    return repoId;
  }

  /**
   * An ACTIVE DIRECT row of {@code project}'s repository with its container up — an admin one,
   * since the direct path is admin only (qits-780) and needs no target. {@code branch} null is a
   * slot.
   */
  private Long workspaceOf(String project, String branch) {
    String repoId = repositoryOf(project);
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  String label = "d" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.workspaceId = label;
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

  private WorkspaceRuntimeStatus runtimeOf(Long id) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findById(id).runtimeStatus);
  }

  @Test
  void theDeskStopsAWorkspaceOfItsOwnProject() {
    Long id = workspaceOf("project-a", null);

    asDesk("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(200)
        .body("runtimeStatus", equalTo("STOPPED"));
  }

  @Test
  void theDeskMayNotStopAnotherProjectsWorkspace() {
    Long id = workspaceOf("project-b", null);

    asDesk("project-a")
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(403)
        .body("message", equalTo(DeskBinding.OWN_PROJECT_ONLY));

    assertEquals(WorkspaceRuntimeStatus.RUNNING, runtimeOf(id), "nothing was stopped");
  }

  @Test
  void theDeskEnsuresAContainerOfItsOwnProjectAndNotAnothers() {
    Long own = workspaceOf("project-a", null);
    Long other = workspaceOf("project-b", null);

    asDesk("project-a")
        .post(API + "/workspaces/" + own + "/ensure-container")
        .then()
        .statusCode(admitted());
    asDesk("project-a")
        .post(API + "/workspaces/" + other + "/ensure-container")
        .then()
        .statusCode(403);
  }

  /** A workspace of no resolvable project is another project's, for the desk: it fails closed. */
  @Test
  void aWorkspaceOfNoKnownProjectIsRefused() {
    Long id = workspaceOf("project-a", null);
    QuarkusTransaction.requiringNew()
        .run(() -> workspaceRepository.findById(id).repositoryId = "unregistered-" + id);

    asDesk("project-a").post(API + "/workspaces/" + id + "/stop-container").then().statusCode(403);
  }

  @Test
  void theDeskMayNotDiscardAWorkspaceWhoseDiscardDeletesABranch() {
    Long id = workspaceOf("project-a", "feature/x");

    asDesk("project-a")
        .body(Map.of())
        .post(API + "/workspaces/" + id + "/discard")
        .then()
        .statusCode(403)
        .body("message", equalTo(WorkspaceController.DESK_DISCARD_DELETES_BRANCH));
  }

  @Test
  void theDeskMayNotCreateAnAdminAgentEvenInItsOwnProject() {
    String repoId = repositoryOf("project-a");

    asDesk("project-a")
        .body(Map.of("repositoryId", repoId, "workId", "w-" + UUID.randomUUID(), "admin", true))
        .post(API + "/agents")
        .then()
        .statusCode(403);
  }

  @Test
  void theDeskCreatesAnAgentOnlyInItsOwnProject() {
    String own = repositoryOf("project-a");
    String other = repositoryOf("project-b");

    asDesk("project-a")
        .body(Map.of("repositoryId", own, "workId", "w-" + UUID.randomUUID()))
        .post(API + "/agents")
        .then()
        .statusCode(admitted());
    asDesk("project-a")
        .body(Map.of("repositoryId", other, "workId", "w-" + UUID.randomUUID()))
        .post(API + "/agents")
        .then()
        .statusCode(403);
  }

  @Test
  void theDeskDispatchesOnlyInItsOwnProject() {
    String other = repositoryOf("project-b");

    asDesk("project-a")
        .body(
            Map.of(
                "repositoryId", other,
                "wrapperBranch", "ticket/x",
                "workId", "w-" + UUID.randomUUID(),
                "instruction", "go"))
        .post(API + "/agent-dispatches")
        .then()
        .statusCode(403);
  }

  /** Every door that pushes stays refused, whatever the project. */
  @Test
  void theDeskNeverReachesADoorThatPushes() {
    String repoId = repositoryOf("project-a");

    asDesk("project-a")
        .body(Map.of("source", "feature/x"))
        .post(API + "/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(403);
    asDesk("project-a")
        .body(Map.of("branch", "feature/x"))
        .post(API + "/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(403);
    asDesk("project-a")
        .body(Map.of("dryRun", false, "repositories", List.of()))
        .post(API + "/gc/branches")
        .then()
        .statusCode(403);
    asDesk("project-a")
        .body(Map.of("dryRun", true, "repositories", List.of()))
        .post(API + "/gc/branches")
        .then()
        .statusCode(admitted());
  }

  /** The control: the coordinator's own token is unbound, whatever project it might name. */
  @Test
  void theCoordinatorIsNotBound() {
    Long id = workspaceOf("project-b", null);

    given()
        .header(
            "Authorization",
            "Bearer "
                + DaemonMachineTokens.tokenWithClaims(
                    "coordinator",
                    Set.of("qits:agent", "qits:admin-agent", "qits:desk"),
                    Map.of("project", "project-a"),
                    "qits-platform"))
        .contentType(ContentType.JSON)
        .post(API + "/workspaces/" + id + "/stop-container")
        .then()
        .statusCode(200);
  }
}
