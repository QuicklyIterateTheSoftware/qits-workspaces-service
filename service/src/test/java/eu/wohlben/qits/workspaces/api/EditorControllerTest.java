package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor door: one idempotent route that a client polls and reads four scalars off.
 *
 * <p>The contract under test is the whole of what the SPA depends on — <b>no parameters</b>, the bare
 * (envelope-free) body, the row id as a String, 201 for a start, and {@code editorReady} false while
 * nothing has reported. The last of those is not a placeholder: until the daemon's {@code
 * EditorState} frame reaches the registry there is no report to have, and a door that claimed
 * readiness anyway would send a reader to an origin that answers nothing.
 *
 * <p><b>The two refusals this class used to assert are gone rather than moved</b>: a repository that
 * was not a project's wrapper (400) and one that did not exist (404) were answers to a parameter
 * that no longer exists. There is one editor, so there is nothing a caller can name wrongly.
 *
 * <p>The 201 → 200 transition is {@code EditorServiceTest}'s rather than this file's: whether a
 * second call starts anything depends on the container being up with its daemon on the socket, and
 * no {@code @QuarkusTest} here has one — the runtime is faked and nothing dials home.
 */
@QuarkusTest
public class EditorControllerTest {

  @Inject WorkspaceRepository workspaces;

  /** The row id of the one ACTIVE editor workspace, read straight off the table. */
  private Long editorRowId() {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaces.findActiveEditor().map(editor -> editor.id).orElse(null));
  }

  /**
   * The editor is one row for the whole database and the database outlives a test method, so a class
   * that asserts a 201 has to arrange for there to be no editor rather than assume it.
   */
  @BeforeEach
  void noEditorYet() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                workspaces
                    .findActiveEditor()
                    .ifPresent(
                        editor -> {
                          editor.status = WorkspaceStatus.ABANDONED;
                          editor.resolvedAt = Instant.now();
                        }));
  }

  @Test
  public void theFirstCallStartsTheEditorAndTheSecondFindsIt() {
    // 201: this call started something. No query parameters and an empty body — there is one editor,
    // so there is nothing to scope it by. The body is BARE — four scalars, no envelope — because the
    // client polls it every two seconds and reads them directly.
    String workspaceId =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post("/workspaces/api/editor/ensure")
            .then()
            .statusCode(201)
            .body("workspaceId", notNullValue())
            .body("containerStatus", notNullValue())
            // Nothing has reported: no daemon frame reaches the registry yet, so the state is null
            // and the readiness is false. A client waits, which is exactly right.
            .body("editorState", nullValue())
            .body("editorReady", equalTo(false))
            .extract()
            .path("workspaceId");

    // The id is the workspace ROW id as a String — the identity /stop-container and
    // /recreate-container address. A branch label here would 404 both of them.
    assertEquals(
        editorRowId(),
        Long.valueOf(workspaceId),
        "the door names the one editor workspace, by its row id");

    // Said again, it finds what the first call made rather than making a second one. The row is the
    // platform's single editor, so idempotence is structural and not a guard.
    //
    // The STATUS is deliberately not asserted here. `fresh` means "this call started something",
    // and whether the second one does depends on the container being up with its daemon on the
    // socket — which no @QuarkusTest has, since the runtime is faked and nothing dials home. The
    // 201 → 200 transition is EditorServiceTest's, where the liveness port can be told what to say.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/workspaces/api/editor/ensure")
        .then()
        .body("workspaceId", equalTo(workspaceId))
        .body("editorReady", equalTo(false));
  }

  @Test
  public void aStrayRepositoryIdIsIGNOREDRatherThanHonoured() {
    // The parameter the client used to send. A browser tab open across a deploy, or a bookmarked
    // request, will send it for a while yet — and the honest answer is the editor, because there is
    // only one and nothing could scope it. This is asserted rather than left to JAX-RS so that
    // reintroducing a parameter here would be a deliberate act with a red test in front of it.
    given()
        .contentType(ContentType.JSON)
        .queryParam("repositoryId", "no-such-repository")
        .body("{}")
        .when()
        .post("/workspaces/api/editor/ensure")
        .then()
        .statusCode(201)
        .body("workspaceId", notNullValue());
  }
}
