package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the workspace path bug.
 *
 * <p>Host git used to run with its working directory set to the bare origin, so a <em>relative</em>
 * data dir (as in dev) put the workspace path nested under origin instead of under the repository's
 * workspaces directory — leaving {@code list}/{@code merge}/{@code discard} unable to find it on
 * disk. {@code GitMirrorRegistry} resolves {@code qits.workspaces.data-dir} to an absolute path
 * once, at construction, which is what makes the bug unreachable rather than fixed; this test keeps
 * a relative one exercised through a whole lifecycle so the property stays asserted. The other
 * controller tests use an absolute temp dir and so never covered it.
 */
@QuarkusTest
@TestProfile(WorkspaceRelativeDataDirTest.TestProfile.class)
public class WorkspaceRelativeDataDirTest {

  /**
   * <b>The two relative paths are the regression, not a fixture.</b> Every other class in this module
   * runs against an absolute data dir, which is precisely the configuration under which the bug
   * could not happen — so a profile that shared theirs would still pass while asserting nothing. It
   * is also the one place in this module where a {@code qits.test.origins-dir} override survived the
   * cull: the others minted a throwaway temp directory for isolation {@code TestOrigin} already
   * gives, whereas this one is relative on purpose, which is the opposite of throwaway.
   */
  public static class TestProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Both deliberately relative (they resolve under the module's target/ build dir): the tree
      // under test is the service's own, and the fixture origins are relative beside it so the
      // whole flow runs with nothing pre-absolutised.
      return Map.of(
          "qits.workspaces.data-dir", "target/qits-rel-workspace-own",
          "qits.test.origins-dir", "target/qits-rel-workspace-test");
    }
  }

  /**
   * A repository with a bare origin on disk and a resolvable id, seeded in-JVM.
   *
   * <p>The monorepo drove POST /api/projects and POST /api/projects/{id}/repositories to build this
   * fixture. Those routes belong to the projects and repositories contexts and are not part of this
   * jar, so the same state is set up directly instead — the endpoints under test here are the
   * workspace ones below, not the seeding ones.
   */
  @jakarta.inject.Inject eu.wohlben.qits.workspaces.control.WorkspaceIds workspaceIds;

  @jakarta.inject.Inject
  eu.wohlben.qits.workspaces.control.FakeRepositoryLookup repositories;

  @org.eclipse.microprofile.config.inject.ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

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
  public void testFullLifecycleWithRelativeDataDir() {
    String repoId = createProjectAndRepository();

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspaceController.CreateWorkspaceRequest(repoId, "rel-01", "master", "rel-branch", null))
        .when()
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(Response.Status.OK.getStatusCode());

    // Workspace must be discoverable on disk: its forked branch resolves to "rel-branch".
    // This is the assertion that fails when the path is created nested under origin.
    given()
        .contentType(ContentType.JSON)
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body(
            "entries.find { it.workspace.workspaceId == 'rel-01' }.workspace.branch",
            equalTo("rel-branch"));

    // merge + discard must also find the workspace on disk. The target is "feature" rather than
    // "master": master is this repository's default branch, and the merge endpoint refuses that
    // one now (integrate is the only door). What is under test here is the on-disk path, which is
    // the same either way.
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.MergeWorkspaceRequest("feature"))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "rel-01") + "/merge")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("hasConflicts", equalTo(false));

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.DiscardWorkspaceRequest(null))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, "rel-01") + "/discard")
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .body("success", equalTo(true));
  }
}
