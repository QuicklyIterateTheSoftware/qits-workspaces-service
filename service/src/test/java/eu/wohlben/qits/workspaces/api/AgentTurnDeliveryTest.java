package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /workspaces/api/agent-dispatches/delivery} — <b>say this to the workspace's agent</b>,
 * the verb that did not exist while a user turn's only entrance was a browser on the daemon's
 * command websocket.
 *
 * <p>Against the same loopback stub daemon {@link AgentDispatchControllerTest} uses, and it reuses
 * that class's {@code TestProfile} <b>by class</b> rather than declaring an identical one: a
 * {@code @TestProfile} is compared by identity, so a second copy would restart Quarkus for no
 * behaviour difference and give this class a second temp origins directory.
 *
 * <p>What is worth proving here is the arm being chosen from what is <em>there</em> — the caller
 * names none of them — and the one arm that does nothing at all: a branch with no workspace. That
 * one is a 200 with a null id, so the assertions are as much about what was NOT created as about
 * what was answered.
 *
 * <p>Every delivery lands after the response, on a thread of the service's own, so each assertion
 * about the daemon awaits the stub's recording rather than reading it straight after the call.
 */
@QuarkusTest
@TestProfile(AgentDispatchControllerTest.TestProfile.class)
public class AgentTurnDeliveryTest {

  /** One RUNNING chat command: what "an agent is working here" looks like on the wire. */
  private static final String AN_AGENT_IS_RUNNING =
      "{\"entries\":[{\"command\":{\"id\":\"cmd-live\",\"status\":\"RUNNING\",\"kind\":\"CHAT\","
          + "\"agentSessions\":[{\"sessionId\":\"s-1\"}]}}]}";

  private static final String NOTHING_IS_RUNNING = "{\"entries\":[]}";

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx daemonVertx;

  private final AtomicReference<String> runningCommands = new AtomicReference<>(NOTHING_IS_RUNNING);

  /** Every launch and every delivered turn the stub received, by the path it arrived on. */
  private final Map<String, JsonObject> launches = new ConcurrentHashMap<>();

  private final Map<String, JsonObject> turns = new ConcurrentHashMap<>();

  private final Map<String, String> turnBearers = new ConcurrentHashMap<>();

  @BeforeEach
  void startFakeDaemon() throws Exception {
    launches.clear();
    turns.clear();
    turnBearers.clear();
    runningCommands.set(NOTHING_IS_RUNNING);
    daemonVertx = Vertx.vertx();
    daemonVertx
        .createHttpServer()
        .requestHandler(
            req -> {
              String path = req.path();
              // Before the /agents arm: a turn's path ends in /agents/turn and must never be read
              // as a launch.
              if (path.endsWith("/agents/turn")) {
                req.bodyHandler(
                    buffer -> {
                      JsonObject body = new JsonObject(buffer.toString());
                      turns.put(path, body);
                      turnBearers.put(
                          path, String.valueOf(req.getHeader("Authorization")));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end(
                              "{\"delivered\":true,\"commandId\":\"cmd-live\",\"kind\":\"CHAT\"}");
                    });
                return;
              }
              if (path.endsWith("/agents")) {
                req.bodyHandler(
                    buffer -> {
                      launches.put(path, new JsonObject(buffer.toString()));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{\"command\":{\"id\":\"cmd-1\",\"status\":\"RUNNING\"}}");
                    });
                return;
              }
              if (path.endsWith("/commands")) {
                req.response()
                    .putHeader("Content-Type", "application/json")
                    .end(runningCommands.get());
                return;
              }
              req.response().setStatusCode(404).end("{\"message\":\"No such endpoint\"}");
            })
        .listen(AgentDispatchControllerTest.latchedPort(), "127.0.0.1")
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  @AfterEach
  void stopFakeDaemon() throws Exception {
    if (daemonVertx != null) {
      daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      daemonVertx = null;
    }
  }

  private String seedRepository() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    return repoId;
  }

  /** A workspace whose (fake) container is already provisioned, so its daemon answers at once. */
  private Long workspaceWithContainer(String repoId, String label, String branch) {
    workspaceService.createWorkspace(repoId, label, "master", branch);
    Long rowId = workspaceIds.of(repoId, label);
    workspaceService.ensureContainer(rowId);
    return rowId;
  }

  /** A workspace with no container at all — an idle-stopped one, from this side. */
  private Long workspaceWithoutContainer(String repoId, String label, String branch) {
    workspaceService.createWorkspace(repoId, label, "master", branch);
    return workspaceIds.of(repoId, label);
  }

