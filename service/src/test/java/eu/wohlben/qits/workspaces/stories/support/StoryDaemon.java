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
import eu.wohlben.qits.workspacedaemon.protocol.Provisioned;
import eu.wohlben.qits.workspacedaemon.protocol.Stream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
 * <b>upgrade</b>, so the dial carries an idp-minted bearer for the platform audience. A real
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

  private StoryDaemon(WebSocket socket, BlockingQueue<DaemonMessage> inbound) {
    this.socket = socket;
    this.inbound = inbound;
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
    WebSocket socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .header("Authorization", "Bearer " + bearer)
            .connectTimeout(SOON)
            .buildAsync(URI.create(endpoint(baseUrl, workspaceId)), new Listener(inbound))
            .get(SOON.toSeconds(), TimeUnit.SECONDS);
    pushed(
        StoryIdentities.DAEMON,
        StoryTarget.SERVICE,
        NetworkEdge.SOCKET,
        "CONNECT " + StoryTarget.DAEMON_LABEL_PATH);
    return new StoryDaemon(socket, inbound);
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
            new Listener(new LinkedBlockingQueue<>()))
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

    private final StringBuilder parts = new StringBuilder();

    private Listener(BlockingQueue<DaemonMessage> inbound) {
      this.inbound = inbound;
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
          inbound.add(DaemonCodec.decode(JSON.readValue(whole, Map.class)));
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
