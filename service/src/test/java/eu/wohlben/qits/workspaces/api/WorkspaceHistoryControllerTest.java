package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The two agent-transcript doors on the history record, over real files in a real directory.
 *
 * <p><b>Why these are history routes and not workspace routes.</b> The whole point of the feature is
 * a workspace that no longer exists: resolving one destroys its container and its {@code /workspace}
 * volume, and the workspace routes 404 a resolved row by design. {@code /history/{id}} is already
 * status-agnostic, so the sessions hang there — and the cases below use a workspace that has been
 * discarded, because that is the state the feature is for.
 *
 * <p>The attribution rule itself is proved in {@code ClaudeTranscriptArchiveTest}, without an
 * application. What is proved here is the wiring: the roles, the response shapes, and the two ways
 * to get a 404.
 */
@QuarkusTest
public class WorkspaceHistoryControllerTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @ConfigProperty(name = "qits.workspace.claude-archive-root")
  String archiveRoot;

  private Path sessionsDir() throws IOException {
    Path dir = Path.of(archiveRoot).resolve(".claude").resolve("projects").resolve("-workspace");
    Files.createDirectories(dir);
    return dir;
  }

  /** A workspace on a branch of its own, so no two cases here can attribute each other's files. */
  private Long workspace(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, label, "master", label);
    return workspaceIds.of(repoId, label);
  }

  private static String turn(String type, String text, String branch, Instant at) {
    return "{\"type\":\""
        + type
        + "\",\"gitBranch\":\""
        + branch
        + "\",\"timestamp\":\""
        + at
        + "\",\"message\":{\"content\":\""
        + text
        + "\"}}";
  }

  private void writeSession(String sessionId, List<String> lines) throws IOException {
    Path file = sessionsDir().resolve(sessionId + ".jsonl");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
  }

  private static String sessionsUrl(Long id) {
    return "/workspaces/api/history/" + id + "/agent-sessions";
  }

  /**
   * A workspace nobody ever ran an agent in answers an empty list and a 200. That is the ordinary
   * state for most of this platform's history — every workspace resolved before the volume was
   * mounted here has no sessions — and reporting it as an error would make the history page fail for
   * the majority of its rows.
   */
  @Test
  public void aWorkspaceWithNoSessionsAnswersAnEmptyList() throws Exception {
    Long id = workspace("history-no-agent");

    given().get(sessionsUrl(id)).then().statusCode(200).body("sessions", hasSize(0));
  }

  /**
   * The feature's actual case: the workspace is discarded — container and {@code /workspace} volume
   * destroyed — and its sessions are still readable, because they were never on either.
   */
  @Test
  public void aResolvedWorkspaceStillAnswersItsSessions() throws Exception {
    String label = "history-resolved";
    Long id = workspace(label);
    Instant at = Instant.now();
    writeSession(
        "resolved-session",
        List.of(
            "{\"type\":\"queue-operation\",\"timestamp\":\"" + at + "\"}",
            turn("user", "do the work", label, at),
            turn("assistant", "did the work", label, at.plusSeconds(20))));

    // Forced: the fixture's fake container reports a dirty tree, and what is under test is what
    // survives the resolution rather than the guard in front of it.
    workspaceService.discardWorkspace(id, null, true);

    JsonPath answer = given().get(sessionsUrl(id)).then().statusCode(200).extract().jsonPath();
    assertThat(answer.getList("sessions"), hasSize(1));
    assertEquals("resolved-session", answer.getString("sessions[0].sessionId"));
    assertThat(answer.getInt("sessions[0].messageCount"), is(2));
  }

  /**
   * The transcript is the harness's own lines, unrendered — the envelopes are the frontend's render
   * contract, so reshaping them on the way out would fork it.
   */
  @Test
  public void theTranscriptDoorAnswersTheRawLines() throws Exception {
    String label = "history-transcript";
    Long id = workspace(label);
    Instant at = Instant.now();
    String first = turn("user", "read it back", label, at);
    writeSession("transcript-session", List.of(first));

    JsonPath answer =
        given()
            .get(sessionsUrl(id) + "/transcript-session/transcript")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();

    assertEquals(List.of(first), answer.getList("lines", String.class));
  }

  /**
   * A session id that does not attribute to this workspace is a 404, indistinguishable from one that
   * never existed. The volume holds the estate's shared harness credential and history beside the
   * transcripts, so the id is matched against the listing and never joined onto a path.
   */
  @Test
  public void aSessionThisWorkspaceDoesNotOwnIsNotFound() throws Exception {
    String label = "history-unowned";
    Long id = workspace(label);
    Instant at = Instant.now();
    writeSession("another-workspaces-session", List.of(turn("user", "hi", "ticket/elsewhere", at)));

    given().get(sessionsUrl(id) + "/another-workspaces-session/transcript").then().statusCode(404);
    given().get(sessionsUrl(id) + "/never-existed/transcript").then().statusCode(404);
    // Not a path, so there is nothing for a traversal to reach: it is simply an unlisted id.
    given().get(sessionsUrl(id) + "/..%2F..%2F.credentials/transcript").then().statusCode(404);
  }

  /** An unknown workspace row is a 404 on both doors, as every other history route answers. */
  @Test
  public void anUnknownWorkspaceIsNotFound() {
    given().get(sessionsUrl(9_999_999L)).then().statusCode(404);
    given().get(sessionsUrl(9_999_999L) + "/whatever/transcript").then().statusCode(404);
  }
}
