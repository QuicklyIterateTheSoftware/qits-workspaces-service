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
@TestProfile(TunnelNonceProfile.class)
public class DaemonStreamRouteTest {

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceTunnels tunnels;

  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx vertx;
  private HttpServer daemonApi;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** Every OpenStream the fake daemon was asked for. */
  private final CopyOnWriteArrayList<OpenStream> asked = new CopyOnWriteArrayList<>();

  /** Set when the fake daemon should ignore an OpenStream instead of dialling back. */
  private volatile boolean deaf;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    daemonApi = vertx.createHttpServer();
    daemonApi.requestHandler(
        req -> req.response().end("daemon:" + req.uri() + ":" + req.getHeader("Authorization")));
    daemonApi.webSocketHandler(
        (ServerWebSocket socket) ->
            socket.textMessageHandler(text -> socket.writeTextMessage("ws-daemon:" + text)));
    await(daemonApi.listen(0, "127.0.0.1"));
    asked.clear();
    deaf = false;
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

  // --- helpers ------------------------------------------------------------------------------------

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
