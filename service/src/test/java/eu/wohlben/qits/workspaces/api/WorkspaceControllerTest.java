package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.workspaces.control.TestOrigin;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class WorkspaceControllerTest {

  // The effective data dir the app uses, so tests can commit directly inside a workspace on disk.
  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @jakarta.inject.Inject eu.wohlben.qits.workspaces.control.WorkspaceIds workspaceIds;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.FakeRepositoryLookup repositories;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.WorkspaceService workspaceService;

  @jakarta.inject.Inject eu.wohlben.qits.workspaces.control.WorkspaceRunners runners;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.TechnicalProcessRegistry technicalProcesses;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository runnerRepository;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.persistence.WorkspaceRepository workspaceRepository;

  /** An ISO-8601 instant, the shape every {@code Instant} in {@code WorkspaceDto} serializes to. */
  private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z";

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture. Those routes belong to the projects and repositories contexts and are not part of this
   * jar, so the same state is set up directly instead — the endpoints under test here are the
   * workspace ones, not the seeding ones.
   */
  private String createProjectAndRepository() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  @Test
  public void testCreateWorkspaceAndMergeAndDiscard() {
    String repoId = createProjectAndRepository();

    // fork a new branch "step-work" from the feature branch
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, "step-01", "feature", "step-work", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.workspaceId", equalTo("step-01"))
        // The create response is a thin view, but createdAt is a plain row field and rides along:
        // the overview sorts on it, so a freshly created workspace must already carry one.
        .body("workspace.createdAt", matchesPattern(ISO_INSTANT));

    // The create started the container (qits-853). This suite has no daemon to report its tree
    // clean, and an unknown tree refuses a merge, so the container goes once the start is over —
    // the row stays ACTIVE and nothing was written in it.
    awaitProvisioned(repoId, "step-01");
    deleteContainer(repoId, "step-01");

    // merge the workspace's branch into its parent, "feature". NOT into master: master is this
    // repository's default branch, which integrate alone writes now — that case is
    // testMergeIntoTheDefaultBranchIsRefusedAndNamesIntegrate below.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("feature"))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "step-01") + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    // discard — carrying ?ignore-changes=true, the UI's confirmed override. Here the tree is
    // clean, so this proves only the wiring: the parameter is accepted and the discard proceeds.
    // The semantics (a dirty tree refused plainly, discarded with force) are the domain suite's
    // abandonWithForceDiscardsADirtyWorkspace.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post(
            "/workspaces/api/workspaces/"
                + workspaceIds.of(repoId, "step-01")
                + "/discard?ignore-changes=true")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }

  /**
   * The create field {@code placement} (qits-853, qits-774): absent is RUNNER for a regular
   * workspace even though this suite registers no runner at all, a RUNNER admin workspace is a 400,
   * a DIRECT regular workspace is a 400 that names itself {@code DIRECT_PLACEMENT_REFUSED}, and a
   * stated RUNNER with no runner is no refusal either. Both refusals come before any branch is
   * pushed.
   */
  @Test
  public void testCreatePlacement() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "repositoryId", repoId, "id", "plain-01", "parent", "master", "branch", "plain-01"))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.placement", equalTo("RUNNER"))
        .body("workspace.runner", nullValue());

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "repositoryId", repoId,
                "id", "adm-01",
                "parent", "master",
                "branch", "adm-01",
                "admin", true,
                "placement", "RUNNER"))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "repositoryId", repoId,
                "id", "direct-01",
                "parent", "master",
                "branch", "direct-01",
                "placement", "DIRECT"))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
        .body("code", equalTo("DIRECT_PLACEMENT_REFUSED"))
        .body(
            "message",
            equalTo(
                "Regular workspaces run on a workspace runner; only admin and editor workspaces"
                    + " use the direct path."));

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "repositoryId", repoId,
                "id", "run-01",
                "parent", "master",
                "branch", "run-01",
                "placement", "RUNNER"))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.placement", equalTo("RUNNER"));

    given()
        .contentType(ContentType.JSON)
        .body(
            java.util.Map.of(
                "repositoryId", repoId,
                "id", "adm-02",
                "parent", "master",
                "branch", "adm-02",
                "admin", true))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.placement", equalTo("DIRECT"));

    org.junit.jupiter.api.Assertions.assertFalse(workspaceService.branchExists(repoId, "adm-01"));
    org.junit.jupiter.api.Assertions.assertFalse(
        workspaceService.branchExists(repoId, "direct-01"));

    // The two RUNNER rows abandoned outright, so neither is left for a later suite's fake runner.
    for (String label : java.util.List.of("plain-01", "run-01")) {
      Long rowId = workspaceIds.of(repoId, label);
      io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository.findById(rowId).status =
                      eu.wohlben.qits.workspaces.entity.WorkspaceStatus.ABANDONED);
    }
  }

  /**
   * With no runner at all (qits-774), a plain create is written RUNNER and an ensure-container
   * answers QUEUED rather than any error: the row simply waits for a runner. Queued through the
   * RUNNER start, so it needs the edge plane (a public domain).
   */
  @Test
  public void testEnsureWithNoRunnerAnswersQueued() {
    io.quarkus.test.junit.QuarkusMock.installMockForType(
        eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddressesFixture.planesWithDomain(
            "wohlben.eu"),
        eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes.class);
    String repoId = createProjectAndRepository();
    try {
      given()
          .contentType(ContentType.JSON)
          .body(
              java.util.Map.of(
                  "repositoryId", repoId, "id", "parked-01", "parent", "master", "branch",
                  "parked-01"))
          .when()
          .post("/workspaces/api/workspaces")
          .then()
          .statusCode(Response.Status.OK.getStatusCode())
          .body("workspace.placement", equalTo("RUNNER"))
          .body("workspace.runtimeStatus", equalTo("QUEUED"));

      given()
          .contentType(ContentType.JSON)
          .when()
          .post(
              "/workspaces/api/workspaces/"
                  + workspaceIds.of(repoId, "parked-01")
                  + "/ensure-container")
          .then()
          .statusCode(Response.Status.OK.getStatusCode())
          .body("workspace.runtimeStatus", equalTo("QUEUED"))
          .body("technicalProcessId", notNullValue());
    } finally {
      // Abandoned outright: a QUEUED row left behind would be a reservation for the next suite's
      // fake runner to take.
      Long rowId = workspaceIds.of(repoId, "parked-01");
      io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository.findById(rowId).status =
                      eu.wohlben.qits.workspaces.entity.WorkspaceStatus.ABANDONED);
    }
  }

  /**
   * Creating a DIRECT workspace — an admin one, the only kind the create door still places DIRECT
   * (qits-774) — starts it (qits-853): the answer carries the start's process, and an
   * ensure-container sent after it — what every client did before — still answers. That the second
   * call joins the start rather than provisioning again is the domain's {@code
   * WorkspaceRunnerPlacementTest.creatingADirectWorkspaceStartsItOnce}, which can count the runs.
   */
  @Test
  public void testCreateStartsTheWorkspace() {
    String repoId = createProjectAndRepository();

    given()
            .contentType(ContentType.JSON)
            .body(
                java.util.Map.of(
                    "repositoryId", repoId, "id", "started-01", "parent", "master", "branch",
                    "started-01", "admin", true))
            .when()
            .post("/workspaces/api/workspaces")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("workspace.workspaceId", equalTo("started-01"))
            .body("workspace.createdAt", matchesPattern(ISO_INSTANT))
            .body("technicalProcessId", notNullValue())
            .body("startError", nullValue());

    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "started-01") + "/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    awaitProvisioned(repoId, "started-01");
    given()
        .get("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "started-01"))
        .then()
        .statusCode(200)
        .body("workspace.runtimeStatus", equalTo("RUNNING"));
  }

  /**
   * An ensure-container never answers 200 with a start that will not bring the container up
   * (qits-1076): a DIRECT row that is STOPPED while a start's process is still open — the instant
   * between a stop and its ending of that start, played by a process registered straight on the
   * registry — is a 409 that names itself and names the process to wait for.
   */
  @Test
  public void testEnsureOnAStoppedWorkspaceWithAStartStillOpenIsAConflict() {
    String repoId = createProjectAndRepository();
    workspaceService.createWorkspace(
        repoId, "stopped-open", "master", "stopped-open", null, false, false, true);
    Long id = workspaceIds.of(repoId, "stopped-open");
    eu.wohlben.qits.workspaces.control.TechnicalProcess open =
        technicalProcesses.begin(repoId, "stopped-open", id);
    try {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/workspaces/api/workspaces/" + id + "/ensure-container")
          .then()
          .statusCode(Response.Status.CONFLICT.getStatusCode())
          .body("code", equalTo("WORKSPACE_STOPPED_DURING_START"))
          .body("message", containsString(open.id()))
          .body("message", containsString("stopped while it was starting"));
    } finally {
      technicalProcesses.end(open.id(), "test over");
    }
  }

  /**
   * A RUNNER workspace's create is the request: the row comes back QUEUED, and no process is
   * answered because there is none — the row's status is its progress. Queued through the RUNNER
   * start, so it needs the edge plane (a public domain) and holds its workspace token (qits-625).
   */
  @Test
  public void testCreateQueuesARunnerWorkspace() {
    io.quarkus.test.junit.QuarkusMock.installMockForType(
        eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddressesFixture.planesWithDomain(
            "wohlben.eu"),
        eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes.class);
    String repoId = createProjectAndRepository();
    java.util.UUID runnerId = java.util.UUID.randomUUID();
    runners.create(runnerId, "r-" + runnerId.toString().substring(0, 8), null, 1, "token-" + runnerId, "sub-" + runnerId);
    try {
      runners.markRegistered(runnerId, "client-" + runnerId, null);
      runners.greenlight(runnerId);

      given()
          .contentType(ContentType.JSON)
          .body(
              java.util.Map.of(
                  "repositoryId", repoId,
                  "id", "queued-01",
                  "parent", "master",
                  "branch", "queued-01",
                  "placement", "RUNNER"))
          .when()
          .post("/workspaces/api/workspaces")
          .then()
          .statusCode(Response.Status.OK.getStatusCode())
          .body("workspace.placement", equalTo("RUNNER"))
          .body("workspace.runtimeStatus", equalTo("QUEUED"))
          .body("workspace.queuedAt", matchesPattern(ISO_INSTANT))
          .body("technicalProcessId", nullValue())
          .body("startError", nullValue());
      Long queuedRow = workspaceIds.of(repoId, "queued-01");
      org.junit.jupiter.api.Assertions.assertNotNull(
          io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
              .call(() -> workspaceRepository.findById(queuedRow).commissionedTokenId),
          "queued only once it holds its workspace token");
    } finally {
      // Abandoned outright, as the domain's placement test cleans up: a QUEUED row left behind
      // would be a reservation for the next suite's fake runner to take.
      Long rowId = workspaceIds.of(repoId, "queued-01");
      io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
          .run(
              () -> {
                workspaceRepository.findById(rowId).status =
                    eu.wohlben.qits.workspaces.entity.WorkspaceStatus.ABANDONED;
                runnerRepository.deleteById(runnerId);
              });
    }
  }

  @Test
  public void testCreateBranchTreeAddsWorkspaceGuide() throws Exception {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                repoId, "adhoc-changes", "master", "adhoc-changes", null, false, true))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.workspaceId", equalTo("adhoc-changes"));

    String guide = TestOrigin.fileAtBranch(dataDir, repoId, "adhoc-changes", "WORKSPACE.md");
    // Two sentences, as the shipped guide spells them: where a release is asked for, and what a
    // release is. Both moved when the release door left this service, and a quoted fragment is only
    // worth asserting while it is the one a workspace really receives.
    org.junit.jupiter.api.Assertions.assertTrue(
        guide.contains("ask **qits-projects** to release it"), guide);
    org.junit.jupiter.api.Assertions.assertTrue(
        guide.contains("a green gate turns it into a version **tag**"), guide);
  }

  /**
   * The rule that makes "integrate is the only flow into the default branch" true in the API and
   * not only at the git host. Merge keeps every other target — merging into a parent branch is what
   * stacked workspaces do all day — and it names the endpoint that does the refused thing properly,
   * because a refusal a caller cannot act on is a worse bug than the merge would have been.
   */
  @Test
  public void testMergeIntoTheDefaultBranchIsRefusedAndNamesIntegrate() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "into-main", "master", "into-main-b");
    Long id = workspaceIds.of(repoId, "into-main");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("master"))
        .when()
        .post("/workspaces/api/workspaces/" + id + "/merge")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("/integrate"))
        .body("message", containsString(String.valueOf(id)));
  }

  /** And a blank target, which resolves to the default branch, is the same refusal. */
  @Test
  public void testMergeWithNoTargetIsRefusedBecauseItResolvesToTheDefaultBranch() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "blank-target", "master", "blank-target-b");

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest(null))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "blank-target") + "/merge")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("/integrate"));
  }

  @Test
  public void testListWorkspacesReturnsCreatedWorkspaceWithBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, "wt-list", "master", "wt-branch", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem("wt-list"))
        // branch is the workspace's own forked branch, resolved from the on-disk workspace
        // (also a regression guard for the path fix).
        .body(
            "entries.find { it.workspace.workspaceId == 'wt-list' }.workspace.branch",
            equalTo("wt-branch"))
        // The overview orders by "most recently touched" and approximates that with createdAt, so
        // the listing has to carry it — ISO-8601, like resolvedAt beside it.
        .body(
            "entries.find { it.workspace.workspaceId == 'wt-list' }.workspace.createdAt",
            matchesPattern(ISO_INSTANT));
  }

  @Test
  public void testTwoWorkspacesCanForkFromTheSameParentBranch() {
    String repoId = createProjectAndRepository();

    // Two workspaces forking new branches from the same parent must not conflict —
    // the old behaviour (checking out an existing branch) made this impossible.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, "fork-a", "master", "branch-a", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, "fork-b", "master", "branch-b", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'fork-a' }.workspace.branch",
            equalTo("branch-a"))
        .body(
            "entries.find { it.workspace.workspaceId == 'fork-b' }.workspace.branch",
            equalTo("branch-b"));
  }

  @Test
  public void testCreateWorkspaceAdoptsExistingBranch() {
    String repoId = createProjectAndRepository();

    // `feature` already exists in the fixture with no workspace. Adopting it must succeed (no
    // duplicate `git branch`) and record a workspace that owns that branch, parented on main.
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, 
                "feature-ws", "master", "feature", null, true))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.workspaceId", equalTo("feature-ws"))
        // branch is resolved only in the list path (the create DTO mapper ignores it), so the
        // adopted-branch assertion lives on the list call below.
        .body("workspace.parent", equalTo("master"));

    // It shows up in the list bound to the adopted branch (not a fresh fork).
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'feature-ws' }.workspace.branch",
            equalTo("feature"));
  }

  @Test
  public void testCreateWorkspaceRecordsTheAdminPostureItWasAskedFor() {
    String repoId = createProjectAndRepository();

    // The posture rides the create and lands on the row, so the read model can say which
    // workspaces hold the host's docker socket. It is answered on the create response and on the
    // listing, because "which ones are privileged" is a question a client must be able to ask
    // without opening each workspace.
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                repoId, "admin-ws", "master", "admin-branch", null, false, false, true))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.admin", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'admin-ws' }.workspace.admin",
            equalTo(true));
  }

  @Test
  public void testCreateWorkspaceIsOrdinaryUnlessTheRequestAsksForAdmin() {
    String repoId = createProjectAndRepository();

    // The claim worth holding: a body that says nothing about the posture creates a workspace with
    // no socket. Every client that predates admin mode sends exactly that body, and a default that
    // ever flipped would grant the host's docker socket to all of them at once.
    given()
        .contentType(ContentType.JSON)
        .body("{\"repositoryId\":\"" + repoId + "\",\"id\":\"plain-ws\",\"branch\":\"plain-branch\"}")
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.admin", equalTo(false));
  }

  @Test
  public void testCreateWorkspaceRejectsExistingBranchWhenNotAdopting() {
    String repoId = createProjectAndRepository();

    // Without adoptExisting, a workspace whose branch already exists must fail loudly rather than
    // silently adopt it — a typo'd "branch off" name is a real error, not an adoption.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, "dup-ws", "master", "feature", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode());
  }

  @Test
  public void testListWorkspacesReportsCommitsAheadAndBehindParent() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.CreateWorkspaceRequest(repoId, "ab-wt", "master", "ab-branch", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // A workspace freshly forked from its parent has made no commits yet, so it is
    // neither ahead of nor behind the parent branch.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.find { it.workspace.workspaceId == 'ab-wt' }.workspace.ahead", equalTo(0))
        .body("entries.find { it.workspace.workspaceId == 'ab-wt' }.workspace.behind", equalTo(0));
  }

  /**
   * An ADMIN workspace, written STOPPED and not started: the tests using it prove what the DIRECT
   * container verbs do, and the direct path is admin and editor only (qits-780) — a regular row
   * through the door is RUNNER and never reaches them. Made through the service rather than the door
   * because the door starts what it creates (qits-853), and these tests start it themselves.
   */
  private void createWorkspace(String repoId, String id, String parent, String branch) {
    workspaceService.createWorkspace(repoId, id, parent, branch, null, false, false, true);
  }

  private void mergeInto(String repoId, String workspaceId, String target) {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest(target))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, workspaceId) + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }



  @Test
  public void testDivergedButCleanWorkspaceReportsNoConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "clean-parent", "master", "clean-parent-branch");
    createWorkspace(repoId, "clean-child", "clean-parent-branch", "clean-child-branch");

    // Both branches add their own distinct file, so each is ahead of and behind the other, yet
    // merging the parent in applies cleanly — divergence without a conflict.
    commitFile(repoId, "clean-parent", "parent-only.txt", "from parent\n", "parent commit");
    commitFile(repoId, "clean-child", "child-only.txt", "from child\n", "child commit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.ahead",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.behind",
            greaterThan(0))
        // diverged but a merge would apply cleanly → no conflict warning
        .body(
            "entries.find { it.workspace.workspaceId == 'clean-child' }.workspace.conflictsWithParent",
            equalTo(false));
  }

  @Test
  public void testDivergedConflictingWorkspaceReportsConflict() throws Exception {
    String repoId = createProjectAndRepository();

    createWorkspace(repoId, "cf-parent", "master", "cf-parent-branch");
    createWorkspace(repoId, "cf-child", "cf-parent-branch", "cf-child-branch");

    // Both branches change the same line of the same file to different values, so a merge of the
    // parent into the child can't apply without manual resolution.
    commitFile(repoId, "cf-parent", "conflict.txt", "parent version\n", "parent edit");
    commitFile(repoId, "cf-child", "conflict.txt", "child version\n", "child edit");

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.ahead",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.behind",
            greaterThan(0))
        .body(
            "entries.find { it.workspace.workspaceId == 'cf-child' }.workspace.conflictsWithParent",
            equalTo(true));
  }


  // MOVED with the routes: the four fast-forward / update-from-parent cases.
  //
  //   testFastForwardAdvancesBranchToParent
  //   testFastForwardRejectsDivergedBranch
  //   testUpdateFromParentMergesDivergedBranch
  //   testUpdateFromParentRejectsConflictAndLeavesWorkspaceUsable
  //
  // POST /{workspaceId}/fast-forward and /update-from-parent are now workspace-daemon routes -- the
  // git they drove ran `docker exec` inside the container the daemon owns. These are real
  // behavioural cases (ff-only refuses divergence; a conflicting merge aborts and leaves the
  // workspace usable) and they should be re-asserted against the daemon's HTTP API, not lost.




  /**
   * Writes a file inside the workspace on disk, commits it on the workspace's branch, and pushes.
   * The workspace is a container-style clone, so a commit stays local until pushed; the origin-side
   * ahead/behind, conflict and incoming-commits probes only see pushed commits.
   */
  /**
   * Provisions the workspace's container (creation is lazy — nothing exists to write into until
   * first use) and returns the fake runtime's host-clone path for it, for tests that touch the
   * working tree directly.
   */
  private Path ensuredWorkspacePath(String repoId, String workspaceId) {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, workspaceId) + "/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    // ensure-container provisions asynchronously now (it returns a technical-process id and the
    // work streams over SSE), so wait for the provision to finish before touching the working
    // tree.
    awaitProvisioned(repoId, workspaceId);
    return Path.of(dataDir, repoId, "workspaces", workspaceId);
  }

  /**
   * Polls until the workspace's technical process completed — i.e. the async provision (and the
   * bootstrap phase) is fully done. Deliberately NOT a runtime-status poll: the workspace list
   * computes RUNNING live from the container set, which reports the container as soon as {@code
   * docker run} happened — while the clone is still writing the working tree (and its final index
   * write would clobber a concurrent {@code git add} from the test).
   */
  private void awaitProvisioned(String repoId, String workspaceId) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      String processId =
          given()
              .when()
              .get("/workspaces/api/workspaces/" + workspaceIds.of(repoId, workspaceId) + "/active-process")
              .then()
              .statusCode(Response.Status.OK.getStatusCode())
              .extract()
              .path("technicalProcessId");
      if (processId == null) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new AssertionError("workspace " + workspaceId + " provision never completed");
  }

  private void deleteContainer(String repoId, String workspaceId) {
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, workspaceId) + "/delete-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
  }

  private void commitFile(
      String repoId, String workspaceId, String file, String content, String msg) throws Exception {
    Path workspacePath = ensuredWorkspacePath(repoId, workspaceId);
    Files.writeString(workspacePath.resolve(file), content);
    runGit(workspacePath, "git", "add", file);
    runGit(
        workspacePath,
        "git",
        "-c",
        "user.email=test@example.com",
        "-c",
        "user.name=Test",
        "commit",
        "-m",
        msg);
    runGit(workspacePath, "git", "push", "origin", "HEAD");
  }

  private void runGit(Path cwd, String... command) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0) {
      String out = new String(p.getInputStream().readAllBytes());
      throw new RuntimeException("git " + String.join(" ", command) + " failed: " + out);
    }
  }

  @Test
  public void testFileContentMissingFileReturns404() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "files", "master", "files");
    given()
        .contentType(ContentType.JSON)
        .when()
        .get(
            "/workspaces/api/workspaces/"
                + workspaceIds.of(repoId, "files")
                + "/files/content?path=does-not-exist.txt")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }







  @Test
  public void testCreateWorkspaceRejectsPathTraversalAndFlagIds() {
    String repoId = createProjectAndRepository();
    // A workspace id becomes a path segment + git operand; slashes/dots/leading-dash are rejected.
    for (String badId : new String[] {"../escape", "a/b", "-D", "."}) {
      given()
          .contentType(ContentType.JSON)
          .body(new WorkspaceController.CreateWorkspaceRequest(repoId, badId, "master", "wt-branch", null))
          .when()
          .post("/workspaces/api/workspaces")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
    }
  }

  @Test
  public void stopThenEnsureContainerRoundTripsTheRuntimeStatus() {
    String repoId = createProjectAndRepository();

    // A regular DIRECT row as the estate still holds them (qits-774): it has no container yet, so
    // it reports STOPPED.
    createWorkspace(repoId, "wt-run", "master", "run-branch");
    assertRuntimeStatus(repoId, "wt-run", "STOPPED");

    // First use provisions the container from the durable branch (asynchronously — the response
    // carries the technical-process id; completion is awaited via /active-process).
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "wt-run") + "/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    awaitProvisioned(repoId, "wt-run");
    assertRuntimeStatus(repoId, "wt-run", "RUNNING");

    // Graceful stop removes the container but keeps the workspace active (STOPPED).
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "wt-run") + "/stop-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("runtimeStatus", equalTo("STOPPED"));

    // Ensure re-provisions from the durable branch — the container comes back RUNNING.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "wt-run") + "/ensure-container")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("technicalProcessId", notNullValue());
    awaitProvisioned(repoId, "wt-run");
    assertRuntimeStatus(repoId, "wt-run", "RUNNING");
  }

  @Test
  public void recreateContainerRejectsAWorkspaceWhoseCleanlinessIsUnknown() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "rc-unknown", "master", "rc-unknown-branch");

    // No workspace-daemon is connected in a @QuarkusTest, so the registry reports the working tree
    // as UNKNOWN — recreate must refuse it (unknown is not a safe basis to destroy a container),
    // surfacing the domain BadRequestException as a 400.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "rc-unknown") + "/recreate-container")
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void recreateContainerIs404ForAnUnknownWorkspace() {
    createProjectAndRepository();
    // An id no workspace has. It needs no repository beside it to be unknown — that is the point
    // of an identifier.
    given()
        .contentType(ContentType.JSON)
        .when()
        .post("/workspaces/api/workspaces/-1/recreate-container")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  /** Asserts the workspace's runtime status through the list endpoint. */
  private void assertRuntimeStatus(String repoId, String workspaceId, String expected) {
    given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == '"
                + workspaceId
                + "' }"
                + ".workspace.runtimeStatus",
            equalTo(expected));
  }

  private static final String INLINE_COMPONENT =
      """
      import { Component } from '@angular/core';

      @Component({
        selector: 'app-greeting',
        template: `
          @if (greeting(); as g) {
            <h1>Hello, {{ g.name }}!</h1>
          }
        `,
      })
      export class Greeting {}
      """;

  private String componentMapUrl(String repoId) {
    return "/workspaces/api/workspaces/" + workspaceIds.of(repoId, "master") + "/component-map";
  }






  private String detectionUrl(String repoId) {
    return "/workspaces/api/workspaces/" + workspaceIds.of(repoId, "master") + "/detection";
  }

  /**
   * The single-workspace read: the same row the collection serves, addressable without knowing the
   * repository. That is the whole point — the detail page opens from a bare id, and the repository
   * was only ever a filter here.
   */
  @Test
  public void testGetWorkspaceServesTheSameRowAsTheCollection() {
    String repoId = createProjectAndRepository();
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(
                repoId, "detail-01", "feature", "detail-work", "why this exists"))
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());
    Long id = workspaceIds.of(repoId, "detail-01");

    given()
        .get("/workspaces/api/workspaces/" + id)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("workspace.id", equalTo(id.intValue()))
        .body("workspace.workspaceId", equalTo("detail-01"))
        .body("workspace.branch", equalTo("detail-work"))
        .body("workspace.parent", equalTo("feature"))
        .body("workspace.status", equalTo("ACTIVE"))
        .body("workspace.preamble", equalTo("why this exists"))
        // The repository's default branch rides along, because "may this be integrated or must it
        // be released?" is `parent == repositoryMainBranch` and asking another service for that one
        // string would be a second request per page.
        .body("workspace.repositoryMainBranch", equalTo("master"))
        // Same createdAt the collection serves, and the same shape — one client cache holds both.
        .body("workspace.createdAt", matchesPattern(ISO_INSTANT))
        // Not a second shape: the status strip reads runtime, daemon and cleanliness off exactly
        // the fields the list already carries, so one client cache holds both.
        .body("workspace", hasKey("runtimeStatus"))
        .body("workspace", hasKey("daemonVersion"))
        .body("workspace", hasKey("agentActivity"))
        .body("workspace", hasKey("clean"));
  }

  /**
   * A resolved workspace is 404 here, deliberately. It has no container, no daemon and no branch to
   * be ahead of anything, so this read would answer with a row whose live half is uniformly null;
   * {@code /history/{id}} is where the narrative record stays readable, and the client routes there
   * instead of rendering half a detail view.
   */
  @Test
  public void testGetWorkspaceIs404ForAResolvedOneAndForAnUnknownId() {
    String repoId = createProjectAndRepository();
    // Recorded and left stopped: the create door would start it, and a container with no daemon to
    // report its tree clean refuses the discard below.
    Long id = workspaceService.createWorkspace(repoId, "detail-02", "feature", "detail-gone").id;

    given().get("/workspaces/api/workspaces/" + id).then().statusCode(200);

    workspaceService.discardWorkspace(id, null);

    given().get("/workspaces/api/workspaces/" + id).then().statusCode(404);
    // ...and the history record still answers, which is the route the client falls back to.
    given().get("/workspaces/api/history/" + id).then().statusCode(200);

    given().get("/workspaces/api/workspaces/999999").then().statusCode(404);
  }
}
