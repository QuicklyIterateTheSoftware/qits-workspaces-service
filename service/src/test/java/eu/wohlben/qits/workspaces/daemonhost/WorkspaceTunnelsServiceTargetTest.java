package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.SharedTuningProfile;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link StreamTarget#SERVICE} target of the reverse tunnel (qits-625, qits-815): one listener
 * per {@code (workspace, SERVICE, serviceId)}, an {@code OpenStream} that names the service by id,
 * and a capability gate at {@link WorkspaceTunnels#SERVICE_CAPABILITY_VERSION}.
 *
 * <p>The fake daemon only records what it is asked; that a stream it serves carries bytes both ways
 * is {@code DaemonStreamRouteTest}'s and {@code EditorTunnelRouteTest}'s to prove, and nothing in
 * the pipe knows which target it is carrying. What is new here is the key and the gate, and both
 * are claims about this class alone.
 */
@QuarkusTest
@TestProfile(SharedTuningProfile.class)
public class WorkspaceTunnelsServiceTargetTest {

  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceTunnels tunnels;
  @Inject WorkspaceDaemonRegistry registry;

  private Vertx vertx;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** Every OpenStream the fake daemon was asked for. */
  private final CopyOnWriteArrayList<OpenStream> asked = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    asked.clear();
  }

  @AfterEach
  void tearDown() throws Exception {
    tunnels.closeAll();
    if (controlSocket != null) {
      controlSocket.close();
    }
    await(vertx.close());
  }

  @Test
  public void twoServicesOfOneWorkspaceGetTwoListenersAndAreAskedForByName() throws Exception {
    Long id = workspaceService.createEditorWorkspace().id;
    connectFakeDaemon(id, WorkspaceTunnels.SERVICE_CAPABILITY_VERSION);

    int web = tunnels.originFor(id, StreamTarget.SERVICE, "web").orElseThrow().port();
    int docs = tunnels.originFor(id, StreamTarget.SERVICE, "docs").orElseThrow().port();
    assertNotEquals(web, docs, "two dev servers must never share one listening port");
    assertEquals(
        web,
        tunnels.originFor(id, StreamTarget.SERVICE, "web").orElseThrow().port(),
        "the same service resolves to the same live tunnel");
    // Neither is the API's or the editor's listener either.
    int api = tunnels.originFor(id, StreamTarget.API).orElseThrow().port();
    assertTrue(api != web && api != docs, "the API's listener is its own");
    // A SERVICE that names no service names nothing.
    assertTrue(tunnels.originFor(id, StreamTarget.SERVICE, null).isEmpty());
    assertTrue(tunnels.originFor(id, StreamTarget.SERVICE, " ").isEmpty());

    // A connection to each listener asks the daemon for THAT service, by id and as SERVICE.
    NetSocket toWeb = await(netClient.connect(web, "127.0.0.1"));
    NetSocket toDocs = await(netClient.connect(docs, "127.0.0.1"));
    awaitAsked(2);
    assertEquals(
        List.of("SERVICE/docs", "SERVICE/web"),
        asked.stream().map(o -> o.target() + "/" + o.serviceId()).sorted().toList());
    asked.forEach(
        open ->
            assertTrue(
                open.path().startsWith(WorkspaceTunnels.STREAM_PATH_PREFIX), open.path()));
    toWeb.close();
    toDocs.close();
  }

  @Test
  public void aDaemonBelowCapabilitySixIsNeverAskedForAServiceStream() throws Exception {
    Long id = workspaceService.createEditorWorkspace().id;
    connectFakeDaemon(id, WorkspaceTunnels.SERVICE_CAPABILITY_VERSION - 1);

    assertEquals(
        OptionalInt.of(WorkspaceTunnels.SERVICE_CAPABILITY_VERSION - 1),
        tunnels.daemonCapability(id));
    // An older daemon decodes the unknown target as API and would serve the wrong listener.
    assertTrue(
        tunnels.originFor(id, StreamTarget.SERVICE, "web").isEmpty(),
        "SERVICE is gated on capability " + WorkspaceTunnels.SERVICE_CAPABILITY_VERSION);
    // The same daemon still serves what it understands: the gate is per target.
    assertTrue(tunnels.originFor(id, StreamTarget.EDITOR).isPresent());
    assertTrue(asked.isEmpty(), "a daemon that cannot serve SERVICE must not be asked for one");
  }

  @Test
  public void noDaemonNoCapability() {
    Long id = workspaceService.createEditorWorkspace().id;
    assertEquals(OptionalInt.empty(), tunnels.daemonCapability(id));
    assertTrue(tunnels.originFor(id, StreamTarget.SERVICE, "web").isEmpty());
  }

  // --- helpers ------------------------------------------------------------------------------------

  private void connectFakeDaemon(Long workspaceId, int capabilityVersion) throws Exception {
    controlSocket =
        await(wsClient.connect(RestAssured.port, "127.0.0.1", "/workspaces/daemon/" + workspaceId));
    controlSocket.textMessageHandler(
        text -> {
          DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
          if (message instanceof OpenStream open) {
            asked.add(open);
          }
        });
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello("master", "repo", "work", "master", capabilityVersion, "test", null)))
            .encode());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Integer seen = registry.lookup(workspaceId).map(info -> info.capabilityVersion()).orElse(null);
      if (seen != null && seen == capabilityVersion) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's Hello never registered");
  }

  private void awaitAsked(int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (asked.size() >= count) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("asked for " + asked.size() + " streams, wanted " + count);
  }

  private static <T> T await(Future<T> future) throws Exception {
    return future.toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
  }
}
