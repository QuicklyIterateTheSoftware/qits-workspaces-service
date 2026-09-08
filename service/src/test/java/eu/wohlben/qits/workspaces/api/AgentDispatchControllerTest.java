package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * {@code POST /workspaces/api/agent-dispatches} — the door qits-projects presses to put an agent on
 * a ticket, exercised end to end against a real loopback daemon.
 *
 * <p><b>The daemon is a stub server and not a mocked port, deliberately.</b> What is new about this
 * feature is that the host ORIGINATES a call to a container's API — nobody had ever done that — so
 * the thing worth pinning is the request that leaves this process: the path (the proxied one, which
 * is the daemon's own address), the bearer (qits', set rather than forwarded) and the body (chat
 * mode, the instruction as the seed turn, and {@code deliverTaskPrompt} false forever). A fake at
 * the port would assert the arguments and prove none of that. {@link ContainerProxyRouteTest} is the
 * sibling and the same kind of test, down to the latched port.
 *
 * <p>The launch happens after the response, on a thread of the service's own, so every launch
 * assertion here awaits the stub's recording rather than reading it straight after the call.
 */
@QuarkusTest
@TestProfile(AgentDispatchControllerTest.TestProfile.class)
public class AgentDispatchControllerTest {

  private static final String TOKEN = "test-dispatch-daemon-token";

  /** Its own key, so this class and {@link ContainerProxyRouteTest} never contend for one port. */
  private static final String PORT_PROPERTY = "qits.test.agent-dispatch.daemon-port";

