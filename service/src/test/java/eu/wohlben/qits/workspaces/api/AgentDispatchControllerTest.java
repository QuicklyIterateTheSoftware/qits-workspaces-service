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

import eu.wohlben.qits.workspaces.control.FakeContainerRuntime;
import eu.wohlben.qits.workspaces.control.FakeCredentialCommissioner;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.GitRefs;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceContainer;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.Workspace;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
  @Inject FakeContainerRuntime containerRuntime;
  @Inject WorkspaceContainerFactory containerFactory;
  @Inject FakeCredentialCommissioner credentials;

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

  /** Every blocked-marker call the stub received, by the path it arrived on. */
  private final Map<String, JsonObject> blockedCalls = new ConcurrentHashMap<>();

  /** Every subject-facts call the stub received, by the path it arrived on. */
  private final Map<String, JsonObject> entityCalls = new ConcurrentHashMap<>();

  /**
   * Whether the stub carries {@code /agents/entity}. False plays a daemon image older than the
   * route, which answers it the stub's ordinary 404 — or, when {@link #daemonRejectsEntityMethod}
   * is also set, the 405 a daemon whose {@code /agents/*} router rejects the method before it
   * resolves the path answers instead (qits-617; measured live against 2026.1001.72420).
   */
  private final AtomicBoolean daemonKnowsEntity = new AtomicBoolean(true);

  /**
   * When {@link #daemonKnowsEntity} is false, whether the stub answers {@code /agents/entity} with
   * 405 rather than falling through to the ordinary 404. Plays the older daemon's router rejecting
   * the method on a sub-path it does not know, before it ever resolves the path.
   */
  private final AtomicBoolean daemonRejectsEntityMethod = new AtomicBoolean(false);

  @BeforeEach
  void startFakeDaemon() throws Exception {
    launches.clear();
    launchBearers.clear();
    blockedCalls.clear();
    entityCalls.clear();
    daemonKnowsEntity.set(true);
    daemonRejectsEntityMethod.set(false);
    runningCommands.set("{\"entries\":[]}");
    daemonVertx = Vertx.vertx();
    daemonVertx
        .createHttpServer()
        .requestHandler(
            req -> {
              String path = req.path();
              if (path.endsWith("/agents/entity") && daemonKnowsEntity.get()) {
                req.bodyHandler(
                    buffer -> {
                      entityCalls.put(path, new JsonObject(buffer.toString()));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{\"renamed\":1}");
                    });
                return;
              }
              if (path.endsWith("/agents/entity")
                  && !daemonKnowsEntity.get()
                  && daemonRejectsEntityMethod.get()) {
                req.response()
                    .setStatusCode(405)
                    .end("{\"message\":\"Method not allowed\"}");
                return;
              }
              if (path.endsWith("/agents/blocked")) {
                req.bodyHandler(
                    buffer -> {
                      blockedCalls.put(path, new JsonObject(buffer.toString()));
                      req.response()
                          .putHeader("Content-Type", "application/json")
                          .end("{}");
                    });
                return;
              }
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

  /**
   * {@link FakeCredentialCommissioner} is a bean for every {@code @QuarkusTest} in this module, so a
   * class that wires it resets it — the fake's own javadoc rule — or it leaks a minted client id
   * into whatever runs next.
   */
  @AfterEach
  void resetCredentials() {
    credentials.reset();
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
   * {@code entityId} is qits-projects' qualified id for the same subject {@code ticketId} names —
   * {@code <project-slug>-<number>}, e.g. {@code qits-614} — carried onto the row beside it so the
   * in-container daemon can name its agent sessions {@code [❗]<status square> <entityId> <title>}.
   * Not on {@link eu.wohlben.qits.workspaces.dto.WorkspaceDto}, so the row is read back directly
   * rather than through the dispatch response.
   */
  @Test
  public void aDispatchStoresTheEntityIdBesideTheTicket() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> request = bodyForTicket(repoId, "ticket/name-the-entity", "t-55", "go");
    request.put("entityId", "qits-614");

    dispatch(request, 200);

    Long rowId = workspaceIds.of(repoId, "ticket-name-the-entity");
    String storedEntityId =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().entityId);
    assertThat(storedEntityId, is("qits-614"));
  }

  /** The row read back in a transaction of its own, for the V9 columns no DTO carries. */
  private Workspace storedRow(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow());
  }

  /**
   * qits-617: the subject's title, status word and blocked flag ride the dispatch onto the row, which
   * is where the container spec reads them from — so the spec the container first comes up on
   * already names the session.
   */
  @Test
  public void aDispatchStoresTheSubjectsTitleStatusAndBlockedFlag() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> request = bodyForTicket(repoId, "ticket/facts", "t-57", "go");
    request.put("entityId", "qits-555");
    request.put("entityTitle", "Comments on every work entity");
    request.put("entityStatus", "REFINED");
    request.put("entityBlocked", Boolean.TRUE);

    dispatch(request, 200);

    Workspace row = storedRow(workspaceIds.of(repoId, "ticket-facts"));
    assertThat(row.entityTitle, is("Comments on every work entity"));
    assertThat(row.entityStatus, is("REFINED"));
    assertThat(row.entityBlocked, is(true));
  }

  /**
   * An older qits-projects sends none of the three. Its dispatch still works — and its re-press must
   * not wipe what a newer relay already stored, so absent means "leave the row alone", not "null".
   */
  @Test
  public void aDispatchWithoutTheFactsKeepsWhatTheRowAlreadyHas() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> first = bodyForTicket(repoId, "ticket/old-caller", "t-58", "go");
    first.put("entityTitle", "Stored once");
    first.put("entityStatus", "IMPLEMENTED");
    dispatch(first, 200);
    Long rowId = workspaceIds.of(repoId, "ticket-old-caller");
    assertThat(storedRow(rowId).entityBlocked, is(false));

    dispatch(bodyForTicket(repoId, "ticket/old-caller", "t-58", "again"), 200);

    Workspace row = storedRow(rowId);
    assertThat(row.entityTitle, is("Stored once"));
    assertThat(row.entityStatus, is("IMPLEMENTED"));
  }

  /** Absent is the ordinary case — qits-projects' dispatch doors send the three fields together. */
  @Test
  public void aDispatchWithNoEntityIdStoresNull() throws Exception {
    String repoId = seedRepository();

    dispatch(bodyForTicket(repoId, "ticket/no-entity", "t-56", "go"), 200);

    Long rowId = workspaceIds.of(repoId, "ticket-no-entity");
    String storedEntityId =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).orElseThrow().entityId);
    assertNull(storedEntityId);
    Workspace row = storedRow(rowId);
    assertNull(row.entityTitle);
    assertNull(row.entityStatus);
    assertNull(row.entityBlocked);
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

  /**
   * qits-938: {@code agentIdentity} is the principal the dispatched agent's own calls are stamped
   * with — a DIRECT row's commissioned client id. {@link #workspaceWithContainer} ensures the
   * container synchronously, so with the issuer wired the commission has already landed on the row
   * by the time this re-dispatch reads it back.
   */
  @Test
  public void aDispatchAnswersTheCommissionedClientIdAsAgentIdentity() throws Exception {
    credentials.wire();
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-identity", "ticket/identity");
    String commissionedClientId = storedRow(rowId).commissionedClientId;
    assertThat(
        "the fixture must actually have commissioned a client",
        commissionedClientId,
        is(notNullValue()));

    JsonPath answer = dispatch(body(repoId, "ticket/identity", "the goal", "go"), 200);

    assertThat(answer.getString("agentIdentity"), is(commissionedClientId));
  }

  /**
   * qits-938: before anything is commissioned — the issuer unwired, the shipped posture — the field
   * is null rather than some other sentinel. Never a secret either way: this is a name, never
   * {@code commissionedClientSecret}.
   */
  @Test
  public void aDispatchWithNoCommissionAnswersANullAgentIdentity() throws Exception {
    String repoId = seedRepository();

    JsonPath answer = dispatch(body(repoId, "ticket/no-commission", "the goal", "go"), 200);

    assertNull(answer.getString("agentIdentity"));
  }

  private JsonPath blocked(String repositoryId, String branch, boolean isBlocked, int status) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("branch", branch);
    body.put("blocked", Boolean.valueOf(isBlocked));
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/agent-dispatches/blocked")
        .then()
        .statusCode(status)
        .extract()
        .jsonPath();
  }

  /**
   * A ticket's blocked state changing when nobody ever dispatched a workspace onto it is the
   * ordinary case — the caller is qits-projects reacting to every ticket's transitions, and most
   * tickets carry no workspace at all. {@code workspaceId: null} says so without a 404.
   */
  @Test
  public void markingBlockedWithNoWorkspaceAnswersNullAndNotApplied() throws Exception {
    String repoId = seedRepository();

    JsonPath answer = blocked(repoId, "ticket/never-dispatched", true, 200);

    assertNull(answer.getObject("workspaceId", Long.class));
    assertThat(answer.getBoolean("applied"), is(false));
  }

  /**
   * The daemon is already answering (the stub's {@code /commands} route), so the marker reaches
   * it, with the flag this call named — and the {@code blocked:false} clear reaches it the same
   * way, which is the other half of the contract: a daemon that can be told "blocked" can be told
   * to stop saying so.
   */
  @Test
  public void markingBlockedWithAReachableDaemonCallsItWithTheFlag() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-blocked", "ticket/blocked");

    JsonPath answer = blocked(repoId, "ticket/blocked", true, 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("applied"), is(true));
    String path = "/workspaces/container/" + rowId + "/agents/blocked";
    assertThat(blockedCalls.get(path).getBoolean("blocked"), is(true));

    assertThat(storedRow(rowId).entityBlocked, is(true));

    JsonPath cleared = blocked(repoId, "ticket/blocked", false, 200);
    assertThat(cleared.getBoolean("applied"), is(true));
    assertThat(blockedCalls.get(path).getBoolean("blocked"), is(false));
    assertThat(storedRow(rowId).entityBlocked, is(false));
  }

  /**
   * A dash-shape fallback branch finds the same workspace a dispatch would have fallen back to —
   * {@link DispatchService#deliver}'s string-only lookup, reused rather than copied: the requested
   * branch is {@code ticket/dash}, the workspace actually stands on the literal {@code
   * ticket-dash}, exactly as it would if a repository holding a literal {@code refs/heads/ticket}
   * had pushed a dispatch there first.
   */
  @Test
  public void markingBlockedFindsTheDashShapeWorkspace() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-dash", "ticket-dash");

    JsonPath answer = blocked(repoId, "ticket/dash", true, 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("applied"), is(true));
  }

  /**
   * <b>The verb this door must never perform.</b> A workspace whose container was never ensured
   * has nothing to rename, and marking it blocked must not be the thing that ensures one, starts a
   * container or launches an agent — a {@code ❗ } nobody can see yet is not worth a cold image
   * pull. The fake daemon is not even listening for this workspace (no container exists for it at
   * all), so a reaching call would fail anyway; what this proves is that nothing in this service
   * ever tries.
   */
  @Test
  public void markingBlockedNeverEnsuresAContainerOrLaunchesAnAgent() throws Exception {
    String repoId = seedRepository();
    String label = "ticket-no-container";
    String branch = "ticket/no-container";
    workspaceService.createWorkspace(repoId, label, "master", branch);
    Long rowId = workspaceIds.of(repoId, label);
    String containerName = containerRuntime.containerName(label, repoId);
    assertFalse(
        containerRuntime.exists(containerName), "the fixture must start with no container");

    JsonPath answer = blocked(repoId, branch, true, 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("applied"), is(false));
    assertFalse(
        containerRuntime.exists(containerName),
        "marking blocked must never ensure a container that was never there");

    // Nothing is on its way, either — not a "later", exactly the running-agent dispatch case above.
    Thread.sleep(300);
    assertNull(launches.get("/workspaces/container/" + rowId + "/agents"));
    assertTrue(blockedCalls.isEmpty(), "an unreachable daemon was never actually called");
  }

  private JsonPath entity(Map<String, Object> body, int status) {
    return given()
        .contentType(ContentType.JSON)
        .body(body)
        .when()
        .post("/workspaces/api/agent-dispatches/entity")
        .then()
        .statusCode(status)
        .extract()
        .jsonPath();
  }

  /** Nulls are members of the contract, so a HashMap rather than {@code Map.of}. */
  private static Map<String, Object> entityBody(
      String repositoryId, String branch, String title, String status, Object blocked) {
    Map<String, Object> body = new HashMap<>();
    body.put("repositoryId", repositoryId);
    body.put("branch", branch);
    body.put("title", title);
    body.put("status", status);
    body.put("blocked", blocked);
    return body;
  }

  /** {@link #markingBlockedWithNoWorkspaceAnswersNullAndNotApplied}'s case, on the successor door. */
  @Test
  public void markingTheEntityWithNoWorkspaceAnswersNullAndNotApplied() throws Exception {
    String repoId = seedRepository();

    JsonPath answer =
        entity(entityBody(repoId, "ticket/never-dispatched", "T", "REFINED", false), 200);

    assertNull(answer.getObject("workspaceId", Long.class));
    assertThat(answer.getBoolean("applied"), is(false));
  }

  /**
   * Both halves with a reachable daemon: the row holds the facts, and the daemon was told all three
   * on {@code /agents/entity} — and not on the old route, which is only the fallback.
   */
  @Test
  public void markingTheEntityStoresTheFactsAndTellsAReachableDaemon() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-entity", "ticket/entity");

    JsonPath answer =
        entity(
            entityBody(
                repoId, "ticket/entity", "Comments on every work entity", "IMPLEMENTED", true),
            200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("applied"), is(true));
    JsonObject told = entityCalls.get("/workspaces/container/" + rowId + "/agents/entity");
    assertThat(told.getString("title"), is("Comments on every work entity"));
    assertThat(told.getString("status"), is("IMPLEMENTED"));
    assertThat(told.getBoolean("blocked"), is(true));
    assertTrue(blockedCalls.isEmpty(), "a daemon that knows /entity is not asked the old way");
    Workspace row = storedRow(rowId);
    assertThat(row.entityTitle, is("Comments on every work entity"));
    assertThat(row.entityStatus, is("IMPLEMENTED"));
    assertThat(row.entityBlocked, is(true));

    // Null title and status are members of the contract: unknown now, so cleared on the row.
    entity(entityBody(repoId, "ticket/entity", null, null, false), 200);
    row = storedRow(rowId);
    assertNull(row.entityTitle);
    assertNull(row.entityStatus);
    assertThat(row.entityBlocked, is(false));
  }

  /**
   * A daemon image older than {@code /agents/entity} answers it 404, and is then told the one fact
   * it understands on {@code /agents/blocked} — so the {@code ❗} stays right on a container that
   * has not been recreated yet.
   */
  @Test
  public void anOldDaemonIsToldTheBlockedFlagOnTheRouteItHas() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-old-daemon", "ticket/old-daemon");
    daemonKnowsEntity.set(false);

    JsonPath answer =
        entity(entityBody(repoId, "ticket/old-daemon", "Old", "REPORTED", true), 200);

    assertThat(answer.getBoolean("applied"), is(true));
    assertTrue(entityCalls.isEmpty());
    assertThat(
        blockedCalls
            .get("/workspaces/container/" + rowId + "/agents/blocked")
            .getBoolean("blocked"),
        is(true));
  }

  /**
   * The 405 sibling of {@link #anOldDaemonIsToldTheBlockedFlagOnTheRouteItHas}: a daemon whose
   * {@code /agents/*} router rejects the method for a sub-path it does not know, answering 405
   * rather than 404, still gets the same fallback (qits-617; measured live against
   * 2026.1001.72420, where a 405 on {@code POST /agents/entity} was previously left unhandled and
   * read as "did not take the subject facts").
   */
  @Test
  public void aDaemonThatAnswers405OnEntityIsToldTheBlockedFlagOnTheRouteItHas() throws Exception {
    String repoId = seedRepository();
    Long rowId = workspaceWithContainer(repoId, "ticket-405-daemon", "ticket/405-daemon");
    daemonKnowsEntity.set(false);
    daemonRejectsEntityMethod.set(true);

    JsonPath answer =
        entity(entityBody(repoId, "ticket/405-daemon", "Old", "REPORTED", true), 200);

    assertThat(answer.getBoolean("applied"), is(true));
    assertTrue(entityCalls.isEmpty());
    assertThat(
        blockedCalls
            .get("/workspaces/container/" + rowId + "/agents/blocked")
            .getBoolean("blocked"),
        is(true));
  }

  /**
   * <b>The point of storing them.</b> No container: the row is updated anyway — so the container
   * that comes up later is specced with what is true now — and, {@link
   * #markingBlockedNeverEnsuresAContainerOrLaunchesAnAgent}'s rule, nothing is ensured or launched.
   */
  @Test
  public void markingTheEntityWithNoContainerStoresTheFactsAndStartsNothing() throws Exception {
    String repoId = seedRepository();
    String label = "ticket-entity-cold";
    String branch = "ticket/entity-cold";
    workspaceService.createWorkspace(repoId, label, "master", branch);
    Long rowId = workspaceIds.of(repoId, label);
    String containerName = containerRuntime.containerName(label, repoId);

    JsonPath answer = entity(entityBody(repoId, branch, "Cold", "VERIFIED", true), 200);

    assertThat(answer.getLong("workspaceId"), is(rowId));
    assertThat(answer.getBoolean("applied"), is(false));
    Workspace row = storedRow(rowId);
    assertThat(row.entityTitle, is("Cold"));
    assertThat(row.entityStatus, is("VERIFIED"));
    assertThat(row.entityBlocked, is(true));
    assertFalse(containerRuntime.exists(containerName), "the facts must never ensure a container");
    Thread.sleep(300);
    assertNull(launches.get("/workspaces/container/" + rowId + "/agents"));
    assertTrue(entityCalls.isEmpty() && blockedCalls.isEmpty());
  }

  /**
   * The seam between the two halves, through the shipped beans: what the door stored is what the
   * NEXT container spec for that row says — the persisted port read by the real factory — so a
   * container started after the relay boots with the facts and the {@code ❗}.
   */
  @Test
  public void theStoredFactsAreWhatTheNextContainerSpecCarries() throws Exception {
    String repoId = seedRepository();
    String label = "ticket-entity-spec";
    String branch = "ticket/entity-spec";
    workspaceService.createWorkspace(repoId, label, "master", branch);
    Long rowId = workspaceIds.of(repoId, label);

    entity(entityBody(repoId, branch, "Boots marked", "REFINED", true), 200);

    WorkspaceContainer spec =
        containerFactory.forWorkspace(repoId, label, rowId, branch, "master", "qits-617");
    assertEquals("Boots marked", spec.env().get("QITS_WORKSPACE_DAEMON_ENTITY_TITLE"));
    assertEquals("REFINED", spec.env().get("QITS_WORKSPACE_DAEMON_ENTITY_STATUS"));
    assertEquals("true", spec.env().get("QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED"));

    entity(entityBody(repoId, branch, "Boots marked", "REFINED", false), 200);
    assertNull(
        containerFactory
            .forWorkspace(repoId, label, rowId, branch, "master", "qits-617")
            .env()
            .get("QITS_WORKSPACE_DAEMON_ENTITY_BLOCKED"));
  }

  /**
   * {@code blocked} is required: a body without it would otherwise read as {@code false} and clear
   * a real {@code ❗}. Missing and non-boolean are both a 400, as is a blank branch.
   */
  @Test
  public void markingTheEntityRefusesAMissingOrNonBooleanBlockedFlag() throws Exception {
    String repoId = seedRepository();
    Map<String, Object> missing = entityBody(repoId, "ticket/x", "T", "REFINED", null);
    missing.remove("blocked");

    entity(missing, 400);
    entity(entityBody(repoId, "ticket/x", "T", "REFINED", null), 400);
    entity(entityBody(repoId, "ticket/x", "T", "REFINED", "maybe"), 400);
    entity(entityBody(repoId, "ticket/x", "T", "REFINED", Map.of("no", 1)), 400);
    entity(entityBody(repoId, " ", "T", "REFINED", true), 400);
  }

  // --- workId (qits-112) -----------------------------------------------------------------------

  private static String newWorkId() {
    return java.util.UUID.randomUUID().toString();
  }

  /** A dispatch naming only a ticket binds the workspace to that ticket as its work id. */
  @Test
  public void aDispatchTakesItsWorkIdFromTheTicket() throws Exception {
    String repoId = seedRepository();
    String ticketId = newWorkId();
    dispatch(bodyForTicket(repoId, "ticket/work-from-ticket", ticketId, "go"), 200);

    Long rowId = workspaceIds.of(repoId, "ticket-work-from-ticket");
    assertThat(storedRow(rowId).workId, is(ticketId));
  }

  /** A feature, a task or a campaign has no field of its own: the work id is how it is named. */
  @Test
  public void aDispatchStoresAnExplicitWorkId() throws Exception {
    String repoId = seedRepository();
    String featureId = newWorkId();
    Map<String, Object> request = body(repoId, "feature/work-explicit", null, "go");
    request.put("workId", featureId);
    request.put("entityId", "qits-900");

    dispatch(request, 200);

    Workspace row = storedRow(workspaceIds.of(repoId, "feature-work-explicit"));
    assertThat(row.workId, is(featureId));
    assertNull(row.ticketId);
    assertNull(row.epicId);
  }

  /**
   * The branch is derived from the work item's slug, which can change. A second dispatch for the
   * same work item on a new branch answers the work item's ACTIVE workspace instead of making a
   * second one: a work item has at most one.
   */
  @Test
  public void aDispatchOnANewBranchAnswersTheWorkItemsActiveWorkspace() throws Exception {
    String repoId = seedRepository();
    String ticketId = newWorkId();

    JsonPath first = dispatch(bodyForTicket(repoId, "ticket/old-slug", ticketId, "go"), 200);
    JsonPath second = dispatch(bodyForTicket(repoId, "ticket/new-slug", ticketId, "go"), 200);

    assertThat(second.getBoolean("fresh"), is(false));
    assertThat(second.getLong("workspace.id"), is(first.getLong("workspace.id")));
    assertThat(second.getString("workspace.branch"), is("ticket/old-slug"));
    assertFalse(workspaceService.branchExists(repoId, "ticket/new-slug"));
  }

  /** The relay finds the workspace by its work id, even when the branch it names is not its own. */
  @Test
  public void markingTheEntityFindsTheWorkspaceByWorkId() throws Exception {
    String repoId = seedRepository();
    String ticketId = newWorkId();
    JsonPath made = dispatch(bodyForTicket(repoId, "ticket/relay-by-work", ticketId, "go"), 200);

    Map<String, Object> request =
        entityBody(repoId, "ticket/renamed-since", "New title", "IMPLEMENTING", false);
    request.put("workId", ticketId);
    JsonPath answer = entity(request, 200);

    assertThat(answer.getLong("workspaceId"), is(made.getLong("workspace.id")));
    assertThat(storedRow(made.getLong("workspace.id")).entityTitle, is("New title"));
  }

  /** The references door also answers by work id, and carries the work id and the qualified id. */
  @Test
  public void theReferencesDoorAnswersByWorkId() throws Exception {
    String repoId = seedRepository();
    String taskId = newWorkId();
    Map<String, Object> request = body(repoId, "task/referenced-by-work", null, "go");
    request.put("workId", taskId);
    request.put("entityId", "qits-901");
    dispatch(request, 200);

    JsonPath answer = references("?workId=" + taskId);

    assertThat(answer.getList("entries").size(), is(1));
    assertThat(answer.getString("entries[0].workspace.workId"), is(taskId));
    assertThat(answer.getString("entries[0].workspace.entityId"), is("qits-901"));
  }

  /**
   * {@code GET /work/workspaces} answers ACTIVE workspaces bound to a work item only, and {@code GET
   * /work/{workRef}/workspaces} answers one work item's whole history, newest first, by its work id
   * or its qualified id.
   */
  @Test
  public void theWorkDoorsAnswerOpenWorkspacesAndOneWorkItemsHistory() throws Exception {
    String repoId = seedRepository();
    String ticketId = newWorkId();
    String qualifiedId = "qits-" + Math.abs(ticketId.hashCode());
    Map<String, Object> request = bodyForTicket(repoId, "ticket/history", ticketId, "go");
    request.put("entityId", qualifiedId);

    Long abandoned = dispatch(request, 200).getLong("workspace.id");
    workspaceService.discardWorkspace(abandoned, null, true);
    Long active = dispatch(request, 200).getLong("workspace.id");
    workspaceService.createWorkspace(repoId, "hand-made-history", "master", "hand-made-history");

    JsonPath open =
        given().get("/workspaces/api/work/workspaces").then().statusCode(200).extract().jsonPath();
    List<Long> openIds = open.getList("entries.workspace.id", Long.class);
    assertTrue(openIds.contains(active), "the active workspace is not listed as open");
    assertFalse(openIds.contains(abandoned), "an abandoned workspace is listed as open");
    assertFalse(
        open.getList("entries.workspace.workId", String.class).contains(null),
        "a workspace bound to no work item is listed");

    for (String ref : List.of(ticketId, qualifiedId)) {
      JsonPath history =
          given()
              .get("/workspaces/api/work/" + ref + "/workspaces")
              .then()
              .statusCode(200)
              .extract()
              .jsonPath();
      assertThat(history.getList("entries.workspace.id", Long.class), is(List.of(active, abandoned)));
      assertThat(
          history.getList("entries.workspace.status", String.class),
          is(List.of("ACTIVE", "ABANDONED")));
      assertThat(history.getString("entries[0].workspace.qualifiedId"), is(qualifiedId));
    }

    JsonPath none =
        given()
            .get("/workspaces/api/work/" + newWorkId() + "/workspaces")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertTrue(none.getList("entries").isEmpty());
  }
}
