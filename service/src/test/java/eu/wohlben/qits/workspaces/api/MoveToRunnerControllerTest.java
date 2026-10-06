package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.LegacyDirectRows;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.MoveRefusals;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/workspaces/{id}/move-to-runner} (qits-776): the answer's shape, the
 * gate's 400 codes and the 409 — the door's wiring. The gate's whole matrix, the swap race, the
 * teardown and the sweep are the domain suite's ({@code DirectPlacementMoveTest}), where the daemon
 * reports can be faked; here no daemon is connected, so a live container's tree is always UNKNOWN.
 */
@QuarkusTest
public class MoveToRunnerControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject WorkspaceIds workspaceIds;
  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;

  @Test
  public void aDirectRowWithNothingOnTheHostIsMovedAndAnswersTheWorkspaceAndItsProcess()
      throws Exception {
    String repoId = repo();
    Long id = legacyDirect(repoId, "mv-empty");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(move(id))
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.id", equalTo(id.intValue()))
        .body("workspace.editor", equalTo(false))
        .body("technicalProcessId", notNullValue());

    await(() -> read(id).placement == WorkspacePlacement.RUNNER);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, read(id).runtimeStatus);
    given()
        .when()
        .get("/workspaces/api/workspaces/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.placement", equalTo("RUNNER"));
  }

  @Test
  public void aRunningRowWhoseTreeNoDaemonReportedIs400Unknown() throws Exception {
    String repoId = repo();
    Long id = legacyDirect(repoId, "mv-unknown");
    workspaceService.ensureContainer(id);

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(move(id))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("code", equalTo(MoveRefusals.UNKNOWN))
        .body("message", containsString("unknown"));
    assertEquals(WorkspacePlacement.DIRECT, read(id).placement);
  }

  @Test
  public void aProvisioningRowIs400Provisioning() throws Exception {
    String repoId = repo();
    Long id = legacyDirect(repoId, "mv-prov");
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaceRepository.findById(id).runtimeStatus =
                    WorkspaceRuntimeStatus.PROVISIONING);

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(move(id))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("code", equalTo(MoveRefusals.PROVISIONING));
  }

  @Test
  public void anAdminRowIs400NotRegular() throws Exception {
    String repoId = repo();
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                repoId, "mv-admin", "master", "mv-admin", null, false, false, true))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    Long id = workspaceIds.of(repoId, "mv-admin");

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(move(id))
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("code", equalTo(MoveRefusals.NOT_REGULAR));
  }

  @Test
  public void aRunnerRowIs409AlreadyMoved() throws Exception {
    String repoId = repo();
    Long id = workspaceService.createWorkspace(repoId, "mv-runner", "master", "mv-runner", null).id;

    given()
        .contentType(ContentType.JSON)
        .when()
        .post(move(id))
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("code", equalTo(MoveRefusals.ALREADY_MOVED));
  }

  @Test
  public void anUnknownWorkspaceIs404() {
    given().contentType(ContentType.JSON).when().post(move(-1L)).then().statusCode(404);
  }

  private static String move(Long id) {
    return "/workspaces/api/workspaces/" + id + "/move-to-runner";
  }

  private String repo() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    return repoId;
  }

  private Long legacyDirect(String repoId, String label) {
    return LegacyDirectRows.direct(
            () -> workspaceService.createWorkspace(repoId, label, "master", label, null))
        .id;
  }

  private eu.wohlben.qits.workspaces.entity.Workspace read(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(id));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("condition not reached");
  }
}
