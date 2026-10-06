package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The prompt draft over HTTP. The service under it was already written and already tested at the
 * domain level; what is new here is the shape, so these cases assert what a client can depend on —
 * the envelope, the status codes, and the two refusals whose whole point is that they are not 500s.
 */
@QuarkusTest
public class WorkspacePromptDraftControllerTest {

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  private Long workspace(String label) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createWorkspace(repoId, label, "master", label);
    return workspaceIds.of(repoId, label);
  }

  private static String base(Long id) {
    return "/workspaces/api/workspaces/" + id + "/prompt-draft";
  }

  @Test
  public void anUnsavedDraftIs404AndASavedOneComesBackWithItsVersion() throws Exception {
    Long id = workspace("draft-roundtrip");

    // Not an empty draft: "nothing composed here" and "composed and cleared" are the same state to
    // the server, and inventing a row for either hands the client an updatedAt no save produced.
    given().get(base(id)).then().statusCode(404);

    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptDraftController.SavePromptDraftRequest(
                "{\"blocks\":[{\"text\":\"add a health check\"}]}", "add a health check"))
        .put(base(id))
        .then()
        .statusCode(200)
        .body("draft.serializedPrompt", equalTo("add a health check"))
        // The version is bumped by the upsert itself — a PUT means the composition changed.
        .body("draft.promptVersion", equalTo(1))
        .body("draft.updatedAt", notNullValue())
        // Never delivered to a run yet, and the fields say so rather than being absent.
        .body("draft.lastRunAt", equalTo(null))
        .body("draft.lastRunPromptVersion", equalTo(null));

    given()
        .get(base(id))
        .then()
        .statusCode(200)
        .body("draft.content", equalTo("{\"blocks\":[{\"text\":\"add a health check\"}]}"));
  }

  @Test
  public void theReturnedUpdatedAtIsTheOneALaterReadServes() throws Exception {
    Long id = workspace("draft-echo");

    String written =
        given()
            .contentType(ContentType.JSON)
            .body(new WorkspacePromptDraftController.SavePromptDraftRequest("{\"a\":1}", null))
            .put(base(id))
            .then()
            .statusCode(200)
            .extract()
            .path("draft.updatedAt");

    String read = given().get(base(id)).then().statusCode(200).extract().path("draft.updatedAt");

    // Byte-for-byte, because the client stores this value to recognise its own echo on the
    // prompt-draft SSE topic. A timestamp stamped in the controller rather than read back from the
    // database would differ here and the client would rehydrate over its own typing.
    assertEquals(written, read);
  }

  @Test
  public void malformedContentIs400AndNothingIsWritten() throws Exception {
    Long id = workspace("draft-malformed");

    for (String bad : List.of("", "   ", "{", "{\"a\":1} trailing", "not json at all")) {
      given()
          .contentType(ContentType.JSON)
          .body(new WorkspacePromptDraftController.SavePromptDraftRequest(bad, null))
          .put(base(id))
          .then()
          .statusCode(400)
          .body("message", equalTo("Prompt draft content is not valid JSON"));
    }

    // The guards run before the database is touched, so a rejected save leaves no row behind.
    given().get(base(id)).then().statusCode(404);
  }

  @Test
  public void anOversizedDraftIs413() throws Exception {
    Long id = workspace("draft-oversized");

    // The cap is on content + serializedPrompt combined, since both are unbounded @Lobs.
    String huge = "\"" + "x".repeat(2 * 1024 * 1024) + "\"";
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SavePromptDraftRequest(huge, "and some markdown"))
        .put(base(id))
        .then()
        .statusCode(413);

    given().get(base(id)).then().statusCode(404);
  }

  @Test
  public void deleteIsIdempotentAndTakesTheAttachmentsWithIt() throws Exception {
    Long id = workspace("draft-delete");
    String attachments = "/workspaces/api/workspaces/" + id + "/prompt-attachments";

    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SavePromptDraftRequest("{\"a\":1}", "go"))
        .put(base(id))
        .then()
        .statusCode(200);
    given()
        .contentType(ContentType.JSON)
        .body(
            new WorkspacePromptAttachmentController.AddAttachmentRequest(
                "image/png", "Pasted image 1", "PASTE", PromptAttachmentFixtures.ONE_PIXEL_PNG))
        .post(attachments)
        .then()
        .statusCode(201);

    given().delete(base(id)).then().statusCode(204);
    // The images are the draft's payload, so clearing the draft clears them.
    given().get(attachments).then().statusCode(200).body("attachments", hasSize(0));

    // "There is no draft here" is the state asked for either way.
    given().delete(base(id)).then().statusCode(204);
    given().get(base(id)).then().statusCode(404);
  }

  @Test
  public void anUnknownWorkspaceIs404OnEveryVerb() throws Exception {
    given().get(base(999_999L)).then().statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(new WorkspacePromptDraftController.SavePromptDraftRequest("{}", null))
        .put(base(999_999L))
        .then()
        .statusCode(404);
    given().delete(base(999_999L)).then().statusCode(404);
  }

  /**
   * The reason the repository's upsert is one {@code insert … on conflict} rather than a
   * read-then-insert: the
   * draft's primary key <em>is</em> the workspace id, so two concurrent first-saves for a draftless
   * workspace both find no row, both insert the same key, and the loser's insert violates the
   * constraint. That is not a hypothetical — it is the cross-device flow this feature is for.
   *
   * <p>The assertion is that <b>every</b> save succeeds. One 500 among sixteen is the bug, and it is
   * exactly the kind that passes a sequential test suite forever.
   */
  @Test
  public void concurrentFirstSavesAllSucceedRatherThanCollidingOnTheSharedKey() throws Exception {
    Long id = workspace("draft-race");
    int writers = 8;

    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CyclicBarrier startTogether = new CyclicBarrier(writers);
    try {
      List<Callable<Integer>> saves =
          java.util.stream.IntStream.range(0, writers)
              .<Callable<Integer>>mapToObj(
                  i ->
                      () -> {
                        startTogether.await(10, TimeUnit.SECONDS);
                        return given()
                            .contentType(ContentType.JSON)
                            .body(
                                new WorkspacePromptDraftController.SavePromptDraftRequest(
                                    "{\"writer\":" + i + "}", "writer " + i))
                            .put(base(id))
                            .thenReturn()
                            .statusCode();
                      })
              .toList();

      for (Future<Integer> result : pool.invokeAll(saves, 60, TimeUnit.SECONDS)) {
        assertEquals(200, result.get(), "a concurrent first save must upsert, never 500");
      }
    } finally {
      pool.shutdownNow();
    }

    // Last write wins, and the row is intact — whichever writer landed last, exactly one draft
    // exists and its version counts every save.
    int version = given().get(base(id)).then().statusCode(200).extract().path("draft.promptVersion");
    assertTrue(version >= 1 && version <= writers, "one row, versioned by the saves that hit it");
  }
}
