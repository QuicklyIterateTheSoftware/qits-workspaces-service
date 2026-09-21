package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.GitRefs;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
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

  /**
   * The bearer this class's profile configures, and therefore the one every class sharing that
   * profile checks its fake daemon was called with. Package-private for {@link
   * ContainerProxyRouteTest}, which shares the profile rather than writing a second one.
   */
  static final String TOKEN = "test-dispatch-daemon-token";

  /** The key the latched port travels on. See {@link #latchedPort()}. */
  private static final String PORT_PROPERTY = "qits.test.agent-dispatch.daemon-port";

  /**
   * The port the fake daemon binds, chosen by the profile and handed to the test through a system
   * property.
   *
   * <p>It has to travel that way, and the <em>first caller wins</em>. Unlike a service's web-view
   * port, the daemon's comes from configuration, so it must be fixed before the application boots —
   * and {@link QuarkusTestProfile} is instantiated in more than one classloader, so {@code
   * getConfigOverrides()} runs more than once. A plain static initializer picks a different port each
   * time; an unconditional {@code setProperty} lets the later call overwrite the value the
   * application was actually configured with. Either way the proxy targets a port nothing is
   * listening on, and the symptom is every proxying assertion failing with a bare, bodyless 502 that
   * says nothing about why. Latching the first value is what makes both halves agree.
   *
   * <p>Package-private, and the module's only such latch: {@link AgentTurnDeliveryTest}, {@link
   * ContainerProxyRouteTest} and {@link AgentTurnCompactionAndWindowTest} all stub a daemon on it.
   * Copying the method would be a second latch and therefore a second port. Each of those classes
   * gets a JVM of its own ({@code <reuseForks>false</reuseForks>}), so the latch is per-class in
   * practice and no two fakes ever race for one bind.
   */
  static synchronized int latchedPort() {
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

  /**
   * <b>A profile of its own, and it has to be one.</b> A fake daemon binds a port that the
   * application must already be configured with, and configuration is fixed before boot — so the
   * port, and the token that goes with it, can live nowhere but a profile. That is a different kind
   * of claim from the dials in {@code SharedTuningProfile}, where the value only makes an assertion
   * cheap.
   *
   * <p>What it cannot share is the class it is closest to. {@link
   * AgentTurnCompactionAndWindowTest}'s subject is the launch window EXPIRING, so its window is
   * 1500 ms; the classes here need a window long enough that a healthy stub always answers inside
   * it, which is this 20 s. One profile would have to pick one number, and each class's assertions
   * are exactly the other's failure. {@link ContainerProxyRouteTest} needs a strict subset of this
   * map and therefore shares it.
   */
  public static class TestProfile
      implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.workspace.daemon-api-port", String.valueOf(latchedPort()),
          "qits.workspace.daemon-api-token", TOKEN,
          // The wait is the feature; the shipped two-second tick would make every assertion here a
          // sleep. The window stays short so a stub that is gone cannot leave a thread polling for
          // fifteen minutes behind the suite.
          "qits.workspace.agent-dispatch.poll-interval-ms", "50",
          "qits.workspace.agent-dispatch.launch-window-ms", "20000");
    }
  }

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;

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

  /**
   * The shape qits-projects actually sends since the preamble left it: no goal at all, and a
   * reference naming what the workspace is for.
   */
  private static Map<String, Object> bodyForTicket(
      String repositoryId, String branch, String ticketId, String instruction) {
    Map<String, Object> body = body(repositoryId, branch, null, instruction);
    body.put("ticketId", ticketId);
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

  /** The Git ref list stored on a workspace row, read in a transaction of its own. */
  private List<String> storedGitRefs(Long rowId) {
    return GitRefs.read(
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().gitRefs));
  }

  /** Contract C4: the list qits-projects computed at dispatch is the list the workspace keeps. */
  @Test
  public void aDispatchStoresTheGitRefsItWasGiven() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> body = bodyForTicket(repoId, "ticket/scoped", "t-1", "go");
    body.put("gitRefs", List.of("refs/heads/ticket/scoped", "refs/heads/ticket/scoped-docs"));

    dispatch(body, 200);

    assertEquals(
        List.of("refs/heads/ticket/scoped", "refs/heads/ticket/scoped-docs"),
        storedGitRefs(workspaceIds.of(repoId, "ticket-scoped")));
  }

  @Test
  public void aDispatchWithoutGitRefsMayPushItsOwnBranch() throws Exception {
    String repoId = seedRepository();

    dispatch(bodyForTicket(repoId, "ticket/unscoped", "t-2", "go"), 200);

    assertEquals(
        List.of("refs/heads/ticket/unscoped"),
        storedGitRefs(workspaceIds.of(repoId, "ticket-unscoped")));
  }

  /** An agent never pushes the default branch: a stated entry for it is dropped, the rest kept. */
  @Test
  public void aDispatchListNamingTheDefaultBranchLosesThatEntry() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> body = bodyForTicket(repoId, "ticket/with-main", "t-main", "go");
    body.put("gitRefs", List.of("refs/heads/ticket/with-main", "refs/heads/master"));

    dispatch(body, 200);

    assertEquals(
        List.of("refs/heads/ticket/with-main"),
        storedGitRefs(workspaceIds.of(repoId, "ticket-with-main")));
  }

  @Test
  public void aBadGitRefsListIsRefusedBeforeAnythingIsCreated() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> body = bodyForTicket(repoId, "ticket/bad-refs", "t-3", "go");
    body.put("gitRefs", List.of("refs/tags/v1"));

    dispatch(body, 400);

    assertFalse(
        workspaceService.branchExists(repoId, "ticket/bad-refs"),
        "a refused list must cost nothing: no branch was pushed");
  }

  @Test
  public void aRePressKeepsTheListTheWorkspaceHas() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> first = bodyForTicket(repoId, "ticket/repress", "t-4", "go");
    first.put("gitRefs", List.of("refs/heads/ticket/repress"));
    dispatch(first, 200);

    // A second press with a wider list must not widen a list that may have been narrowed since.
    Map<String, Object> second = bodyForTicket(repoId, "ticket/repress", "t-4", "go");
    second.put("gitRefs", List.of("refs/heads/ticket/repress", "refs/heads/epic/*"));
    JsonPath answer = dispatch(second, 200);

    assertThat(answer.getBoolean("fresh"), is(false));
    assertEquals(
        List.of("refs/heads/ticket/repress"),
        storedGitRefs(workspaceIds.of(repoId, "ticket-repress")));
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
   * The dispatch qits-projects makes today: a reference and no prose. The id is carried onto the
   * row and back out of the listing — this service resolves nothing with it, so being able to read
   * it back is the whole of the contract.
   */
  @Test
  public void aDispatchNamesItsTicketInAFieldAndLeavesTheGoalEmpty() throws Exception {
    String repoId = seedRepository();

    JsonPath answer =
        dispatch(
            bodyForTicket(repoId, "ticket/name-the-subject", "  t-42  ", "read it first"), 200);

    assertNull(answer.getString("workspace.preamble"), "a dispatch wrote a goal nobody authored");
    // Trimmed on the way in: a padded id would compose a link to nothing.
    assertThat(answer.getString("workspace.ticketId"), is("t-42"));
    assertNull(answer.getString("workspace.epicId"));

    JsonPath listing =
        given()
            .get("/workspaces/api/workspaces?repositoryId=" + repoId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    // By branch, not by position: the fixture's own main workspace is in this listing too.
    assertThat(
        listing.getList("entries.workspace.ticketId", String.class), hasItem("t-42"));
  }

  /**
   * The read back: qits-projects asks which workspaces name its rows, for a screenful of rows at
   * once. Both parameters repeat and both are optional, and an id nothing was dispatched onto
   * answers nothing rather than everything. Each row states its own status, so a caller reading one
   * never has to infer from the row's presence what the row itself can say.
   */
  @Test
  public void theReferencesDoorAnswersTheWorkspacesForTheRowsAskedAbout() throws Exception {
    String repoId = seedRepository();
    dispatch(bodyForTicket(repoId, "ticket/referenced", "t-99", "go"), 200);
    Map<String, Object> epicDispatch = body(repoId, "epic/referenced", null, "go");
    epicDispatch.put("epicId", "e-7");
    dispatch(epicDispatch, 200);

    JsonPath one = references("?ticketId=t-99");
    assertThat(one.getList("entries").size(), is(1));
    assertThat(one.getString("entries[0].workspace.ticketId"), is("t-99"));
    assertThat(one.getString("entries[0].workspace.branch"), is("ticket/referenced"));
    assertThat(one.getString("entries[0].workspace.repositoryId"), is(repoId));
    assertThat(one.getString("entries[0].workspace.workspaceId"), is("ticket-referenced"));
    assertThat(one.getLong("entries[0].workspace.workspaceRowId"), is(notNullValue()));
    assertNull(one.getString("entries[0].workspace.epicId"));
    assertThat(one.getString("entries[0].workspace.status"), is("ACTIVE"));
    assertNull(one.getString("entries[0].workspace.resolvedAt"));

    // Batched, and across both kinds in one call — the whole reason the parameters repeat.
    JsonPath both = references("?ticketId=t-99&ticketId=t-nothing&epicId=e-7");
    assertThat(both.getList("entries").size(), is(2));
    assertThat(both.getList("entries.workspace.branch", String.class), hasItem("epic/referenced"));

    assertTrue(references("?ticketId=t-nothing").getList("entries").isEmpty());
    // Asked about no rows: an empty answer, never the whole table.
    assertTrue(references("").getList("entries").isEmpty());
  }

  /**
   * Resolving a workspace does not end the reference, it changes what the reference says. The row
   * stays in the answer and reports the status it resolved to, because the side that owns the ticket
   * wants to show that the work was done and where — "this ticket's workspace was abandoned" is a
   * sentence somebody reads, and a row that simply vanished would leave the panel unable to tell it
   * apart from a ticket nobody ever dispatched. The status is what carries the distinction, so the
   * reader decides; this door decides nothing.
   */
  @Test
  public void aResolvedWorkspaceIsStillReportedAndCarriesItsStatus() throws Exception {
    String repoId = seedRepository();
    dispatch(bodyForTicket(repoId, "ticket/short-lived", "t-77", "go"), 200);
    assertThat(references("?ticketId=t-77").getList("entries").size(), is(1));

    // Forced, because the fixture's fake container reports a dirty tree; what is under test is the
    // resolution, not the guard in front of it.
    workspaceService.discardWorkspace(workspaceIds.of(repoId, "ticket-short-lived"), null, true);

    JsonPath after = references("?ticketId=t-77");
    assertThat(
        "a resolved workspace stopped being reported for the ticket it was dispatched onto",
        after.getList("entries").size(),
        is(1));
    // ABANDONED and not INTEGRATED: discard is the abandon verb, and asserting the status the code
    // writes is the whole point of carrying one.
    assertThat(after.getString("entries[0].workspace.status"), is("ABANDONED"));
    assertThat(after.getString("entries[0].workspace.resolvedAt"), is(notNullValue()));
    assertThat(after.getString("entries[0].workspace.branch"), is("ticket/short-lived"));
  }

  private JsonPath references(String query) {
    return given()
        .get("/workspaces/api/agent-dispatches/references" + query)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath();
  }

  /**
   * The ad-hoc half of the same rule: a caller that names no subject gets a row with neither field
   * set, and a blank one is not a subject either.
   */
  @Test
  public void aDispatchWithNoReferenceLeavesBothFieldsEmpty() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> request = body(repoId, "ticket/no-subject", "hand-written goal", "go");
    request.put("ticketId", "   ");

    JsonPath answer = dispatch(request, 200);

    assertNull(answer.getString("workspace.ticketId"), "a blank id was stored as a subject");
    assertNull(answer.getString("workspace.epicId"));
    assertThat(answer.getString("workspace.preamble"), is("hand-written goal"));
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
   * <p>{@code surface} is the one that is worth a test of its own for a reason the other fields are
   * not: the daemon <b>requires</b> it, so an omission is not a shape that degrades — it is a 400
   * and a dispatch that cut a workspace, started a container and launched nothing. That is what
   * shipped between qits-workspace-daemon 2026.909.125238 (which removed the shape-based guess) and
   * this assertion. The value has to be {@code ticket.dispatch} rather than the SPA's
   * {@code workspace.chat}: the surface is what a session is *for*, and per-surface agent
   * configuration reads it.
   *
   * <p>{@code deliverTaskPrompt} is worth one too: true seeds the session
   * with an instruction to fetch the real prompt through an MCP tool named {@code taskPrompt}, and
   * that tool is implemented nowhere on the platform. An agent launched that way sits there waiting
   * for a tool that will never exist, and nothing in this service would say so.
   */
  @Test
  public void theLaunchCarriesItsSurfaceChatModeTheInstructionAndNeverTheTaskPrompt()
      throws Exception {
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
    assertEquals(
        "ticket.dispatch",
        launch.getString("surface"),
        "the daemon 400s a launch with no surface, and this caller is a ticket dispatch");
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
