package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.FakeCredentialCommissioner;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceCredential;
import eu.wohlben.qits.workspaces.control.WorkspaceCredentials;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/branches/resolution?repositoryId=…} — the door qits-projects' Auto
 * Release calls beside the branch deletion it just made.
 *
 * <p>It is a <b>workspace-lifecycle</b> door that a release happens to call, so what is asserted
 * here is the lifecycle: the row resolves, the container and its volume go, the credential goes
 * back, the timeline carries the release's version and sha — and the main workspace is refused on
 * both belts. The release half of it is one query parameter and two strings on an event.
 *
 * <p>The two cheap answers matter as much as the teardown. A branch with no workspace is {@code
 * resolved:false} and not a 404, because that is what most released branches are; and a second call
 * answers the same, because the caller is best-effort and free to retry.
 */
@QuarkusTest
public class BranchResolutionControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;
  @Inject FakeCredentialCommissioner commissioner;
  @Inject WorkspaceCredentials credentials;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceIds workspaceIds;
  @Inject ContainerRuntime containers;

  @BeforeEach
  void wireAnIssuer() {
    commissioner.reset();
    commissioner.wire();
  }

  @AfterEach
  void unwireTheIssuer() {
    commissioner.reset();
  }

  private String seedOrigin() {
    try {
      String repoId = TestOrigin.create(dataDir);
      repositories.register(repoId);
      workspaceService.createMainWorkspace(repoId, "master");
      return repoId;
    } catch (Exception e) {
      throw new IllegalStateException("failed to seed a test origin", e);
    }
  }

  /** The body carries nulls for the optional members, so a HashMap rather than {@code Map.of}. */
  private static Map<String, Object> body(
      String branch, String target, String commit, String result) {
    Map<String, Object> body = new HashMap<>();
    body.put("branch", branch);
    body.put("target", target);
    body.put("commit", commit);
    body.put("result", result);
    return body;
  }

  private JsonPath resolve(String repoId, Map<String, Object> body, int expectedStatus) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/branches/resolution?repositoryId=" + repoId)
        .then()
        .statusCode(expectedStatus)
        .extract()
        .jsonPath();
  }

  @Test
  public void theWorkspaceOnAReleasedBranchResolvesAsIntegrated() {
    String repoId = seedOrigin();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    Long rowId = workspaceIds.of(repoId, "feat");
    workspaceService.ensureContainer(rowId);
    String container = containers.containerName("feat", repoId);
    WorkspaceCredential credential = credentials.forWorkspace(rowId).orElseThrow();

    JsonPath answer =
        resolve(repoId, body("feat", "2026.905.120000", "0123456789abcdef", "released"), 200);

    assertThat(answer.getBoolean("resolved"), is(true));
    assertThat(answer.getLong("workspaceId"), is(rowId));

    // The row: soft-deleted with a resolution stamp, and readable through the history route the
    // client falls back to.
    JsonPath resolved =
        given().get("/workspaces/api/history/" + rowId).then().statusCode(200).extract().jsonPath();
    assertThat(resolved.getString("workspace.status"), is(WorkspaceStatus.INTEGRATED.name()));
    assertThat(resolved.getString("workspace.resolvedAt"), is(notNullValue()));
    assertThat(
        resolved.getList("workspace.events.type", String.class),
        hasItem(WorkspaceEventType.INTEGRATED.name()));
    assertThat(
        resolved.getList("workspace.events.target", String.class), hasItem("2026.905.120000"));
    assertThat(
        resolved.getList("workspace.events.commit", String.class), hasItem("0123456789abcdef"));

    // The teardown the whole endpoint exists for.
    assertThat(containers.exists(container), is(false));
    assertThat(
        containers.listWorkspaceVolumes().stream().anyMatch(v -> repoId.equals(v.repoId())),
        is(false));
    assertThat(commissioner.decommissioned(), is(List.of(credential.clientId())));
  }

  @Test
  public void aBranchWithNoWorkspaceAnswersFalseAndTearsNothingDown() {
    String repoId = seedOrigin();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    JsonPath answer = resolve(repoId, body("never-had-a-workspace", "v1", null, null), 200);

    assertThat(answer.getBoolean("resolved"), is(false));
    assertThat(answer.get("workspaceId"), is(nullValue()));
    assertThat(containers.exists(containers.containerName("feat", repoId)), is(true));
    assertThat(commissioner.decommissioned(), is(List.of()));
  }

  /** Belt one: the row has no parent. The registry's main branch is moved out of the way first. */
  @Test
  public void theMainWorkspaceIsRefusedBecauseItHasNoParent() {
    String repoId = seedOrigin();
    repositories.setMainBranch(repoId, "trunk");

    JsonPath refusal = resolve(repoId, body("master", "v1", null, null), 400);

    assertThat(refusal.getString("message"), containsString("no parent branch"));
  }

  /** Belt two: a row that HAS a parent, standing on what the registry now calls the main branch. */
  @Test
  public void aWorkspaceOnTheDefaultBranchIsRefusedEvenWithAParent() {
    String repoId = seedOrigin();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    repositories.setMainBranch(repoId, "feat");

    JsonPath refusal = resolve(repoId, body("feat", "v1", null, null), 400);

    assertThat(refusal.getString("message"), containsString("default branch"));
  }

  @Test
  public void aSecondCallAfterTheResolutionAnswersFalse() {
    String repoId = seedOrigin();
    workspaceService.createWorkspace(repoId, "feat", "master", "feat", null);
    workspaceService.ensureContainer(workspaceIds.of(repoId, "feat"));

    assertThat(resolve(repoId, body("feat", "v1", null, null), 200).getBoolean("resolved"), is(true));
    int handedBack = commissioner.decommissioned().size();

    JsonPath again = resolve(repoId, body("feat", "v1", null, null), 200);

    assertThat(again.getBoolean("resolved"), is(false));
    assertThat(commissioner.decommissioned().size(), is(handedBack));
  }
}
