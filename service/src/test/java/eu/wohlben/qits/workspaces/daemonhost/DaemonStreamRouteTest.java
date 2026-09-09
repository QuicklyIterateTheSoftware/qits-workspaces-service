package eu.wohlben.qits.workspaces.daemonhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reverse tunnel end to end inside one JVM: a fake daemon holds a real control socket to the
 * application, answers {@code OpenStream} by dialling the real stream route, and pipes that back to
 * a real HTTP server standing in for its own {@code WorkspaceApi}. A real request at {@code
 * /workspaces/container/{id}/…} then has to traverse all of it.
 *
 * <p>No docker. The daemon side is only two things — a WebSocket client and a byte pump — and both
 * are the same in a container and in this JVM, so the parts a container would add (a real image, a
 * real checkout) are the {@code DaemonApiGateIT}'s subject rather than this one's.
 *
 * <p>The capability version is what the fake daemon varies to exercise both branches: announce 4 and
 * the request must arrive over the tunnel, announce 3 and it must not — because a daemon at 3 is
 * still listening on {@code qits-net} and one at 4 is not.
 */
@QuarkusTest
@TestProfile(DaemonStreamRouteTest.TestProfile.class)
public class DaemonStreamRouteTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-daemon-stream-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            // Short enough that the expiry test does not dominate the run, long enough that a
            // loopback dial-back never loses the race.
            "qits.workspace.daemon-tunnel.nonce-ttl-ms", "8000");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceTunnels tunnels;

  @Inject WorkspaceDaemonRegistry registry;

  @Inject WorkspaceCapabilityRelay relay;

  @Inject eu.wohlben.qits.workspaces.control.FakeAgentCapabilitySink catalogue;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  /** The workspace image pin this service creates containers from — the relay's fallback key. */
  @ConfigProperty(name = "qits.workspace.image-version")
  String imageVersion;

  private Vertx vertx;
  private HttpServer daemonApi;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** Every OpenStream the fake daemon was asked for. */
  private final CopyOnWriteArrayList<OpenStream> asked = new CopyOnWriteArrayList<>();

  /** Set when the fake daemon should ignore an OpenStream instead of dialling back. */
  private volatile boolean deaf;

  // --- the capability relay's half of this fixture -------------------------------------------------

  /** A complete report, of the shape {@code AgentJson.available} builds in qits-workspace-daemon. */
  private static final String REPORT =
      """
      {"agents":["CLAUDE","KIMI"],"defaultAgent":"CLAUDE",\
      "imageVersion":"2026.909.tunnelrelay","reportedBy":"qits-workspace-daemon",\
      "capabilities":[{"harness":"CLAUDE","harnessVersion":"2.1.226",\
      "models":["opus","sonnet"],"modelsEnumerated":false,\
      "effortSupported":true,"effortLevels":["low","high"],\
      "authenticated":true,"authDetail":"","probeFailed":false,"probeDetail":""}]}""";

  /**
   * What the daemon answers before its probe has landed: the route is served the moment the agent
   * surface is wired, from a supplier over a volatile field that is {@code List.of()} until a second
   * worker assigns it. Byte for byte a real one, minus the reports.
   */
  private static final String NOT_PROBED_YET =
      "{\"agents\":[\"CLAUDE\"],\"defaultAgent\":\"CLAUDE\","
          + "\"imageVersion\":\"2026.909.tunnelrelay\",\"reportedBy\":\"qits-workspace-daemon\","
          + "\"capabilities\":[]}";

  /** How many times {@code /agents/available} has been asked, so a retry is counted not inferred. */
  private final java.util.concurrent.atomic.AtomicInteger availableReads =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * How many more times it answers <b>200 with nothing in it</b> before it answers anything else.
   *
   * <p>The shape qits-projects' relay was measured taking, and the one that cost it a second
   * release: the tunnel connects, the daemon has nothing to pipe from yet, and what comes back is a
   * successful hop with an empty body. Handed straight to Jackson that is a parse failure, and a
   * parse failure is terminal.
   */
  private final java.util.concurrent.atomic.AtomicInteger emptyBodyAnswersRemaining =
      new java.util.concurrent.atomic.AtomicInteger();

  /** How many more times it answers {@link #NOT_PROBED_YET} before answering {@link #REPORT}. */
  private final java.util.concurrent.atomic.AtomicInteger emptyAnswersRemaining =
      new java.util.concurrent.atomic.AtomicInteger();

  /** The status it answers with; 404 is the daemon that does not serve the route at all. */
  private volatile int availableStatus = 200;

  /** What a 200 carries once {@link #emptyAnswersRemaining} is exhausted. */
  private volatile String availableBody = REPORT;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    daemonApi = vertx.createHttpServer();
    daemonApi.requestHandler(
        req -> {
          if (req.uri().endsWith("/" + WorkspaceCapabilityRelay.AVAILABLE_PATH)) {
            answerAvailable(req);
            return;
          }
          req.response().end("daemon:" + req.uri() + ":" + req.getHeader("Authorization"));
        });
    daemonApi.webSocketHandler(
        (ServerWebSocket socket) ->
            socket.textMessageHandler(text -> socket.writeTextMessage("ws-daemon:" + text)));
    await(daemonApi.listen(0, "127.0.0.1"));
    asked.clear();
    deaf = false;
    availableReads.set(0);
    emptyAnswersRemaining.set(0);
    emptyBodyAnswersRemaining.set(0);
    availableStatus = 200;
    availableBody = REPORT;
    catalogue.reset();
  }

  @AfterEach
  void tearDown() throws Exception {
    // The application's tunnels outlive a test otherwise: each one holds a NetServer and an
    // HttpClient wired to a fake daemon whose Vert.x is about to be closed, and the next test would
    // inherit that. In production a tunnel's lifetime is its daemon's; here it has to be the test's.
    tunnels.closeAll();
    if (controlSocket != null) {
      controlSocket.close();
    }
    if (wsClient != null) {
      wsClient.close();
    }
    if (netClient != null) {
      netClient.close();
    }
    if (daemonApi != null) {
      daemonApi.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  /**
   * Connect a fake daemon for {@code workspaceId} announcing {@code capabilityVersion}, and have it
   * serve every {@code OpenStream} by dialling the real stream route and piping to {@link
   * #daemonApi}. Exactly what {@code DaemonStreamTunnel} does, in the test's own words.
   */
  private void connectFakeDaemon(Long workspaceId, int capabilityVersion) throws Exception {
    controlSocket =
        await(
            wsClient.connect(
                RestAssured.port, "127.0.0.1", "/workspaces/daemon/" + workspaceId));
    controlSocket.textMessageHandler(
        text -> {
          DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
          if (message instanceof OpenStream open) {
            asked.add(open);
            if (!deaf) {
              serveStream(open);
            }
          }
        });
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello(
                        "ws-" + workspaceId,
                        "repo",
                        "work",
                        "master",
                        capabilityVersion,
                        "test",
                        null)))
            .encode());
    // The Hello has to have been processed before the proxy asks for a capability version.
    awaitCapability(workspaceId, capabilityVersion);
  }

  /**
   * The loopback connection first and the dial-back second, and both ends paused until the handlers
   * exist — exactly what the real {@code DaemonStreamTunnel} does, and for the same reason: the host
   * writes the moment its upgrade completes, so anything read before there is a handler to take it
   * is lost, and a lost request line is a request that never answers.
   */
  private void serveStream(OpenStream open) {
    netClient
        .connect(daemonApi.actualPort(), "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(RestAssured.port, "127.0.0.1", open.path())
                    .onSuccess(remote -> pipe(remote, local))
                    .onFailure(t -> local.close()));
  }

  private static void pipe(WebSocket remote, NetSocket local) {
    remote.pause();
    local.pause();
    remote.handler(local::write);
    local.handler(remote::writeBinaryMessage);
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
    remote.resume();
    local.resume();
  }

  private Long workspace() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    return workspaceIds.of(repoId, "work");
  }

  @Test
  public void aRequestTraversesTheTunnelWhenTheDaemonCanServeOne() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);

    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        .statusCode(200)
        // The daemon's path arrives unstripped and the bearer arrives with it — the tunnel is
        // transport, and changes neither.
        .body(containsString("daemon:/workspaces/container/" + id + "/files:Bearer "));

    assertEquals(1, asked.size(), "exactly one stream was asked for");
    assertTrue(
        asked.getFirst().path().startsWith(WorkspaceTunnels.STREAM_PATH_PREFIX),
        asked.getFirst().path());
  }

  @Test
  public void aWebSocketUpgradeTraversesTheTunnel() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);

    // The case the byte pipe exists for. A terminal socket now crosses two proxies and a tunnel,
    // and neither end knows about any of it.
    CompletableFuture<String> reply = new CompletableFuture<>();
    WebSocket browser =
        await(
            wsClient.connect(
                RestAssured.port,
                "127.0.0.1",
                "/workspaces/container/" + id + "/terminal/commands/abc"));
    browser.textMessageHandler(reply::complete);
    browser.writeTextMessage("{\"type\":\"data\",\"data\":\"k\"}");

    assertEquals("ws-daemon:{\"type\":\"data\",\"data\":\"k\"}", reply.get(20, TimeUnit.SECONDS));
    browser.close();
  }

  @Test
  public void aDaemonBelowTheTunnelCapabilityIsNeverAskedForAStream() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION - 1);

    // It is still listening on qits-net, so the direct branch is right — and here that means the
    // FakeContainerRuntime origin, which no container is behind, so a 502. What matters is that no
    // stream was asked for: asking one of these for a tunnel would hang until the nonce expired.
    given().get("/workspaces/container/" + id + "/files").then().statusCode(502);

    assertTrue(asked.isEmpty(), "a daemon that still listens must not be asked to dial back");
  }

  @Test
  public void aNonceIsSingleUse() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    given().get("/workspaces/container/" + id + "/files").then().statusCode(200);

    String nonce = asked.getFirst().nonce();
    // Replay. The claim is an atomic map removal, so single-use is structural rather than a rule
    // someone has to remember — and a 404 says nothing about whether it was unknown or spent.
    assertEquals(404, upgradeStatus(WorkspaceTunnels.STREAM_PATH_PREFIX + nonce));
  }

  @Test
  public void anUnknownNonceIsRefused() throws Exception {
    assertEquals(404, upgradeStatus(WorkspaceTunnels.STREAM_PATH_PREFIX + "not-a-real-nonce"));
  }

  @Test
  public void theBarePrefixWithoutATrailingSlashIsA404NotAnError() {
    // `route(PREFIX + "*")` matches the prefix with no trailing slash too, one character short of
    // the prefix itself, and the nonce substring used to overflow into a 500 there. A plain GET,
    // not an upgrade: the guard sits before the upgrade attempt.
    given()
        .get(
            WorkspaceTunnels.STREAM_PATH_PREFIX.substring(
                0, WorkspaceTunnels.STREAM_PATH_PREFIX.length() - 1))
        .then()
        .statusCode(404);
  }

  @Test
  public void aDaemonThatNeverDialsBackExpiresTheStreamRatherThanHanging() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    deaf = true;

    // The parked socket is closed when the TTL fires, which is what turns this into a connection
    // error rather than a request that hangs until some other timeout notices.
    given().get("/workspaces/container/" + id + "/files").then().statusCode(502);
    assertEquals(1, asked.size(), "it was asked; it simply never came");
  }

  @Test
  public void aLiveTunnelSurvivesButPendingNoncesDoNot() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    given().get("/workspaces/container/" + id + "/files").then().statusCode(200);

    // A control socket bouncing must not take file browsing and open terminals down with it — that
    // availability coupling is the whole reason these calls do not ride the control socket. What a
    // disconnect does drop is nonces that were waiting on the daemon that just left.
    deaf = true;
    given().get("/workspaces/container/" + id + "/files"); // parks a nonce, never claimed
    controlSocket.close();
    Thread.sleep(300);
    assertTrue(
        tunnels.claim(asked.getLast().nonce()).isEmpty(),
        "a pending nonce must not outlive the daemon it was minted for");
  }

  /**
   * <b>The regression this relay exists as it does because of.</b> A container that has said {@code
   * Hello} cannot answer this yet, and the way it says so is <em>an empty capabilities array</em> —
   * not a 503 and not a failed hop, which is what qits-projects' sibling relay treats as "not yet".
   *
   * <p>Read from {@code ControlSocket} in qits-workspace-daemon: the clone's worker binds the
   * loopback API, wires the agent surface — from which moment {@code GET /agents/available} answers
   * <b>200</b>, out of {@code () -> harnessCapabilities} — and only then probes the harnesses on a
   * second worker, which is what finally assigns that volatile field. So an empty list is the
   * ordinary answer for the first seconds of every container, and a relay that read it as "an older
   * daemon, terminal" (which is exactly what it means over in qits-projects, where the probe runs
   * before the bind) would record nothing on nearly every container and say nothing about it.
   */
  @Test
  public void aProbeThatHasNotLandedIsAskedAgainUntilTheReportArrives() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    emptyAnswersRemaining.set(2);

    relay.relay(id);

    assertEquals(
        3,
        availableReads.get(),
        "an empty capabilities array is not an answer: the relay asks again until the probe lands");
    assertEquals(1, catalogue.recorded().size(), "the report reached the catalogue exactly once");
    assertEquals(
        REPORT,
        catalogue.recorded().getFirst(),
        "the daemon's body must reach the ingest door unchanged — the relay has no opinion on it");
  }

  /**
   * <b>The same rule one layer earlier, and the arm that cost the sibling relay a second release.</b>
   * The not-ready window does not only present as a 503 or a failed hop: it presents as a
   * <em>successful</em> hop carrying an empty body, because the tunnel connects before the daemon
   * has anything behind it to pipe from. Measured live against qits-projects on 2026-09-09, where
   * that body reached Jackson as "No content to map due to end-of-input" and landed in the terminal
   * broken arm — one attempt, a WARN saying the daemon spoke an unreadable contract, and a
   * catalogue that stayed empty for ever.
   *
   * <p>A body that is absent says nothing about capabilities and can never be the reason to stop
   * asking. Only a body genuinely present and unparseable is broken.
   */
  @Test
  public void anEmptyBodyIsAskedAgainRatherThanReadAsABrokenContract() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    emptyBodyAnswersRemaining.set(2);

    relay.relay(id);

    assertEquals(
        3, availableReads.get(), "an empty body is not an answer: the relay asks again");
    assertEquals(1, catalogue.recorded().size(), "and the report it finally got was recorded");
    assertEquals(REPORT, catalogue.recorded().getFirst());
  }

  /**
   * The other half of the rule, and the one that keeps the retry from being unbounded in disguise: a
   * daemon that does not serve the route at all is <b>absence</b>, terminal and quiet. Asking a
   * second time gets the same 404 for ever.
   */
  @Test
  public void aDaemonThatDoesNotServeTheRouteIsReadOnceAndNotRetried() throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    availableStatus = 404;

    relay.relay(id);

    assertEquals(1, availableReads.get(), "a 404 is absence, and absence is terminal");
    assertTrue(catalogue.recorded().isEmpty(), "nothing was reported, so nothing is recorded");
  }

  /**
   * The one edit the relay makes, and the only one: {@code imageVersion} is half the key the
   * catalogue stores under, and {@code "unknown"} is what the daemon answers when it could not name
   * its own build. This service chose the pin the container was created from, so it fills that blank
   * rather than letting every unnamed build share one row.
   */
  @Test
  public void anUnnamedImageBuildIsFilledFromThePinThisServiceCreatedTheContainerFrom()
      throws Exception {
    Long id = workspace();
    connectFakeDaemon(id, DaemonProtocol.TUNNEL_CAPABILITY_VERSION);
    availableBody = REPORT.replace("2026.909.tunnelrelay", "unknown");

    relay.relay(id);

    assertEquals(1, catalogue.recorded().size());
    assertTrue(
        catalogue.recorded().getFirst().contains("\"imageVersion\":\"" + imageVersion + "\""),
        catalogue.recorded().getFirst());
  }

  // --- helpers ------------------------------------------------------------------------------------

  /** The fake daemon's {@code /agents/available}: a scripted boot window, then the report. */
  private void answerAvailable(io.vertx.core.http.HttpServerRequest request) {
    availableReads.incrementAndGet();
    if (availableStatus != 200) {
      request.response().setStatusCode(availableStatus).end("{\"error\":\"no such route\"}");
      return;
    }
    if (emptyBodyAnswersRemaining.getAndDecrement() > 0) {
      request.response().end();
      return;
    }
    String body = emptyAnswersRemaining.getAndDecrement() > 0 ? NOT_PROBED_YET : availableBody;
    request.response().putHeader("Content-Type", "application/json").end(body);
  }

  /** The HTTP status a WebSocket upgrade to {@code path} was refused with. */
  private int upgradeStatus(String path) throws Exception {
    AtomicReference<Integer> status = new AtomicReference<>();
    try {
      await(wsClient.connect(RestAssured.port, "127.0.0.1", path)).close();
      return 101;
    } catch (Exception e) {
      // Vert.x reports the refusal status in the message of an UpgradeRejectedException.
      String text = String.valueOf(e.getCause() == null ? e : e.getCause());
      status.set(text.contains("404") ? 404 : -1);
    }
    return status.get();
  }

  /** Poll until the registry has recorded the announced capability version. */
  private void awaitCapability(Long workspaceId, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Integer seen =
          registry.lookup(workspaceId).map(info -> info.capabilityVersion()).orElse(null);
      if (seen != null && seen == expected) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's Hello never registered");
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
