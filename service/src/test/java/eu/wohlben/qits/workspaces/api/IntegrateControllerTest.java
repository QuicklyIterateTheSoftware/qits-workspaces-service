package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeGitHostAddress;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestGit;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/workspaces/{id}/integrate} — the other door, and it never reaches the
 * default branch.
 *
 * <p>An integrate lands a workspace on <b>its parent</b>: a task branch on the epic it forked from,
 * which is later released — by a release request in qits-projects, not by anything here. So the
 * fixture every case here builds is a two-level stack, and the assertion that carries the suite is
 * what an integrate is <em>not</em>: no version anywhere near the commit, and no manifest touched.
 * Everything it <em>is</em> — the detached worktree on a private mirror, the single two-parent
 * commit, the real push, the 409 family — is {@code BranchIntegrator}, which is all that survived
 * the release door leaving this service.
 *
 * <p>Like that suite, every case runs against a <b>real bare origin</b> and performs a <b>real
 * {@code git push}</b> into it. The one production difference asserted here is the argv: an
 * integrate sends no {@code --push-option}, because the git host's hook guards the default branch
 * and this push does not touch it.
 */
@QuarkusTest
public class IntegrateControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeGitHostAddress gitHost;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;

  @BeforeEach
  void resetDoubles() {
    gitHost.reset();
  }

  // -----------------------------------------------------------------------------------------
  // fixtures
  // -----------------------------------------------------------------------------------------

  private String seedRepository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  /**
   * Records the workspace and leaves it stopped. Not the create door: that starts the container too
   * (qits-853), and a container with no daemon to report its tree clean refuses every landing — this
   * suite is about the landing, not the start.
   */
  private void createWorkspace(String repoId, String label, String parent, String branch) {
    workspaceService.createWorkspace(repoId, label, parent, branch, null);
  }

  /** The stack every case needs: {@code epic-b} off master, {@code task-b} off {@code epic-b}. */
  private String seedStack() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "epic", "master", "epic-b");
    createWorkspace(repoId, "task", "epic-b", "task-b");
    return repoId;
  }

  private io.restassured.response.Response integrate(String repoId, String label, String summary) {
    return given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.IntegrateRequest(summary))
        .when()
        .post("/workspaces/api/workspaces/" + workspaceIds.of(repoId, label) + "/integrate");
  }

  private String inOrigin(String repoId, String... argv) throws Exception {
    return TestGit.exec(Path.of(dataDir, repoId, "origin").toFile(), argv).trim();
  }

  private List<String> activeLabels(String repoId) {
    return given()
        .when()
        .get("/workspaces/api/workspaces?repositoryId=" + repoId)
        .then()
        .statusCode(Response.Status.OK.getStatusCode())
        .extract()
        .path("entries.workspace.workspaceId");
  }

  /** A minimal but real maven reactor root — the thing a release bump would rewrite, elsewhere. */
  private static final String POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>fixture</artifactId>
          <version>1.0.0-SNAPSHOT</version>
      </project>
      """;

  // -----------------------------------------------------------------------------------------
  // the happy path
  // -----------------------------------------------------------------------------------------

  /**
   * The whole of what an integrate is: one commit with two parents on the <b>parent branch</b>, the
   * manifests untouched, and the default branch exactly where it was.
   *
   * <p>"Untouched" is asserted as the pom being <b>byte-identical</b> across the merge rather than as
   * an absence of a version string. A release's bump is a splice into the original text, so a bump
   * that ran and produced a coincidentally similar file would pass a substring check and fail this
   * one.
   */
  @Test
  public void anIntegrateIsOneMergeCommitOnTheParentWithNoVersionAnywhere() throws Exception {
    String repoId = seedStack();
    // The pom rides in on the task branch, so it is in the merged tree either way — which is what
    // makes "byte-identical" a statement about the bump rather than about the file's absence.
    TestOrigin.commitOnBranch(dataDir, repoId, "task-b", "pom.xml", POM, "add a pom");
    TestOrigin.commitOnBranch(dataDir, repoId, "task-b", "feature.md", "shipped\n", "the work");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");
    String epicBefore = inOrigin(repoId, "git", "rev-parse", "epic-b");
    String sourceTip = inOrigin(repoId, "git", "rev-parse", "task-b");

    var response =
        integrate(repoId, "task", "fold the parser into the epic")
            .then()
            .statusCode(Response.Status.OK.getStatusCode())
            .body("commitSha", not(emptyOrNullString()))
            .body("branch", equalTo("task-b"))
            .body("targetBranch", equalTo("epic-b"))
            .body("version", nullValue())
            .extract();
    String commitSha = response.path("commitSha");

    // The push moved the PARENT's ref, and moved it to exactly what the caller was told.
    assertEquals(commitSha, inOrigin(repoId, "git", "rev-parse", "epic-b"));
    assertEquals(
        masterBefore,
        inOrigin(repoId, "git", "rev-parse", "master"),
        "an integrate never touches the default branch — nothing here writes it");

    // ONE commit, TWO parents, and they are the two tips that went in.
    String parents = inOrigin(repoId, "git", "rev-list", "--parents", "-n", "1", "epic-b");
    assertEquals(
        List.of(commitSha, epicBefore, sourceTip),
        List.of(parents.split(" ")),
        "the integrate must be a single merge commit of the old parent and the source");

    assertEquals(
        "integrate(task-b): fold the parser into the epic",
        inOrigin(repoId, "git", "log", "-1", "--format=%s", "epic-b"),
        "the scope is the SOURCE branch, and the subject is not a release(...)");
    assertEquals(
        "Integrates workspace branch `task-b` into `epic-b`.",
        inOrigin(repoId, "git", "log", "-1", "--format=%b", "epic-b").trim(),
        "the body names both branches, which the merge's parents record only as shas");

    // The manifest is byte-identical: nothing stamped it, and the placeholder version survives.
    assertEquals(
        POM.trim(),
        inOrigin(repoId, "git", "show", "epic-b:pom.xml"),
        "a plain integrate bumps nothing");
    assertEquals("shipped", inOrigin(repoId, "git", "show", "epic-b:feature.md"));
    assertEquals(
        "",
        inOrigin(repoId, "git", "diff", "--name-only", sourceTip, commitSha),
        "the merge commit's tree is the source's tree: no file changed on the way in");

    // The workspace resolved.
    assertTrue(!activeLabels(repoId).contains("task"), "an integrated workspace leaves the listing");
    assertTrue(activeLabels(repoId).contains("epic"), "the parent workspace is untouched");

    assertEquals(
        "",
        inOrigin(repoId, "git", "tag", "-l"),
        "an integrate stamps no version, so there is nothing for a tag to name");
    assertEquals(
        "",
        inOrigin(
            repoId,
            "git",
            "for-each-ref",
            "--format=%(refname)",
            "refs/heads/environment",
            "refs/heads/platform"),
        "one push, one ref: this flow writes nothing beside the target branch");
  }

  // -----------------------------------------------------------------------------------------
  // the wrong door
  // -----------------------------------------------------------------------------------------

  /**
   * A workspace forked straight off the default branch has no parent to integrate into — its parent
   * <em>is</em> the branch this service does not write. It is refused with the reason a client can
   * branch on, so the UI offers the release path instead of word-matching prose.
   */
  @Test
  public void aWorkspaceWhoseParentIsTheDefaultBranchIsSentToTheReleaseFlow() throws Exception {
    String repoId = seedRepository();
    createWorkspace(repoId, "straight", "master", "straight-b");
    TestOrigin.commitOnBranch(dataDir, repoId, "straight-b", "notes.txt", "hi\n", "a note");

    String masterBefore = inOrigin(repoId, "git", "rev-parse", "master");

    integrate(repoId, "straight", "nowhere to fold this")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("RELEASE_REQUIRED"))
        .body("message", containsString("release request"));

    assertEquals(masterBefore, inOrigin(repoId, "git", "rev-parse", "master"));
    assertTrue(activeLabels(repoId).contains("straight"), "nothing was attempted");
  }

  // -----------------------------------------------------------------------------------------
  // the refusals
  // -----------------------------------------------------------------------------------------

  /**
   * The 409 family is the flow's, not the endpoint's. A conflict leaves the <b>parent</b> branch
   * byte-identical: the merge happens in a detached worktree on a mirror nobody serves, and only
   * the push moves a ref.
   */
  @Test
  public void aConflictIsA409WithTheFileListAndLeavesTheParentByteIdentical() throws Exception {
    String repoId = seedStack();
    TestOrigin.commitOnBranch(dataDir, repoId, "epic-b", "shared.txt", "base\n", "base");
    TestOrigin.commitOnBranch(dataDir, repoId, "task-b", "shared.txt", "theirs\n", "their edit");
    TestOrigin.commitOnBranch(dataDir, repoId, "epic-b", "shared.txt", "ours\n", "our edit");

    String epicBefore = inOrigin(repoId, "git", "rev-parse", "epic-b");

    integrate(repoId, "task", "this will not apply")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("message", containsString("conflict"))
        .body("reason", equalTo("CONFLICT"))
        .body("conflicts", hasItem("shared.txt"));

    assertEquals(epicBefore, inOrigin(repoId, "git", "rev-parse", "epic-b"));
    assertTrue(activeLabels(repoId).contains("task"), "a refused integrate resolves nothing");
  }

  /** What a lost 200 looks like on the retry, at this door too. */
  @Test
  public void anAlreadyIntegratedBranchIsRefusedRatherThanMergedAgain() throws Exception {
    String repoId = seedStack();
    TestOrigin.commitOnBranch(dataDir, repoId, "task-b", "done.txt", "done\n", "the work");
    inOrigin(repoId, "git", "branch", "-f", "epic-b", "task-b");
    String epicBefore = inOrigin(repoId, "git", "rev-parse", "epic-b");

    integrate(repoId, "task", "already in")
        .then()
        .statusCode(Response.Status.CONFLICT.getStatusCode())
        .body("reason", equalTo("ALREADY_INTEGRATED"))
        .body("message", containsString("already integrated"));

    assertEquals(epicBefore, inOrigin(repoId, "git", "rev-parse", "epic-b"));
  }

  // -----------------------------------------------------------------------------------------
  // the request
  // -----------------------------------------------------------------------------------------

  @Test
  public void aSummaryIsRequired() throws Exception {
    String repoId = seedStack();

    integrate(repoId, "task", "  ").then().statusCode(Response.Status.BAD_REQUEST.getStatusCode());
  }

  @Test
  public void anUnknownWorkspaceIs404() {
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspaceController.IntegrateRequest("nobody home"))
        .when()
        .post("/workspaces/api/workspaces/999999/integrate")
        .then()
        .statusCode(Response.Status.NOT_FOUND.getStatusCode());
  }
}