  private static Map<String, Object> turn(String repositoryId, String branch, String text) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("branch", branch);
    body.put("text", text);
    body.put("compactFirst", Boolean.FALSE);
    return body;
  }

  private JsonPath deliver(Map<String, Object> body, int expectedStatus) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/agent-dispatches/delivery")
        .then()
        .statusCode(expectedStatus)
        .extract()
        .jsonPath();
  }

  private String turnPath(Long rowId) {
    return "/workspaces/container/" + rowId + "/agents/turn";
  }

  private String launchPath(Long rowId) {
    return "/workspaces/container/" + rowId + "/agents";
  }

  private JsonObject awaitTurn(Long rowId) {
    return await(turns, turnPath(rowId), "no turn ever reached the daemon at ");
  }

  private JsonObject awaitLaunch(Long rowId) {
    return await(launches, launchPath(rowId), "no agent launch ever reached the daemon at ");
  }

  private static JsonObject await(Map<String, JsonObject> recorded, String path, String complaint) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      JsonObject body = recorded.get(path);
      if (body != null) {
        return body;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError(complaint + path);
  }

  /**
   * The arm the whole feature exists for: somebody is working here, so they are told — and no second
   * agent is started beside them, which is what the dispatch door had to do instead.
   */
  @Test
  public void aRunningAgentIsSpokenToAndNotRelaunched() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-talking", "ticket/talking");
    runningCommands.set(AN_AGENT_IS_RUNNING);

    JsonPath answer = deliver(turn(repoId, "ticket/talking", "the ticket moved to VERIFY"), 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("delivered"), is(true));
    assertThat(answer.getBoolean("launched"), is(false));
    assertThat(answer.getString("detail"), containsString("agent is running"));

    assertEquals("the ticket moved to VERIFY", awaitTurn(rowId).getString("text"));
    // Peer authentication between qits and the container, presented by this service itself.
    assertThat(turnBearers.get(turnPath(rowId)), containsString("Bearer "));
    // Nothing was launched, and nothing is on its way either.
    Thread.sleep(300);
    assertNull(launches.get(launchPath(rowId)), "a turn was delivered AND a second agent started");
  }

  /**
   * The fallback arm, and it is the path that already existed: a workspace whose session has ended
   * has nobody to tell, so what was to be said becomes the first turn of a new one.
   */
  @Test
  public void anIdleWorkspaceIsLaunchedWithTheText() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-quiet", "ticket/quiet");

    JsonPath answer = deliver(turn(repoId, "ticket/quiet", "pick the ticket back up"), 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("delivered"), is(false));
    assertThat(answer.getBoolean("launched"), is(true));

    assertEquals("pick the ticket back up", awaitLaunch(rowId).getString("initialContext"));
    assertNull(turns.get(turnPath(rowId)), "there was nobody to deliver a turn to");
  }

  /**
   * An idle-stopped container is the ordinary between-phase state, not an error: the delivery starts
   * it, waits, and then says what it came to say. The answer cannot claim either arm yet — the
   * daemon has not been asked — which is what both booleans being false beside a real workspace id
   * means.
   */
  @Test
  public void aStoppedContainerIsStartedWaitedForThenSpokenTo() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithoutContainer(repoId, "ticket-asleep", "ticket/asleep");
    runningCommands.set(AN_AGENT_IS_RUNNING);

    JsonPath answer = deliver(turn(repoId, "ticket/asleep", "carry on where you left off"), 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("delivered"), is(false));
    assertThat(answer.getBoolean("launched"), is(false));
    assertThat(answer.getString("detail"), containsString("not answering yet"));

    assertEquals("carry on where you left off", awaitTurn(rowId).getString("text"));
  }

  /**
   * The one way this differs from a dispatch, and the reason it is a 200: the caller is a machine
   * reacting to a status change, and a ticket nobody put an agent on has no workspace. Nothing is
   * created — not a row, not a branch, not a container.
   */
  @Test
  public void anUnknownBranchAnswersTheEmptySentenceAndStartsNothing() throws Exception {
    String repoId = seedRepository();

    JsonPath answer = deliver(turn(repoId, "ticket/nobody-is-here", "anyone?"), 200);

    assertNull(answer.get("workspaceId"), "a delivery conjured a workspace");
    assertThat(answer.getBoolean("delivered"), is(false));
    assertThat(answer.getBoolean("launched"), is(false));
    assertThat(answer.getString("detail"), containsString("no workspace stands on"));

    Thread.sleep(300);
    assertTrue(launches.isEmpty(), "nothing may be launched for a branch with no workspace");
    assertTrue(turns.isEmpty(), "nothing may be said to a branch with no workspace");
    assertTrue(
        !workspaceService.branchExists(repoId, "ticket/nobody-is-here"),
        "a delivery pushed a branch; only a dispatch may do that");
    JsonPath listing =
        given()
            .get("/workspaces/api/workspaces?repositoryId=" + repoId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertTrue(
        listing.getList("entries.workspace.branch", String.class).stream()
            .noneMatch("ticket/nobody-is-here"::equals),
        "a delivery created a workspace row");
  }

  /** A repository that does not exist is the same answer: nobody to tell, and nothing refused. */
  @Test
  public void anUnknownRepositoryIsTheSameEmptySentence() {
    JsonPath answer = deliver(turn("no-such-repository", "ticket/whatever", "hello"), 200);

    assertNull(answer.get("workspaceId"));
    assertThat(answer.getString("detail"), containsString("no workspace stands on"));
  }

  /** An empty turn is a request that cannot have been meant, and the daemon 400s it anyway. */
  @Test
  public void aBlankTextIsRefused() throws Exception {
    String repoId = seedRepository();
    workspaceWithContainer(repoId, "ticket-blank", "ticket/blank");

    deliver(turn(repoId, "ticket/blank", "   "), 400);

    Thread.sleep(200);
    assertTrue(turns.isEmpty(), "a blank turn reached a daemon");
  }
}
