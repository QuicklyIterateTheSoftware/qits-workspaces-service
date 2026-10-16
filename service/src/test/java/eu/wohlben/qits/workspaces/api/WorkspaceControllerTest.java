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

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository agentWorktrees;

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
   * An ADMIN workspace, written STOPPED and not started: the tests using it prove what the DIRECT
   * container verbs do, and the direct path is admin only (qits-780, qits-1152). Made through the
   * service: there is no create door any more (qits-1152), and these tests start it themselves.
   */
  private void createWorkspace(String repoId, String id, String parent, String branch) {
    workspaceService.createWorkspace(repoId, id, parent, branch, null, false, false, true);
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

  /**
   * Recreate asks the slot's agents whether their worktrees hold unpushed work (qits-1263). No
   * workspace-daemon is connected in a @QuarkusTest, so an agent on the slot cannot be checked —
   * recreate must refuse (an unknowable worktree is not a safe basis to destroy a container) with
   * the 400 it has always answered, naming the agent and saying why in {@code code}.
   */
  @Test
  public void recreateContainerRejectsAWorkspaceWhoseAgentsCannotBeChecked() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "rc-unknown", "master", "rc-unknown-branch");
    Long rowId = workspaceIds.of(repoId, "rc-unknown");
    java.util.UUID agentId = java.util.UUID.randomUUID();
    io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
        .run(
            () -> {
              eu.wohlben.qits.workspaces.entity.AgentWorktree agent =
                  new eu.wohlben.qits.workspaces.entity.AgentWorktree();
              agent.id = agentId;
              agent.repositoryId = repoId;
              agent.workId = "w-" + agentId;
              agent.entityId = "qits-1263";
              agent.wrapperBranch = "ticket/w-" + agentId;
              agent.workspaceId = rowId;
              agent.runState = eu.wohlben.qits.workspaces.entity.AgentRunState.RUNNING;
              agent.createdAt = java.time.Instant.now();
              agent.gitRefs = "[]";
              agentWorktrees.persist(agent);
            });
    try {
      given()
          .contentType(ContentType.JSON)
          .when()
          .post("/workspaces/api/workspaces/" + rowId + "/recreate-container")
          .then()
          .statusCode(Response.Status.BAD_REQUEST.getStatusCode())
          .body("code", equalTo("AGENT_DAEMON_UNREACHABLE"))
          .body("message", containsString("agent " + agentId + " (work item qits-1263)"));
    } finally {
      io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
          .run(
              () ->
                  agentWorktrees.findByIdOptional(agentId)
                      .ifPresent(
                          a -> a.state = eu.wohlben.qits.workspaces.entity.AgentState.REMOVED));
    }
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
