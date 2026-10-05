package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The branch-level integrate and cleanup routes, ported from the monorepo's {@code
 * RepositoryControllerTest} along with the endpoints themselves.
 *
 * <p>One sibling test did not come with them: {@code testBranchesReportCanCleanup} asserted on the
 * {@code canCleanup} flag of {@code GET /branches}, which is a repositories-context route. It stays
 * with that route in {@code qits-projects}, which computes the flag through its {@code
 * WorkspaceLookup} SPI.
 */
@QuarkusTest
public class BranchControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture; those routes are not part of this jar, so the same state is set up directly — as
   * {@link WorkspaceControllerTest} does. The origin has {@code master} with two commits and a
   * diverged {@code feature}.
   */
  private String createProjectAndRepository() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      workspaceService.createMainWorkspace(repoId, "master");
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  /**
   * Records the workspace and leaves it stopped. Not the create door: that starts the container too
   * (qits-853), and a container with no daemon to report its tree clean refuses every landing — this
   * suite is about the landing, not the start.
   */
  private void createWorkspace(String repoId, String id, String parent, String branch) {
    workspaceService.createWorkspace(repoId, id, parent, branch, null);
  }

  /** The branch names present in the repository's bare origin. */
  private String originBranches(String repoId) throws Exception {
    Path originPath = Path.of(dataDir, repoId, "origin");
    return TestGit.exec(
        originPath.toFile(), "git", "for-each-ref", "--format=%(refname:short)", "refs/heads");
  }

  /**
   * A blank target still resolves to the repository's configured main branch — and that is now
   * exactly why it is refused. The default branch is written by integrate alone, and the refusal
   * names the endpoint that does it, because a refusal a caller cannot act on is a worse bug than
   * the merge would have been.
   */
  @Test
  public void testIntegrateBranchIntoTheDefaultBranchIsRefusedAndNamesIntegrate() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", null, null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("/integrate"));

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("/integrate"));
  }

  /** Every other target is untouched: merging into a parent branch is what stacking is. */
  @Test
  public void testIntegrateBranchIntoANonDefaultTargetStillMerges() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "host-wt", "master", "host-b");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "host-b", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("commitHash", not(emptyOrNullString()))
        .body("hasConflicts", equalTo(false));
  }

  @Test
  public void testIntegrateRejectsBranchIntoItself() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("master", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRejectsFlagLikeSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("-D", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateRequiresSource() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testIntegrateUnknownRepoReturns404() {
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=does-not-exist")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }

  @Test
  public void testIntegrateAutoCleansUpEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    // Stacked one level down, so the target is a parent branch rather than the default branch —
    // which is the shape the merge endpoints keep serving.
    createWorkspace(repoId, "base-wt", "master", "base-b");
    createWorkspace(repoId, "auto-wt", "base-b", "auto-b");

    // Integrating a clean, dependent-free workspace into its parent removes it afterwards.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("auto-b", "base-b", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false))
        .body("cleanedUp", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("auto-wt")));
  }

  @Test
  public void testIntegrateKeepsWorkspaceWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "gwt", "master", "gb");
    createWorkspace(repoId, "pwt", "gb", "pb");
    createWorkspace(repoId, "cwt", "pb", "cb");

    // pb still has a dependent workspace (cwt), so it must not be cleaned up after integration.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("pb", "gb", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("cleanedUp", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", hasItem("pwt"));
  }

  /**
   * <b>What moved, recorded rather than deleted.</b> This used to assert that integrating a
   * <em>plain</em> branch (one with no workspace) into the default branch left it fully merged and
   * therefore deleted it — the same auto-cleanup a workspace branch gets.
   *
   * <p>That path is gone, and not by omission. A plain branch's cleanup parent is the repository's
   * main branch by definition ({@code canCleanupBranch}), so it is only ever eligible once it is
   * merged <em>into</em> that branch — and the default branch is integrate's alone now, while
   * integrate is workspace-keyed. So: the merge is refused, and the branch survives untouched.
   * Workspace branches still resolve and are still deleted; that happens inside integrate.
   */
  @Test
  public void testIntegratePlainBranchIntoTheDefaultBranchIsRefusedAndLeavesItAlone()
      throws Exception {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "master", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("/integrate"));

    // The monorepo asserted branch existence through GET /branches, which is a repositories-context
    // route and not in this jar; the origin's ref namespace is the same fact one layer down.
    org.junit.jupiter.api.Assertions.assertTrue(
        originBranches(repoId).lines().anyMatch(b -> b.equals("feature")),
        "a refused merge deletes nothing");
  }

  /**
   * <b>The release doors are gone, and this is what says so.</b> {@code /branches/release} created
   * release requests in qits-projects and {@code /branches/execute-release} performed the landing;
   * both left with the release flow on 2026-09-03 — qits-projects folds a request's sources through
   * qits-githost, stamps, bumps and tags there, and nothing in this service writes a default branch
   * any more.
   *
   * <p>Asserted as a <b>404 on the route</b> rather than as an absent method on a class: a resource
   * method deleted from {@link BranchController} could be reintroduced anywhere in this jar under
   * the same path, and a caller only ever sees the path. RESTEasy answers an unmapped path under a
   * mapped resource with 404, which is the same answer any other unknown route gives — exactly the
   * point.
   */
  @Test
  public void theReleaseDoorsAreNotRoutedAnyMore() {
    String repoId = createProjectAndRepository();

    for (String door : new String[] {"release", "execute-release"}) {
      given()
          .contentType(ContentType.JSON)
          .body("{\"branch\":\"feature\",\"summary\":\"nothing releases here\"}")
          .when()
          .post("/workspaces/api/branches/" + door + "?repositoryId=" + repoId)
          .then()
          .statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }
  }

  @Test
  public void testCleanupBranchRemovesEligibleWorkspace() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "elig-wt", "master", "elig-b");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("elig-b", null))
        .when()
        .post("/workspaces/api/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));

    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("entries.workspace.workspaceId", not(hasItem("elig-wt")));
  }

  @Test
  public void testCleanupBranchRejectsUnmergedCommits() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "ahead-wt", "master", "ahead-b");
    // Advance ahead-b past master by integrating the diverged feature branch into it.
    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.MergeBranchRequest("feature", "ahead-b", null))
        .when()
        .post("/workspaces/api/branches/merge?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("ahead-b", null))
        .when()
        .post("/workspaces/api/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsBranchWithChildren() {
    String repoId = createProjectAndRepository();
    createWorkspace(repoId, "par-wt", "master", "par-b");
    createWorkspace(repoId, "chi-wt", "par-b", "chi-b");

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("par-b", null))
        .when()
        .post("/workspaces/api/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRejectsMainBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("master", null))
        .when()
        .post("/workspaces/api/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void testCleanupBranchRequiresBranch() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(new BranchController.CleanupBranchRequest("", null))
        .when()
        .post("/workspaces/api/branches/cleanup?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }
}
