package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tunnel's dial-back for a RUNNER row (qits-812): the nonce is the second factor, and the
 * bearer is the first — a validated token whose {@code sub} is the row's token subject. No bearer
 * and a wrong subject both get the bare 404 an unknown nonce gets; the right subject pipes. A DIRECT
 * row's dial-back still pipes on the nonce alone.
 *
 * <p>The cases {@link DaemonStreamRouteTest} would hold, here because they need bearers this
 * service validates: the gate-on profile of {@link DaemonControlSocketMachineAuthTest}, with real
 * RS256 tokens. The stream is opened at the tunnel's loopback entrance directly ({@link
 * WorkspaceTunnels#originFor}), because the browser-facing proxy in front of it is not this test's
 * subject and wants a session the gate-on profile does not give.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DaemonStreamRouteRunnerTest {

  private static final String PLATFORM_AUDIENCE = "qits-platform";

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceIds workspaceIds;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceRepository workspaceRepository;
  @Inject WorkspaceTunnels tunnels;
  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  @TestHTTPResource("/")
  URI base;

  private Vertx vertx;
  private HttpServer daemonApi;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** The Authorization header the fake daemon's dial-back sends; null sends none. */
  private volatile String dialBackAuthorization;

  /** The status of the fake daemon's last dial-back: 101, or the status it was refused with. */
  private volatile CompletableFuture<Integer> dialBackStatus;

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    daemonApi = vertx.createHttpServer();
    daemonApi.requestHandler(req -> req.response().end("daemon:" + req.uri()));
    await(daemonApi.listen(0, "127.0.0.1"));
    dialBackStatus = new CompletableFuture<>();
  }

  @AfterEach
  void tearDown() throws Exception {
    tunnels.closeAll();
    if (controlSocket != null) {
      controlSocket.close();
    }
    wsClient.close();
    netClient.close();
    daemonApi.close();
    await(vertx.close());
  }

  @Test
  void aRunnerDialBackWithNoBearerIsA404() throws Exception {
    Long id = runnerWorkspace("tok-workspace-a");
    dialBackAuthorization = null;

    assertStreamRefused(id);
  }

  @Test
  void aRunnerDialBackWithAnotherSubjectIsA404() throws Exception {
    Long id = runnerWorkspace("tok-workspace-b");
    dialBackAuthorization = bearer("tok-workspace-somebody-else");

    assertStreamRefused(id);
  }

  @Test
  void aRunnerDialBackWithTheRowsTokenSubjectPipes() throws Exception {
    Long id = runnerWorkspace("tok-workspace-c");
    dialBackAuthorization = bearer("tok-workspace-c");

    assertEquals("daemon:/files", get(id, "/files"));
    assertEquals(101, dialBackStatus.get(10, TimeUnit.SECONDS));
  }

  @Test
  void aDirectDialBackStillPipesOnTheNonceAlone() throws Exception {
    Long id = workspace("direct");
    connectFakeDaemon(id);
    dialBackAuthorization = null;

    assertEquals("daemon:/files", get(id, "/files"));
    assertEquals(101, dialBackStatus.get(10, TimeUnit.SECONDS));
  }

  // --- helpers ------------------------------------------------------------------------------------

  private static String bearer(String subject) {
    return "Bearer "
        + DaemonMachineTokens.tokenWithRoles(subject, Set.of("qits:agent"), PLATFORM_AUDIENCE);
  }

  /** A RUNNER row holding a token with {@code subject}, its fake daemon connected. */
  private Long runnerWorkspace(String subject) throws Exception {
    Long id = workspace("runner");
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              var row = workspaceRepository.findActiveById(id).orElseThrow();
              row.placement = WorkspacePlacement.RUNNER;
              row.commissionedTokenId = "tok-id-" + id;
              row.commissionedTokenSubject = subject;
              row.commissionedToken = "qits_tok_" + id;
            });
    connectFakeDaemon(id);
    return id;
  }

  private Long workspace(String prefix) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    String label = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    workspaceService.createWorkspace(repoId, label, "master", label);
    return workspaceIds.of(repoId, label);
  }

  /** The stream was refused at the dial-back with a 404, and the loopback request got nothing. */
  private void assertStreamRefused(Long id) throws Exception {
    assertTrue(tunnels.originFor(id).isPresent(), "the tunnel is there to be refused through");
    assertThrows(Exception.class, () -> get(id, "/files"));
    assertEquals(404, dialBackStatus.get(10, TimeUnit.SECONDS));
  }

  /** One request into the tunnel's loopback entrance, answered by the fake daemon's API. */
  private String get(Long id, String path) throws Exception {
    WorkspaceTunnels.TunnelOrigin origin = tunnels.originFor(id).orElseThrow();
    return await(
        origin
            .client()
            .request(HttpMethod.GET, origin.port(), "127.0.0.1", path)
            .compose(request -> request.send())
            .compose(HttpClientResponse::body)
            .map(Object::toString));
  }

  /**
   * A fake daemon on {@code id}'s control socket, with a system bearer, that answers every {@code
   * OpenStream} by dialling the real stream route with {@link #dialBackAuthorization} and piping to
   * {@link #daemonApi}.
   */
  private void connectFakeDaemon(Long id) throws Exception {
    controlSocket =
        await(
            wsClient.connect(
                new WebSocketConnectOptions()
                    .setHost(base.getHost())
                    .setPort(base.getPort())
                    .setURI("/workspaces/daemon/" + id)
                    .addHeader(
                        "Authorization",
                        "Bearer " + DaemonMachineTokens.token("svc", PLATFORM_AUDIENCE))));
    controlSocket.textMessageHandler(
        text -> {
          DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
          if (message instanceof OpenStream open) {
            serveStream(open);
          }
        });
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello(
                        "ws-" + id,
                        "repo",
                        "work",
                        "master",
                        DaemonProtocol.TUNNEL_CAPABILITY_VERSION,
                        "test",
                        null)))
            .encode());
    // The Hello has to have been processed — its capability recorded — before a tunnel is asked for.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (!helloSeen(id) && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    assertTrue(helloSeen(id), "the daemon's Hello registered");
  }

  private boolean helloSeen(Long id) {
    return registry
        .lookup(id)
        .filter(info -> info.capabilityVersion() == DaemonProtocol.TUNNEL_CAPABILITY_VERSION)
        .isPresent();
  }

  private void serveStream(OpenStream open) {
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(base.getHost())
            .setPort(base.getPort())
            .setURI(open.path());
    if (dialBackAuthorization != null) {
      options.addHeader("Authorization", dialBackAuthorization);
    }
    netClient
        .connect(daemonApi.actualPort(), "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(options)
                    .onSuccess(
                        remote -> {
                          dialBackStatus.complete(101);
                          pipe(remote, local);
                        })
                    .onFailure(
                        t -> {
                          dialBackStatus.complete(
                              t instanceof UpgradeRejectedException rejected
                                  ? rejected.getStatus()
                                  : -1);
                          local.close();
                        }));
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

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
