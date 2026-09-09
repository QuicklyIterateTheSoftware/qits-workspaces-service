package eu.wohlben.qits.workspaces.stories.support;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.workspacedaemon.protocol.Ack;
import eu.wohlben.qits.workspacedaemon.protocol.CommandChunk;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonCodec;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.Heartbeat;
import eu.wohlben.qits.workspacedaemon.protocol.Hello;
import eu.wohlben.qits.workspacedaemon.protocol.OpenStream;
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The workspace container's own {@code workspace-daemon}, as a story plays it — and the tap for the
 * one plane the framework ships no tap for.
 *
 * <h2>Why this is a real client and not a fixture</h2>
 *
 * <p>It is the JDK's own WebSocket dialling the real endpoint with the real {@code Authorization}
 * header and framing the real protocol through the vendored {@link DaemonCodec}; the host cannot
 * tell it from a container. That matters because the provision the host is waiting on <b>only</b>
 * completes when a daemon dials home and reports: {@code WorkspaceDaemonRegistry.awaitProvision}
 * waits for a live connection and then for a terminal {@code Provisioned} frame, and with neither it
 * fails the launch with "no workspace-daemon dialed home". A story that stopped at the container
 * request would be a story about half a provision.
 *
 * <h2>The credential is a bearer, and the commissioned pair is what a story reads instead</h2>
 *
 * <p>{@code DaemonControlSocket} is {@code @RolesAllowed("qits:system")}, enforced at the HTTP
 * <b>upgrade</b>, so the dial carries an idp-minted bearer for this service's audience. A real
 * container gets one by exchanging the pair the host commissioned for it — {@code
 * QITS_COMMISSIONED_CLIENT_ID}/{@code …_SECRET}, injected into its environment — and that exchange
 * happens at qits-platform-idp, which is a stub here: it would answer an opaque string, and an
 * opaque string is not a JWT the gate can validate. So the story mints the bearer directly and reads
 * the commissioned pair out of the workload spec qits-containers was handed, which is the only place
 * it exists and exactly where a container would find it. The two halves are proved separately
 * rather than pretended to be one.
 *
 * <h2>The tap, and why it is written here</h2>
 *
 * <p>The framework ships a RestAssured tap and nothing for a socket, so this plane is instrumented
 * with {@link NetworkCapture#observe} at the call sites — and every one of those calls is
 * synchronous on the <b>story thread</b>, which is the one place the framework's rule allows the
 * actor to be read. A handler on the client's own reader thread would inherit whatever actor is
 * current when the frame lands, which is a different story's.
 *
 * <p>Two kinds, and the split is the vocabulary's own:
 *
 * <ul>
 *   <li><b>{@code socket}</b> — the dial. One edge for the connection the container holds open.
 *       Direction is who dialled, and the whole design of this plane is that the container dials
 *       <b>out</b>: qits-workspaces never dials in, which is why a workspace container needs no
 *       inbound route and no address of its own.
 *   <li><b>{@code event}</b> — one per frame pushed over that connection, in whichever direction it
 *       was pushed. {@code hello}, {@code heartbeat} and {@code provisioned} are the container's;
 *       {@code ack} is the host's, and it is the reply to the {@code Hello} rather than a greeting
 *       of its own.
 * </ul>
 *
 * <p><b>An observed label is not scrubbed at drain</b> — only a source-supplied one is — so the
 * socket edge spells {@code /workspaces/daemon/{id}} by hand. The row id in the real path is a bare
 * number, which {@code Labels} would rewrite; writing the raw id here would move the {@code
 * networkHash} on every run, and nothing would say so.
 */
public final class StoryDaemon implements AutoCloseable {

  /** How long a frame the host owes may take to arrive. Generous: the host is a launched process. */
  private static final Duration SOON = Duration.ofSeconds(30);

  /** The one mapper this class needs. {@link DaemonCodec} is framework-free and hands over a Map. */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final WebSocket socket;

  private final BlockingQueue<DaemonMessage> inbound;

  /** Where the host answers, so an {@code OpenStream} can be dialled back. */
  private final String baseUrl;

  // --- the reverse tunnel, which a container really does serve -------------------------------------
  //
  // The host cannot dial a container: a daemon binds loopback and qits-workspaces reaches it by
  // ASKING it to dial back (OpenStream → a WebSocket to /workspaces/daemon/stream/<nonce>, piped
  // to its own API). Everything below is that, in the story's own words — the same three moves
  // DaemonStreamTunnel makes in the real daemon and DaemonStreamRouteTest makes in the unit suite.
  // Without it a story's container is reachable for frames and unreachable for requests, which is
  // half a container.

  private Vertx vertx;
  private HttpServer api;
  private WebSocketClient wsClient;
  private NetClient netClient;

  /** How many times the host has read {@code /agents/available} through the tunnel. */
  private final AtomicInteger capabilityReads = new AtomicInteger();

  /**
   * What that route answers, given the attempt number (1 for the first read). {@code null} means a
   * 404 — a daemon that does not serve the route at all — which is what every container in this
   * catalogue answers unless its story says otherwise, and is deliberately the cheapest honest
   * answer: the host's relay reads it once, records nothing and asks no more.
   */
  private volatile java.util.function.IntFunction<String> capabilityAnswers = attempt -> null;

  private StoryDaemon(WebSocket socket, BlockingQueue<DaemonMessage> inbound, String baseUrl) {
    this.socket = socket;
    this.inbound = inbound;
    this.baseUrl = baseUrl;
  }

  /**
   * Dial the control socket for one workspace, and record the connection.
   *
   * <p>The upgrade completing IS admission here, unlike some sibling planes: the role is checked at
   * the upgrade, so a refused dial fails the handshake rather than closing afterwards. That is what
   * makes the refusal story's {@code assertThrows} a claim about the gate.
   */
  public static StoryDaemon dial(String baseUrl, long workspaceId, String bearer) throws Exception {
    BlockingQueue<DaemonMessage> inbound = new LinkedBlockingQueue<>();
    // The frame handler is installed before the socket exists, so it is handed the daemon through a
    // holder: an OpenStream must be served the moment it arrives, and the host asks for one as soon
    // as anything on its side wants to reach this container.
    java.util.concurrent.atomic.AtomicReference<StoryDaemon> self =
        new java.util.concurrent.atomic.AtomicReference<>();
    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Authorization", "Bearer " + bearer)
            .connectTimeout(SOON)
            .buildAsync(
                URI.create(endpoint(baseUrl, workspaceId)),
                new Listener(
                    inbound,
                    message -> {
                      StoryDaemon daemon = self.get();
                      if (message instanceof OpenStream open && daemon != null) {
                        daemon.serveStream(open);
                      }
                    }))
            .get(SOON.toSeconds(), TimeUnit.SECONDS);
    StoryDaemon daemon = new StoryDaemon(socket, inbound, baseUrl);
    daemon.startContainerApi();
    self.set(daemon);
    pushed(
        StoryIdentities.DAEMON,
        StoryTarget.SERVICE,
        NetworkEdge.SOCKET,
        "CONNECT " + StoryTarget.DAEMON_LABEL_PATH);
    return daemon;
  }

  /**
   * Dial without recording anything — for the story about a dial that must not be admitted. A
   * refused upgrade is not a connection, so there is no edge to draw, and drawing one would be the
   * story contradicting itself.
   */
  public static void dialRefused(String baseUrl, long workspaceId, String bearer) throws Exception {
    var builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(SOON);
    if (bearer != null) {
      builder = builder.header("Authorization", "Bearer " + bearer);
    }
    builder
        .buildAsync(
            URI.create(endpoint(baseUrl, workspaceId)),
            new Listener(new LinkedBlockingQueue<>(), message -> {}))
        .get(SOON.toSeconds(), TimeUnit.SECONDS);
  }

  /** {@code ws://…/workspaces/daemon/<rowId>} — the address a container is handed at launch. */
  public static String endpoint(String baseUrl, long workspaceId) {
    return baseUrl.replaceFirst("^http", "ws") + "/workspaces/daemon/" + workspaceId;
  }

  /** {@code Hello} — the daemon naming itself, its repository and the branch it checked out. */
  public void hello(String label, String repoId, String branch) {
    send(
        new Hello(
            label,
            repoId,
            branch,
            StoryTarget.MAIN,
            DaemonProtocol.CAPABILITY_VERSION,
            "story-daemon",
            null));
    fromDaemon("hello");
  }

  /** The host's answer. The registry keeps the label and the capability version off the Hello. */
  public Ack awaitAck() throws Exception {
    Ack ack = assertInstanceOf(Ack.class, next(), "the host must acknowledge a Hello");
    fromHost("ack");
    return ack;
  }

  /** A liveness frame. The daemon sends them unprompted; the host owes nothing back. */
  public void heartbeat(String label) {
    send(new Heartbeat(label));
    fromDaemon("heartbeat");
  }

  /** One line of the provision's output, on the correlation id the host routes to its clone step. */
  public void provisionOutput(String text) {
    send(new CommandChunk(DaemonProtocol.PROVISION_CORRELATION_ID, Stream.STDOUT, text));
    fromDaemon("stepChunk provision");
  }

  /**
   * The terminal frame the host's provision is waiting on: the checkout is populated, at this head.
   *
   * <p>The edge is recorded once, on the first send. A frame that beats the host's awaiter is
   * dropped by design ({@code completeProvision} finds no slot), so the story repeats it until the
   * provision is observably over — and a repeat is the same {@code (kind, from, to, label)}, which
   * is one arrow either way.
   */
  public void provisioned(String label, String head) {
    send(new Provisioned(label, head));
    fromDaemon("provisioned");
  }

  public boolean isOpen() {
    return !socket.isOutputClosed();
  }

  @Override
  public void close() {
    try {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "story over").get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      socket.abort();
    }
    // The container's own listeners go with it. A tunnel that outlived the story would leave the
    // host holding a NetServer wired to a Vert.x that is about to close.
    closeQuietly(api == null ? null : api::close);
    closeQuietly(wsClient == null ? null : wsClient::close);
    closeQuietly(netClient == null ? null : netClient::close);
    closeQuietly(vertx == null ? null : vertx::close);
  }

  private static void closeQuietly(Runnable close) {
    if (close == null) {
      return;
    }
    try {
      close.run();
    } catch (RuntimeException ignored) {
      // A story that is over cannot be failed by a listener that was already gone.
    }
  }

  // --- what the container answers when the host reaches into it -----------------------------------

  /**
   * Serve {@code GET /agents/available} from {@code answers}, which is handed the attempt number
   * (1 for the first read); {@code null} from it is a 404. The container's API is already bound —
   * {@link #dial} binds it — so this only says what it answers.
   *
   * <p><b>Called before {@code Hello}</b>, because {@code Hello} is exactly when the host starts
   * asking: {@code WorkspaceCapabilityRelay} fires on that frame and reads through the tunnel.
   */
  public void serveHarnessCapabilities(java.util.function.IntFunction<String> answers) {
    capabilityAnswers = answers;
  }

  /** Bind this container's loopback API, which the tunnel above is the only way into. */
  private void startContainerApi() {
    vertx = Vertx.vertx();
    wsClient = vertx.createWebSocketClient();
    netClient = vertx.createNetClient();
    api = vertx.createHttpServer();
    api.requestHandler(
        request -> {
          if (!request.uri().endsWith("/agents/available")) {
            request.response().setStatusCode(404).end("{\"message\":\"no such route\"}");
            return;
          }
          String body = capabilityAnswers.apply(capabilityReads.incrementAndGet());
          if (body == null) {
            request.response().setStatusCode(404).end("{\"message\":\"no such route\"}");
            return;
          }
          request.response().putHeader("Content-Type", "application/json").end(body);
        });
    try {
      api.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("the story container's API would not bind", e);
    }
  }

  /** How many times the host has read {@code /agents/available} from this container. */
  public int capabilityReads() {
    return capabilityReads.get();
  }

  /**
   * Serve one {@code OpenStream}: dial the host's stream route and pipe it to this container's own
   * API, exactly as {@code DaemonStreamTunnel} does.
   *
   * <p>Both ends are paused until the handlers exist — the host writes the moment its upgrade
   * completes, and a request line read before there is a handler to take it is a request that never
   * answers.
   */
  private void serveStream(OpenStream open) {
    if (api == null) {
      return; // this story's container serves nothing; the host's read fails, which is honest
    }
    URI host = URI.create(baseUrl);
    netClient
        .connect(api.actualPort(), "127.0.0.1")
        .onSuccess(
            local ->
                wsClient
                    .connect(host.getPort(), host.getHost(), open.path())
                    .onSuccess(remote -> pipe(remote, local))
                    .onFailure(failure -> local.close()));
  }

  private static void pipe(io.vertx.core.http.WebSocket remote, NetSocket local) {
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

  // --- the wire ---------------------------------------------------------------------------------

  private void send(DaemonMessage message) {
    try {
      socket.sendText(JSON.writeValueAsString(DaemonCodec.encode(message)), true).get(5, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException("could not send " + message, e);
    }
  }

  private DaemonMessage next() throws Exception {
    DaemonMessage message = inbound.poll(SOON.toMillis(), TimeUnit.MILLISECONDS);
    assertNotNull(message, "the host sent no frame within " + SOON);
    return message;
  }

  /** A frame this container pushed; the actor is read here, on the story thread. */
  private static void fromDaemon(String label) {
    pushed(StoryIdentities.DAEMON, StoryTarget.SERVICE, NetworkEdge.EVENT, label);
  }

  /** A frame the host pushed back down the connection the container opened. */
  private static void fromHost(String label) {
    pushed(StoryTarget.SERVICE, StoryIdentities.DAEMON, NetworkEdge.EVENT, label);
  }

  private static void pushed(String from, String to, String kind, String label) {
    NetworkCapture.observe(kind, from, to, label);
  }

  /**
   * Text frames in, decoded and queued. Nothing here records an edge — see the class javadoc.
   *
   * <p>The parts are <b>accumulated</b>: the JDK's WebSocket delivers a text message in as many
   * pieces as it likes and only the last one carries {@code last}, so decoding each piece as it
   * arrives would turn one frame into several undecodable ones the moment a message grew.
   */
  private static final class Listener implements WebSocket.Listener {

    private final BlockingQueue<DaemonMessage> inbound;

    /** Frames the container must ACT on rather than queue — today, {@code OpenStream}. */
    private final Consumer<DaemonMessage> handler;

    private final StringBuilder parts = new StringBuilder();

    private Listener(BlockingQueue<DaemonMessage> inbound, Consumer<DaemonMessage> handler) {
      this.inbound = inbound;
      this.handler = handler;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      parts.append(data);
      if (last) {
        String whole = parts.toString();
        parts.setLength(0);
        try {
          DaemonMessage message = DaemonCodec.decode(JSON.readValue(whole, Map.class));
          handler.accept(message);
          inbound.add(message);
        } catch (Exception undecodable) {
          // A frame this story does not model is not a failure of the story; the assertions name
          // what was expected.
        }
      }
      webSocket.request(1);
      return null;
    }
  }
}
