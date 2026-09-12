package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.security.NoDevUserProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

/**
 * An agent keeps every read and gains no write (phase 4 of principal-bound-git-refs-plan.md). A
 * caller holding only {@code qits:agent} gets past the gate of every read route here, and a mixed
 * class still refuses it a write.
 *
 * <p>One test per class whose reads changed. The ids name nothing, so a read answers 404 or an
 * empty list — what matters is that it is not 401 or 403. Under {@link NoDevUserProfile}, so the
 * roles are exactly the ones the request sends.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class AgentReadAccessTest {

  private static final String NO_ROW = "999999999";

  @TestHTTPResource("/")
  URI base;

  /** Past the gate: any answer but a refusal. */
  private static Matcher<Integer> admitted() {
    return not(anyOf(is(401), is(403)));
  }

  private static RequestSpecification asAgent() {
    return given().header("X-Qits-User", "agent-1").header("X-Qits-Roles", "qits:agent");
  }

  /** The status of an event stream, read from its headers; the stream itself is closed at once. */
  private int streamStatus(String path) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve(path))
            .header("X-Qits-User", "agent-1")
            .header("X-Qits-Roles", "qits:agent")
            .header("Accept", "text/event-stream")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    HttpResponse<InputStream> response =
        client
            .sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            .get(30, TimeUnit.SECONDS);
    response.body().close();
    return response.statusCode();
  }

  @Test
  void workspaceControllerReadsAreOpenAndItsWritesAreNot() {
    asAgent().get("/workspaces/api/workspaces?repositoryId=no-such").then().statusCode(admitted());
    asAgent().get("/workspaces/api/workspaces/" + NO_ROW).then().statusCode(admitted());
    asAgent()
        .get("/workspaces/api/workspaces/" + NO_ROW + "/active-process")
        .then()
        .statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("repositoryId", "no-such", "workspaceId", "x"))
        .post("/workspaces/api/workspaces")
        .then()
        .statusCode(403);
  }

  @Test
  void workspaceHistoryReadsAreOpenAndItsEditIsNot() {
    asAgent().get("/workspaces/api/history?repositoryId=no-such").then().statusCode(admitted());
    asAgent().get("/workspaces/api/history/" + NO_ROW).then().statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("preamble", "x"))
        .patch("/workspaces/api/history/" + NO_ROW)
        .then()
        .statusCode(403);
  }

  @Test
  void thePromptDraftReadIsOpenAndItsWriteIsNot() {
    asAgent()
        .get("/workspaces/api/workspaces/" + NO_ROW + "/prompt-draft")
        .then()
        .statusCode(admitted());
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("content", "x"))
        .put("/workspaces/api/workspaces/" + NO_ROW + "/prompt-draft")
        .then()
        .statusCode(403);
  }

  @Test
  void thePromptAttachmentReadsAreOpenAndItsDeleteIsNot() {
    asAgent()
        .get("/workspaces/api/workspaces/" + NO_ROW + "/prompt-attachments")
        .then()
        .statusCode(admitted());
    asAgent()
        .get("/workspaces/api/workspaces/" + NO_ROW + "/prompt-attachments/some-id/content")
        .then()
        .statusCode(admitted());
    asAgent()
        .delete("/workspaces/api/workspaces/" + NO_ROW + "/prompt-attachments/some-id")
        .then()
        .statusCode(403);
  }

  @Test
  void theBootstrapRunsAreReadable() {
    asAgent()
        .get("/workspaces/api/workspaces/" + NO_ROW + "/bootstrap-runs")
        .then()
        .statusCode(admitted());
  }

  @Test
  void theServiceEventsAreReadable() {
    asAgent().get("/workspaces/api/service-events").then().statusCode(admitted());
  }

  @Test
  void thePinsAreReadable() {
    asAgent().get("/workspaces/api/pins").then().statusCode(admitted());
  }

  @Test
  void theDispatchReferencesAreReadableAndTheDispatchIsNot() {
    asAgent()
        .get("/workspaces/api/agent-dispatches/references?ticketId=t-1")
        .then()
        .statusCode(200);
    asAgent()
        .contentType(ContentType.JSON)
        .body(Map.of("repositoryId", "no-such", "branch", "ticket/x", "instruction", "go"))
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(403);
  }

  @Test
  void theGlobalEventStreamIsReadable() throws Exception {
    int status = streamStatus("workspaces/api/events");
    assertTrue(status != 401 && status != 403, "refused with " + status);
  }

  @Test
  void aWorkspaceEventStreamIsReadable() throws Exception {
    int status = streamStatus("workspaces/api/workspaces/" + NO_ROW + "/events");
    assertTrue(status != 401 && status != 403, "refused with " + status);
  }

  @Test
  void aTechnicalProcessEventStreamIsReadable() throws Exception {
    int status = streamStatus("workspaces/api/technical-processes/no-such-process/events");
    assertTrue(status != 401 && status != 403, "refused with " + status);
  }

  /** The control: the gate still judges roles, so a role that is not the agent's is refused. */
  @Test
  void aRoleThatIsNotTheAgentsIsStillRefused() {
    given()
        .header("X-Qits-User", "bob")
        .header("X-Qits-Roles", "qits:reader")
        .get("/workspaces/api/workspaces/" + NO_ROW)
        .then()
        .statusCode(403);
  }
}
