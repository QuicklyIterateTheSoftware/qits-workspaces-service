package eu.wohlben.qits.workspaces.daemonhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.EditorState;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import jakarta.inject.Inject;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The editor over the reverse tunnel, end to end in one JVM: a fake daemon holds a real control
 * socket, answers an {@code OpenStream} by dialling the real stream route back, and pipes that to a
 * real HTTP server standing in for openvscode-server on its container's loopback. A browser request
 * on {@code editor.<project>.…} then has to traverse all of it.
 *
 * <p>{@link DaemonStreamRouteTest}'s shape, one target over — and <b>this is where the editor's data
 * path is proved</b>, because the tunnel is the only transport it has. The daemon binds
 * openvscode-server to the container's LOOPBACK, so there is no address on {@code qits-net} that
 * reaches an editor and no second arm to test: the verbatim path, the identity strip on both
 * transports, the bounded pipe and the upgrade are all claims about this rig or about nothing.
 *
 * <p><b>The stream is asked for by NAME.</b> The {@code OpenStream} carries {@link
 * StreamTarget#EDITOR}, so the daemon resolves it against its own allow-list and the host never
 * states a port inside the container. That the frame says {@code EDITOR} is asserted directly,
 * because it is the only thing that keeps an editor request off the daemon's own API.
 *
 * <p><b>A daemon that predates the editor is never asked</b> — neither when the origin is resolved
 * nor when a socket the resolution admitted is accepted a moment later. An older image decodes an
 * absent target as {@code API}, so a name it has never heard of would be served by the wrong
 * listener rather than refused; which is why the gate is a capability version and not a hope, and
 * why it is read twice.
 *
 * <p><b>The fake daemon's own pipe carries backpressure</b>, exactly as the real
 * {@code DaemonStreamTunnel} does. Without it the flood case would prove nothing: an unbounded hop
 * inside the fixture would happily swallow everything the editor produced and the assertion would
 * fail on the fixture rather than on the route.
 */
@QuarkusTest
@TestProfile(TunnelNonceProfile.class)
public class EditorTunnelRouteTest {

  @Inject FakeRepositoryLookup repositories;
  @Inject WorkspaceService workspaceService;
  @Inject WorkspaceTunnels tunnels;
  @Inject WorkspaceDaemonRegistry registry;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx vertx;
  private HttpServer editor;
  private WebSocketClient wsClient;
  private NetClient netClient;
  private WebSocket controlSocket;

  /** Every OpenStream the fake daemon was asked for. */
  private final CopyOnWriteArrayList<OpenStream> asked = new CopyOnWriteArrayList<>();

  /** What the last request — of either transport — arrived at the editor carrying. */
  private final AtomicReference<MultiMap> lastEditorHeaders = new AtomicReference<>();

  /** The text frame that asks the fake editor to write N 64 KiB binary frames as fast as it can. */
  private static final String FLOOD = "flood:";

  private static final int FLOOD_FRAME_BYTES = 64 * 1024;

  private final AtomicReference<CompletableFuture<Void>> floodWritten =
      new AtomicReference<>(new CompletableFuture<>());

  @BeforeEach
  void setUp() throws Exception {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    editor = vertx.createHttpServer();
    editor.requestHandler(
        req -> {
          lastEditorHeaders.set(req.headers());
          req.response().end("editor:" + req.uri() + ":" + req.getHeader("X-Qits-User"));
        });
    editor.webSocketHandler(
        (ServerWebSocket socket) -> {
          lastEditorHeaders.set(socket.headers());
          socket.textMessageHandler(
              text -> {
                if (text.startsWith(FLOOD)) {
                  flood(socket, Integer.parseInt(text.substring(FLOOD.length())));
                } else {
                  socket.writeTextMessage("ws-editor:" + text);
                }
              });
        });
    await(editor.listen(0, "127.0.0.1"));
    asked.clear();
    lastEditorHeaders.set(null);
    floodWritten.set(new CompletableFuture<>());
  }

  /** A well-behaved producer: stop on a full write queue, resume on drain. */
  private void flood(ServerWebSocket ws, int remaining) {
    Buffer frame = Buffer.buffer(new byte[FLOOD_FRAME_BYTES]);
    int left = remaining;
    while (left > 0 && !ws.writeQueueFull()) {
      ws.writeBinaryMessage(frame);
      left--;
    }
    if (left == 0) {
      floodWritten.get().complete(null);
      return;
    }
    int rest = left;
    ws.drainHandler(
        drained -> {
          ws.drainHandler(null);
          flood(ws, rest);
        });
  }

  @AfterEach
  void tearDown() throws Exception {
    // The application's tunnels outlive a test otherwise: each holds a NetServer and an HttpClient
    // wired to a fake daemon whose Vert.x is about to close. In production a tunnel's lifetime is
    // its daemon's; here it has to be the test's.
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
    if (editor != null) {
      editor.close();
    }
    if (vertx != null) {
      await(vertx.close());
    }
  }

  /**
   * A fake daemon for {@code workspaceId} announcing {@code capabilityVersion}, serving every {@code
   * OpenStream} by dialling the real stream route and piping it to {@link #editor} — which stands in
   * for the ONE listener this test is about, so a frame that named the API would fail visibly rather
   * than quietly succeed against a second stub.
   */
  private void connectFakeDaemon(Long workspaceId, int capabilityVersion) throws Exception {
    controlSocket =
        await(wsClient.connect(RestAssured.port, "127.0.0.1", "/workspaces/daemon/" + workspaceId));
    controlSocket.textMessageHandler(
        text -> {
          DaemonMessage message = DaemonCodec.decode(new JsonObject(text).getMap());
          if (message instanceof OpenStream open) {
            asked.add(open);
            serveStream(open);
          }
        });
    controlSocket.writeTextMessage(
        new JsonObject(
                DaemonCodec.encode(
                    new Hello(
                        "master",
                        "repo",
                        "work",
                        "master",
                        capabilityVersion,
                        "test",
                        null)))
            .encode());
    awaitCapability(workspaceId, capabilityVersion);
  }

  /** What a daemon that supervises an editor says on connect and on every transition. */
  private void reportEditor(Long workspaceId, String state) throws Exception {
    controlSocket.writeTextMessage(
        new JsonObject(DaemonCodec.encode(new EditorState(state))).encode());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (registry.editorStateFor(workspaceId).map(Enum::name).filter(state::equals).isPresent()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's EditorState never registered");
  }

  private void serveStream(OpenStream open) {
    netClient
        .connect(editor.actualPort(), "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(RestAssured.port, "127.0.0.1", open.path())
                    .onSuccess(remote -> pipe(remote, local))
                    .onFailure(t -> local.close()));
  }

  /**
   * The fake daemon's half of the tunnel, and it pauses and drains in both directions <b>on
   * purpose</b> — the real {@code DaemonStreamTunnel} does, and the flood case is only a statement
   * about this service's pipe if every other hop in the fixture is bounded too. A bare {@code
   * a.handler(b::write)} here would absorb the whole flood inside the test's own Vert.x and report
   * the route as leaking.
   */
  private static void pipe(WebSocket remote, NetSocket local) {
    remote.pause();
    local.pause();
    remote.handler(
        buffer -> {
          local.write(buffer);
          if (local.writeQueueFull()) {
            remote.pause();
            local.drainHandler(drained -> remote.resume());
          }
        });
    local.handler(
        buffer -> {
          remote.writeBinaryMessage(buffer);
          if (remote.writeQueueFull()) {
            local.pause();
            remote.drainHandler(drained -> local.resume());
          }
        });
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
    remote.resume();
    local.resume();
  }

  /** A project whose wrapper has a main workspace — and deliberately NO container. */
  private Workspace editorWorkspace(String slug) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.registerWrapper(repoId, "master", EditorHost.wrapperRepositoryName(slug));
    return workspaceService.createMainWorkspace(repoId, "master");
  }

  private static String host(String slug) {
    return "editor." + slug + ".dev.example.eu";
  }

  @Test
  public void aRequestReachesTheEditorThroughAStreamAskedForByName() throws Exception {
    Workspace main = editorWorkspace("tunnelled");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host("tunnelled"))
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .header("Authorization", "Bearer smuggled-in-by-the-caller")
        .get("/stable-abc/static/out/vs/workbench/workbench.js?v=2")
        .then()
        .statusCode(200)
        // The path arrives unrewritten, QUERY INCLUDED — the editor serves from `/`, which is the
        // whole reason it is a host — and the platform's identity does not arrive at all.
        .body(containsString("editor:/stable-abc/static/out/vs/workbench/workbench.js?v=2:null"));

    // The editor runs an untrusted checkout with a shell: a platform identity header it can read is
    // one it can echo at something that trusts the namespace, and a bearer would be a credential
    // moved inside the sandbox for nobody's benefit. The WHOLE namespace goes, not the two headers
    // this platform happens to assert today.
    assertNoPlatformIdentity(lastEditorHeaders.get());
    // What DOES travel is the public name, which is the only thing the editor could want.
    assertEquals(host("tunnelled"), lastEditorHeaders.get().get("X-Forwarded-Host"));

    assertEquals(1, asked.size(), "exactly one stream was asked for");
    assertEquals(
        StreamTarget.EDITOR,
        asked.getFirst().target(),
        "an editor request must name the editor; API here would serve the daemon's own 404s");
    assertTrue(
        asked.getFirst().path().startsWith(WorkspaceTunnels.STREAM_PATH_PREFIX),
        asked.getFirst().path());
  }

  @Test
  public void theTunnelBranchNeverAsksTheOrchestratorWhetherTheContainerIsUp() throws Exception {
    // No ensureContainer anywhere in this class, so FakeContainerRuntime knows of no container at
    // all — and the request is still served. A live control socket at the editor capability is
    // stronger evidence that the container is up than a status call is, and it is one round trip
    // less per request on a surface where a keystroke is a request.
    Workspace main = editorWorkspace("nocontainerread");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    given()
        .header("X-Forwarded-Host", host("nocontainerread"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("editor:/"));
  }

  @Test
  public void aWebSocketTraversesTheTunnelToTheEditor() throws Exception {
    Workspace main = editorWorkspace("tunnelsockets");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    // The case the byte pipe exists for: the extension host's socket crosses a proxy and a tunnel,
    // and neither end knows about either.
    CompletableFuture<String> reply = new CompletableFuture<>();
    WebSocket browser =
        await(
            wsClient.connect(
                new WebSocketConnectOptions()
                    .setHost("127.0.0.1")
                    .setPort(RestAssured.port)
                    .setURI("/stable-abc/vscode-remote-resource")
                    .addHeader("X-Forwarded-Host", host("tunnelsockets"))
                    .addHeader("X-Qits-User", "alice")
                    .addHeader("X-Qits-Roles", "qits:admin")
                    .addHeader("Authorization", "Bearer smuggled-in-by-the-caller")));
    browser.textMessageHandler(reply::complete);
    browser.writeTextMessage("hello");

    assertEquals("ws-editor:hello", reply.get(20, TimeUnit.SECONDS));

    // The strip has to be spelled a SECOND time for an upgrade: vertx-http-proxy installs no
    // interceptor chain on one, which is why this route carries the handshake by hand — and an
    // upgrade is precisely the request that carries a browser session, so a strip that only covered
    // the ordinary path would be dead on exactly the traffic it exists for.
    assertNoPlatformIdentity(lastEditorHeaders.get());
    assertEquals(host("tunnelsockets"), lastEditorHeaders.get().get("X-Forwarded-Host"));
    browser.close();
  }

  /**
   * The case that is about the pipe rather than about the feature.
   *
   * <p>{@code vertx-http-proxy} pipes an upgrade with bare {@code a.handler(b::write)} installs — no
   * {@code writeQueueFull}, no {@code pause}, no {@code drainHandler} — so an editor streaming a
   * build log into a terminal writes as fast as it likes and everything the browser has not read
   * accumulates on <em>this</em> process's heap. Park the browser, ask for 64 MiB, and a bounded
   * chain simply cannot get rid of it; resume, and every byte arrives in order.
   *
   * <p>64 MiB and not the direct arm's 32, because the flood has to be larger than every buffer
   * between the producer and the parked reader ADDED UP, and a tunnelled request crosses four
   * loopback connections rather than two — kernel socket buffers alone swallowed 16 MiB here with
   * every hop's flow control working exactly as it should.
   *
   * <p>Through the TUNNEL, because that is the transport an editor has. Every hop between the flood
   * and the parked reader is bounded — the fake editor's own producer, the fake daemon's pipe, {@code
   * DaemonStreamRoute}'s, and the route's — so the flood stalling is a statement about all of them
   * and its completing would be a statement about whichever one is not.
   */
  @Test
  public void aParkedBrowserStopsTheEditorRatherThanFillingThisProcessesHeap() throws Exception {
    Workspace main = editorWorkspace("tunnelbackpressure");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    reportEditor(main.id, EditorState.State.RUNNING);

    int frames = 1024;
    long expectedBytes = (long) frames * FLOOD_FRAME_BYTES;

    WebSocket browser =
        await(
            wsClient.connect(
                new WebSocketConnectOptions()
                    .setHost("127.0.0.1")
                    .setPort(RestAssured.port)
                    .setURI("/flooded")
                    .addHeader("X-Forwarded-Host", host("tunnelbackpressure"))
                    .addHeader("X-Qits-User", "alice")));

    CompletableFuture<Void> allReceived = new CompletableFuture<>();
    AtomicLong received = new AtomicLong();
    browser.binaryMessageHandler(
        buffer -> {
          if (received.addAndGet(buffer.length()) >= expectedBytes) {
            allReceived.complete(null);
          }
        });

    // Stop reading before asking for anything, so the very first frames have nowhere to go.
    browser.pause();
    browser.writeTextMessage(FLOOD + frames);

    try {
      floodWritten.get().get(3, TimeUnit.SECONDS);
      throw new AssertionError(
          "the editor handed over all "
              + expectedBytes
              + " bytes while the browser was not reading — something on the way is buffering them"
              + " (the parked browser had taken "
              + received.get()
              + ")");
    } catch (TimeoutException expected) {
      // Good: the editor is parked on its drain handler because a hop downstream stopped reading.
    }

    browser.resume();
    floodWritten.get().get(60, TimeUnit.SECONDS);
    allReceived.get(60, TimeUnit.SECONDS);
    assertEquals(expectedBytes, received.get(), "every byte, in order, once the reader comes back");
    browser.close();
  }

  @Test
  public void aDaemonBelowTheEditorCapabilityIsNeverAskedForAnEditorStream() throws Exception {
    Workspace main = editorWorkspace("olddaemon");
    connectFakeDaemon(main.id, WorkspaceTunnels.EDITOR_CAPABILITY_VERSION - 1);

    // That image carries no editor, and an absent target decodes there as API — so asking would be
    // served by the wrong listener, and the browser would read the daemon's 404s as an editor that
    // is broken rather than one that is absent. It gets the splash instead: no container was ever
    // ensured for this workspace, so there is nothing running to wait for either.
    given()
        .header("X-Forwarded-Host", host("olddaemon"))
        .header("X-Qits-User", "alice")
        .get("/")
        .then()
        .statusCode(200)
        .body(containsString("not running"));

    assertTrue(asked.isEmpty(), "a daemon that cannot serve an editor must not be asked for one");
  }

  /**
   * The gate is read twice, and this is the window between the two readings.
   *
   * <p>{@code originFor} checks the capability and hands back a listening port; {@code onAccepted}
   * mints the nonce and sends the {@code OpenStream}, one accepted connection later. A daemon that
   * reconnected on an older image in between would be sent a target its codec drops — and an absent
   * target decodes as {@code API}, so the stream would land on the daemon's own API port instead of
   * being refused. Bounded (that port wants a bearer this side does not send) and still wrong: the
   * browser would read someone else's 401s as its editor.
   *
   * <p>Dialled at the tunnel's own port rather than through the route, because the route would
   * re-resolve and never reach the window. A refused socket is the same connection error the nonce's
   * expiry gives, one round trip sooner.
   */
  @Test
  public void aDaemonThatDROPSToAnOlderImageIsNotAskedByASocketTheOldGateAdmitted()
      throws Exception {
    Workspace main = editorWorkspace("capabilityrace");
    connectFakeDaemon(main.id, DaemonProtocol.CAPABILITY_VERSION);
    int tunnelPort = tunnels.originFor(main.id, StreamTarget.EDITOR).orElseThrow().port();

    controlSocket.close();
    awaitNoDaemon(main.id);
    connectFakeDaemon(main.id, WorkspaceTunnels.EDITOR_CAPABILITY_VERSION - 1);

    NetSocket stale = await(netClient.connect(tunnelPort, "127.0.0.1"));
    CompletableFuture<Void> closed = new CompletableFuture<>();
    stale.closeHandler(v -> closed.complete(null));
    stale.write("GET / HTTP/1.1\r\nHost: editor\r\n\r\n");

    closed.get(20, TimeUnit.SECONDS);
    assertTrue(asked.isEmpty(), "the downgraded daemon must not be asked for an editor stream");
  }

  // --- helpers ------------------------------------------------------------------------------------

  /** Nothing of the platform's own identity may reach a container running an untrusted checkout. */
  private static void assertNoPlatformIdentity(MultiMap headers) {
    assertNotNull(headers, "the editor was never reached");
    for (String name : headers.names()) {
      String lower = name.toLowerCase(Locale.ROOT);
      assertFalse(
          lower.startsWith("x-qits-") || lower.equals("authorization"),
          "the editor must not see " + name);
    }
  }

  private void awaitNoDaemon(Long workspaceId) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      if (registry.lookup(workspaceId).isEmpty()) {
        return;
      }
      Thread.sleep(25);
    }
    throw new AssertionError("the daemon's control socket never went away");
  }

  private void awaitCapability(Long workspaceId, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Integer seen = registry.lookup(workspaceId).map(info -> info.capabilityVersion()).orElse(null);
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