  /** See {@link ContainerProxyRouteTest}'s twin for why the first caller has to win. */
  private static synchronized int latchedPort() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (Exception e) {
      throw new IllegalStateException("no free port for the fake daemon", e);
    }
  }

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-agent-dispatch-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            "qits.workspace.daemon-api-port", String.valueOf(latchedPort()),
            "qits.workspace.daemon-api-token", TOKEN,
            // The wait is the feature; the shipped two-second tick would make every assertion
            // here a sleep. The window stays short so a stub that is gone cannot leave a thread
            // polling for fifteen minutes behind the suite.
            "qits.workspace.agent-dispatch.poll-interval-ms", "50",
            "qits.workspace.agent-dispatch.launch-window-ms", "20000");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx daemonVertx;

  /** What the stub answers {@code GET /commands?status=RUNNING} with. Nothing running by default. */
  private final AtomicReference<String> runningCommands =
      new AtomicReference<>("{\"entries\":[]}");

  /**
   * Every launch the stub received, by the path it arrived on.
   *
   * <p>Keyed rather than counted because a wait thread outlives the test method that started it: a
   * launch that lands late belongs to whichever workspace it names, and no assertion here can be
   * confused by one it did not ask about.
   */
  private final Map<String, JsonObject> launches = new ConcurrentHashMap<>();

  private final Map<String, String> launchBearers = new ConcurrentHashMap<>();

  @BeforeEach
  void startFakeDaemon() throws Exception {
    launches.clear();
    launchBearers.clear();
    runningCommands.set("{\"entries\":[]}");
    daemonVertx = Vertx.vertx();
    daemonVertx
        .createHttpServer()
        .requestHandler(
            req -> {
              String path = req.path();
              if (path.endsWith("/agents")) {
                req.bodyHandler(
                    buffer -> {
                      launches.put(path, new JsonObject(buffer.toString()));
                      launchBearers.put(path, String.valueOf(req.getHeader("Authorization")));
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
        .listen(latchedPort(), "127.0.0.1")
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** Awaited, for {@link ContainerProxyRouteTest#stopFakeDaemon}'s reason: the port must be free. */
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

  /** The body carries nulls for the optional members, so a HashMap rather than {@code Map.of}. */
  private static Map<String, Object> body(
      String repositoryId, String branch, String preamble, String instruction) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("branch", branch);
    body.put("branchTree", Boolean.FALSE);
    body.put("preamble", preamble);
    body.put("instruction", instruction);
    return body;
  }

  private JsonPath dispatch(Map<String, Object> body, int expectedStatus) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/agent-dispatches")
        .then()
        .statusCode(expectedStatus)
        .extract()
        .jsonPath();
  }

  private JsonObject awaitLaunch(Long workspaceRowId) {
    String path = "/workspaces/container/" + workspaceRowId + "/agents";
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      JsonObject launch = launches.get(path);
      if (launch != null) {
        return launch;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError("no agent launch ever reached the daemon at " + path);
  }

  /**
   * The whole arc from one call: a branch that did not exist, a workspace on it carrying the goal,
   * a container start to watch, and — minutes later in production, a moment later here — an agent.
   */
  @Test
  public void aFreshDispatchCreatesTheBranchTheWorkspaceAndTheAgent() throws Exception {
    String repoId = seedRepository();

    JsonPath answer =
        dispatch(
            body(repoId, "ticket/fix-login", "# Fix the login\n\nIt 500s.", "start with the test"),
            200);

    assertThat(answer.getBoolean("fresh"), is(true));
    assertThat(answer.getString("agentLaunch"), is("SCHEDULED"));
    assertThat(answer.getString("workspace.branch"), is("ticket/fix-login"));
    assertThat(answer.getString("workspace.parent"), is("master"));
    assertThat(answer.getString("workspace.preamble"), is("# Fix the login\n\nIt 500s."));
    // The container start is asynchronous and the caller watches it; a dispatch that answered no
    // process id would leave it with nothing to poll but the workspace itself.
    assertThat(answer.getString("technicalProcessId"), is(notNullValue()));

    // The durable half: the ref really is on the git host, not just a row claiming it.
    assertTrue(
        workspaceService.branchExists(repoId, "ticket/fix-login"),
        "the dispatch created a row but never pushed the branch");

    Long rowId = workspaceIds.of(repoId, "ticket-fix-login");
    assertThat(awaitLaunch(rowId).getString("initialContext"), is("start with the test"));
  }

  /**
   * The door is pressed to reach a state, so pressing it twice reaches the same one. A 409 here
   * would make a caller's retry an error and its poll impossible.
   */
  @Test
  public void aSecondDispatchOnTheSameBranchAnswersTheSameWorkspace() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> request = body(repoId, "ticket/retry", "the goal", "do the thing");

    JsonPath first = dispatch(request, 200);
    JsonPath second = dispatch(request, 200);

    assertThat(first.getBoolean("fresh"), is(true));
    assertThat(second.getBoolean("fresh"), is(false));
    assertThat(second.getLong("workspace.id"), is(first.getLong("workspace.id")));

    // …and there is one workspace, not two. The preamble of the second call is discarded with it:
    // the goal belongs to the workspace that exists.
    JsonPath listing =
        given()
            .get("/workspaces/api/workspaces?repositoryId=" + repoId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals(
        1,
        listing.getList("entries.workspace.branch", String.class).stream()
            .filter("ticket/retry"::equals)
            .count());
  }

  /**
   * git's ref namespace is filesystem-like: a repository holding {@code refs/heads/ticket} can hold
   * no {@code refs/heads/ticket/*} at all. The dispatch takes the dash shape rather than failing on
   * a push it could not explain — and the workspace slug is the same either way, so nothing
   * downstream can tell which shape it got.
   */
  @Test
  public void aLiteralFirstSegmentBranchPushesTheDispatchToTheDashShape() throws Exception {
    String repoId = seedRepository();
    workspaceService.createWorkspace(repoId, "ticket", "master", "ticket");

    JsonPath answer = dispatch(body(repoId, "ticket/fix-login", "the goal", "go"), 200);

    assertThat(answer.getString("workspace.branch"), is("ticket-fix-login"));
    assertThat(answer.getString("workspace.workspaceId"), is("ticket-fix-login"));
    assertTrue(workspaceService.branchExists(repoId, "ticket-fix-login"));
    assertFalse(workspaceService.branchExists(repoId, "ticket/fix-login"));
  }

  /**
   * The request that leaves this process, in full.
   *
   * <p>{@code deliverTaskPrompt} is the one that is worth a test of its own: true seeds the session
   * with an instruction to fetch the real prompt through an MCP tool named {@code taskPrompt}, and
   * that tool is implemented nowhere on the platform. An agent launched that way sits there waiting
   * for a tool that will never exist, and nothing in this service would say so.
   */
  @Test
  public void theLaunchCarriesChatModeTheInstructionAndNeverTheTaskPrompt() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-wired", "ticket/wired");

    JsonPath answer =
        dispatch(body(repoId, "ticket/wired", "the goal", "read the failing test first"), 200);

    assertThat(answer.getBoolean("fresh"), is(false));
    assertThat(answer.getString("agentLaunch"), is("SCHEDULED"));
    // The daemon was already answering, so nothing had to be started for it.
    assertNull(answer.getString("technicalProcessId"));

    JsonObject launch = awaitLaunch(rowId);
    assertEquals("REPOSITORY", launch.getString("scope"), "ACTIONS is served by nothing today");
    assertEquals("CHAT", launch.getString("mode"), "a headless dispatch cannot drive a PTY");
    assertEquals("read the failing test first", launch.getString("initialContext"));
    assertEquals(
        Boolean.FALSE,
        launch.getBoolean("deliverTaskPrompt"),
        "the taskPrompt MCP tool is unimplemented platform-wide; the prompt rides initialContext");

    // Peer authentication between qits and the container, presented by this service itself — there
    // is no caller to forward one from on a scheduler thread.
    assertEquals(
        "Bearer " + TOKEN, launchBearers.get("/workspaces/container/" + rowId + "/agents"));
  }

  /**
   * A re-dispatch onto a workspace somebody is already working in starts nothing. Two agents on one
   * checkout is a merge conflict with itself.
   */
  @Test
  public void aRunningAgentIsNotJoinedBySecond() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-busy", "ticket/busy");
    runningCommands.set(
        "{\"entries\":[{\"command\":{\"id\":\"cmd-live\",\"status\":\"RUNNING\","
            + "\"kind\":\"CHAT\",\"agentSessions\":[{\"sessionId\":\"s-1\"}]}}]}");

    JsonPath answer = dispatch(body(repoId, "ticket/busy", "the goal", "go again"), 200);

    assertThat(answer.getBoolean("fresh"), is(false));
    assertThat(answer.getString("agentLaunch"), is("SKIPPED_RUNNING"));
    assertThat(answer.getLong("workspace.id"), is(rowId));

    // Nothing was launched, and nothing is on its way either — this answer is terminal, not a
    // "later".
    Thread.sleep(300);
    assertNull(launches.get("/workspaces/container/" + rowId + "/agents"));
  }
}
