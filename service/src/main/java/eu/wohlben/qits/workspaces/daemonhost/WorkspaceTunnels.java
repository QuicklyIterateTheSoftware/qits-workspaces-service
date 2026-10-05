package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.control.WorkspaceDaemonInfo;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host end of the reverse tunnel: a loopback {@link NetServer} per workspace whose accepted TCP
 * connections are handed to that workspace's daemon, which dials back and pipes them to its own
 * {@code WorkspaceApi}.
 *
 * <h2>Why a loopback listener, of all things</h2>
 *
 * <p>Because the host has to speak HTTP over a connection it did not initiate, and Vert.x has no
 * API for "an {@code HttpClient} over a socket I supply" — {@code HttpProxy} offers {@code
 * origin(…)}, {@code originSelector(…)} and {@code originRequestProvider(…)}, all of which want a
 * real address. A loopback {@code NetServer} <em>is</em> a real address, so the proxy stays an
 * ordinary reverse proxy and the only thing that changed between reaching a container directly and
 * reaching it through a tunnel is which host:port it points at. That was the promise: everything
 * about the route survives, only the origin moves.
 *
 * <p>It also means the tunnel carries bytes rather than framed requests, which is what lets a
 * WebSocket upgrade traverse it unchanged. {@code vertx-http-proxy} already turns an upgraded
 * exchange into a raw byte pipe, so the two compose instead of fighting.
 *
 * <h2>One HttpClient per (workspace, target), and why it is not an optimisation to share</h2>
 *
 * <p>An ephemeral port is reused. Workspace A's tunnel closes, the OS later hands the same port to
 * workspace B's, and a pool keyed on {@code (host, port)} may still hold a live connection wired
 * through to <em>A's</em> daemon — which it would then hand to a request for B. That is a
 * cross-workspace read of someone else's working tree, arrived at without anything being
 * misconfigured. So each tunnel owns its client, created and closed with it, and every accepted
 * socket is closed explicitly at teardown ({@code NetServer.close()} closes the listening channel
 * only; accepted sockets survive it).
 *
 * <p><b>The same argument makes the TARGET part of the key.</b> A workspace's daemon has two
 * loopback listeners now — its {@code WorkspaceApi} and the supervised web editor — and a listener
 * is chosen once, when the socket is accepted, by the {@code OpenStream} this side sends. So one
 * listening port must mean one target for as long as it is bound: sharing a {@code NetServer}
 * between the two would make the pooled connection behind a keep-alive an editor stream or an API
 * stream depending on which request opened it first, which is the ephemeral-port hazard above
 * pointed inward. Two listeners, two servers, two clients, closed independently.
 *
 * <p><b>And for the same reason the SERVICE id is part of it too</b> (qits-625, qits-815). A
 * {@link StreamTarget#SERVICE} stream names one supervised dev server by its id, and two dev
 * servers of one workspace are two listeners inside its container exactly as the API and the
 * editor are: a pooled connection behind one service's port must never be handed to a request for
 * the other's. So the key is {@code (workspace, target, serviceId)}, with the id null for every
 * target that is not a service.
 */
@ApplicationScoped
public class WorkspaceTunnels {

  private static final Logger LOG = Logger.getLogger(WorkspaceTunnels.class);

  /** The dial-back path, and a cross-repo contract with the daemon's {@code DaemonStreamTunnel}. */
  static final String STREAM_PATH_PREFIX = "/workspaces/daemon/stream/";

  /**
   * An INSTANCE field, and it must stay one. A {@code static final SecureRandom} is initialized by
   * the class initializer, which native-image runs during the build — so the seeded instance lands
   * in the image heap and the build aborts outright:
   *
   * <pre>
   *   Detected an instance of Random/SplittableRandom class in the image heap.
   *   Object was reached by scanning root java.security.SecureRandom@…: NativePRNG embedded in
   *     eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels.mintNonce(...)
   * </pre>
   *
   * A CDI bean is constructed at runtime, so as a field of the bean it is created when the
   * application starts and never reaches the heap the builder writes. That is also why {@link
   * #mintNonce} is not static. GraalVM refuses this case rather than silently baking the seed in,
   * which is the one mercy here — a nonce generator with a build-time seed would be a credential
   * that is identical in every deployment of the same image.
   */
  private final SecureRandom random = new SecureRandom();

  @Inject Vertx vertx;

  @Inject WorkspaceDaemonRegistry registry;

  /** The row's placement and bound token subject, read once per tunnel (qits-812). */
  @Inject WorkspaceRepository workspaces;

  /**
   * The kill switch. A tunnel that misbehaves can be turned off without rolling back an image —
   * with the caveat that a daemon at the tunnel capability has already stopped listening on {@code
   * qits-net}, so switching this off makes its API unreachable rather than reachable the old way.
   * It is an escape hatch for a broken tunnel, not a supported topology.
   */
  @ConfigProperty(name = "qits.workspace.daemon-tunnel.enabled", defaultValue = "true")
  boolean enabled;

  /**
   * How long a minted nonce stays claimable. Generous for a docker network and short for a bearer
   * credential; the only thing that happens in the window is one WebSocket dial.
   */
  @ConfigProperty(name = "qits.workspace.daemon-tunnel.nonce-ttl-ms", defaultValue = "10000")
  long nonceTtlMs;

  /**
   * The first capability version whose daemon understands {@link StreamTarget#EDITOR} — and
   * therefore the version below which an {@code OpenStream} naming it must never be sent.
   *
   * <p><b>It is a constant here rather than in {@code DaemonProtocol}</b>, unlike {@link
   * DaemonProtocol#TUNNEL_CAPABILITY_VERSION} beside it, and that is a fact about this repository
   * rather than a judgement: {@code workspace-daemon-protocol/} is a source copy that must stay
   * byte-identical to qits-workspace-daemon's, so a constant only this side needs cannot be added
   * to it without becoming drift. Move it there the day the daemon repo declares one, and delete
   * this. What keeps the two honest meanwhile is {@code DaemonCodecTest}'s literal assertion on
   * {@code CAPABILITY_VERSION}, which fails on whichever copy falls behind.
   *
   * <p>Gating at all is the {@code OPEN_STREAM} asymmetry: this message travels qits→daemon, so an
   * older image simply never handles what it is sent. A daemon at 4 has no editor in it, and asking
   * it for one would park a socket until its nonce expired — a request that hangs and then 502s,
   * instead of the splash the state gate answers with.
   */
  static final int EDITOR_CAPABILITY_VERSION = 5;

  /**
   * The first capability version whose daemon understands {@link StreamTarget#SERVICE} — a stream
   * to one of its supervised dev servers, named by id and never by port (qits-625, qits-814). A
   * constant here for {@link #EDITOR_CAPABILITY_VERSION}'s reason, and gated for the same one: an
   * older daemon decodes the unknown target as absent, which means {@code API}, and would serve its
   * own API to a browser asking for a dev server.
   */
  public static final int SERVICE_CAPABILITY_VERSION = 6;

  private final ConcurrentHashMap<TunnelKey, Tunnel> tunnels = new ConcurrentHashMap<>();

  /** Minted-but-unclaimed nonces, across every workspace. Single-use by construction. */
  private final ConcurrentHashMap<String, Parked> pending = new ConcurrentHashMap<>();

  /**
   * What a tunnel is one of: a workspace and the listener inside its container — the target, and
   * for a {@link StreamTarget#SERVICE} which service. Every part is needed: see the class note on
   * why one listening port may not be shared between two listeners. {@code serviceId} is null for
   * every other target.
   */
  private record TunnelKey(Long workspaceId, StreamTarget target, String serviceId) {}

  /** One (workspace, target) tunnel: its listener, its client, and the sockets it has accepted. */
  private static final class Tunnel {
    private final TunnelKey key;
    private final NetServer server;
    private final HttpClient client;

    /**
     * The daemon connection this tunnel belongs to. A reconnect mints a new {@code connectedAt}, and
     * a tunnel whose daemon has been replaced must be rebuilt rather than reused — its parked
     * sockets would be waiting on a socket that is gone.
     */
    private final Instant connectedAt;

    /**
     * Who may dial back for this tunnel's streams, read when it was opened. A tunnel lives as long as
     * one daemon connection, and a RUNNER row's token only changes with its container — which
     * replaces the connection, and with it the tunnel.
     */
    private final DialBack dialBack;

    private final Set<NetSocket> accepted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Tunnel(
        TunnelKey key, NetServer server, HttpClient client, Instant connectedAt, DialBack dialBack) {
      this.key = key;
      this.server = server;
      this.client = client;
      this.connectedAt = connectedAt;
      this.dialBack = dialBack;
    }

    void close() {
      // Explicitly, and before the server: NetServer.close() closes only the listening channel, so
      // an accepted socket would otherwise outlive its tunnel and keep a pooled connection alive
      // against a port the OS is free to hand to another workspace.
      accepted.forEach(NetSocket::close);
      accepted.clear();
      client.close();
      server.close();
    }
  }

  /**
   * A TCP connection waiting for its daemon to dial back, and whatever the proxy has already
   * written to it.
   *
   * <p>The buffer is not an optimisation — it is the fix for a race that presents as the request
   * simply never being answered. The proxy writes its request bytes as soon as it connects, which
   * can be before the daemon has dialled back and before any handler exists to receive them.
   * Pausing the socket is not enough on its own to make that safe, so an interim handler collects
   * whatever arrives and {@link #pipe} replays it before wiring the two ends together.
   */
  record Parked(Long workspaceId, NetSocket socket, long timerId, Buffer early, DialBack dialBack) {}

  /**
   * What a dial-back for one workspace's stream must present beside its nonce (qits-625, qits-812).
   *
   * <p>A DIRECT row's dial-back comes over {@code qits-net} and the nonce is its whole
   * authentication, as it always was. A RUNNER row's comes through the edge with the workspace
   * token, so it must also carry a bearer this service validated whose {@code sub} is the row's
   * token subject ({@code boundSubject}); a RUNNER row with no subject admits nothing.
   */
  record DialBack(boolean runner, String boundSubject) {

    /** The DIRECT row's: the nonce alone. */
    static final DialBack NONCE_ONLY = new DialBack(false, null);
  }

  /**
   * Where to reach {@code workspaceRowId}'s daemon through the tunnel, or empty when that daemon
   * cannot be reached this way — no daemon connected, or one too old to serve a stream.
   *
   * <p>The caller gets the client as well as the port, and must use that client: it belongs to this
   * workspace's tunnel and is closed with it, which is what keeps a reused ephemeral port from
   * handing one workspace a pooled connection into another's container. It is <em>not</em> an
   * optimisation to reach for a shared one.
   *
   * <p>A live control socket is what proves the container is up here, which is why this asks the
   * registry rather than docker: it is both stronger evidence and one less round-trip per request.
   *
   * <p>Blocking (it awaits a bind on first use), so call it off the event loop.
   */
  public Optional<TunnelOrigin> originFor(Long workspaceRowId) {
    return originFor(workspaceRowId, StreamTarget.API);
  }

  /**
   * The same, for one named listener inside the container — {@link StreamTarget#API} or the
   * supervised web editor.
   *
   * <p>The tunnel is per {@code (workspace, target)} and never shared between two targets; the class
   * note says what sharing one would cost. The <b>capability gate is per target too</b>: a stream to
   * the API needs {@link DaemonProtocol#TUNNEL_CAPABILITY_VERSION}, and one to the editor needs
   * {@link #EDITOR_CAPABILITY_VERSION}, because an older daemon decodes an absent target as {@code
   * API} and would serve the wrong listener rather than refusing. Asking a daemon that cannot answer
   * costs a parked socket and a nonce expiry — a hang that turns into a 502 — where the caller's own
   * state gate can answer at once.
   */
  public Optional<TunnelOrigin> originFor(Long workspaceRowId, StreamTarget target) {
    return originFor(workspaceRowId, target, null);
  }

  /**
   * The same, for a {@link StreamTarget#SERVICE}: the dev server {@code serviceId} declares, which
   * the daemon resolves to its own supervised web-view port (qits-625, qits-815). The id is part of
   * the tunnel's key — two services, two listeners — and is ignored for every other target. A
   * SERVICE request with no id names nothing and resolves to nothing.
   */
  public Optional<TunnelOrigin> originFor(
      Long workspaceRowId, StreamTarget target, String serviceId) {
    if (!enabled || workspaceRowId == null) {
      return Optional.empty();
    }
    StreamTarget resolved = target == null ? StreamTarget.API : target;
    String id = resolved == StreamTarget.SERVICE ? serviceId : null;
    if (resolved == StreamTarget.SERVICE && (id == null || id.isBlank())) {
      return Optional.empty();
    }
    TunnelKey key = new TunnelKey(workspaceRowId, resolved, id);
    WorkspaceDaemonInfo.Info info = registry.lookup(workspaceRowId).orElse(null);
    if (info == null || info.capabilityVersion() < capabilityFor(key.target())) {
      // No daemon, or one too old for this target: for the API, one that still listens on qits-net
      // and knows nothing about OpenStream — the caller falls back to the direct container address,
      // which is exactly where such a daemon is listening. For the editor there is no fallback to
      // have: that image carries no editor at all.
      closeTunnel(key);
      return Optional.empty();
    }
    Tunnel existing = tunnels.get(key);
    if (existing != null && existing.connectedAt.equals(info.connectedAt())) {
      return Optional.of(originOf(existing));
    }
    closeTunnel(key);
    try {
      return Optional.of(originOf(openTunnel(key, info.connectedAt())));
    } catch (RuntimeException e) {
      LOG.warnf(e, "could not open a %s daemon tunnel for workspace %s", key.target(), workspaceRowId);
      return Optional.empty();
    }
  }

  /**
   * The capability version {@code workspaceRowId}'s connected daemon announced, or empty when none
   * is connected. What lets a caller tell "no daemon" from "a daemon too old for this target" —
   * {@link #originFor} answers both with an empty origin, and the two deserve different answers.
   */
  public OptionalInt daemonCapability(Long workspaceRowId) {
    if (workspaceRowId == null) {
      return OptionalInt.empty();
    }
    return registry
        .lookup(workspaceRowId)
        .map(info -> OptionalInt.of(info.capabilityVersion()))
        .orElse(OptionalInt.empty());
  }

  /** The lowest capability version whose daemon can serve a stream to {@code target}. */
  public static int capabilityFor(StreamTarget target) {
    return switch (target) {
      case API -> DaemonProtocol.TUNNEL_CAPABILITY_VERSION;
      case EDITOR -> EDITOR_CAPABILITY_VERSION;
      case SERVICE -> SERVICE_CAPABILITY_VERSION;
    };
  }

  private static TunnelOrigin originOf(Tunnel tunnel) {
    return new TunnelOrigin(tunnel.client, tunnel.server.actualPort());
  }

  /**
   * One workspace's tunnel entrance: the loopback port to target, and the client that must be used
   * to target it.
   */
  public record TunnelOrigin(HttpClient client, int port) {}

  private Tunnel openTunnel(TunnelKey key, Instant connectedAt) {
    // 127.0.0.1 is a literal and not a config key on purpose: a configurable bind address here
    // would be an SSRF footgun with no caller asking for it.
    NetServer server = vertx.createNetServer();
    server.connectHandler(socket -> onAccepted(key, socket));
    NetServer bound = await(server.listen(0, "127.0.0.1"));
    HttpClient client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    Tunnel tunnel = new Tunnel(key, bound, client, connectedAt, dialBackFor(key.workspaceId()));
    tunnels.put(key, tunnel);
    LOG.debugf(
        "daemon %s%s tunnel for workspace %s listening on 127.0.0.1:%s",
        key.target(),
        key.serviceId() == null ? "" : " " + key.serviceId(),
        key.workspaceId(),
        Integer.valueOf(bound.actualPort()));
    return tunnel;
  }

  /**
   * Who may dial back for {@code workspaceRowId}'s streams: the row's placement and, on a RUNNER
   * row, its token subject. Read off the row on the blocking path that opens a tunnel, never on the
   * event loop that mints a nonce. A row that cannot be read is treated as DIRECT, which is what
   * every row was before placement — no RUNNER row reaches here without a row to read.
   */
  private DialBack dialBackFor(Long workspaceRowId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaces
                    .findActiveById(workspaceRowId)
                    .filter(w -> w.placement == WorkspacePlacement.RUNNER)
                    .map(w -> new DialBack(true, w.commissionedTokenSubject))
                    .orElse(DialBack.NONCE_ONLY));
  }

  /**
   * One accepted connection: park it, ask its daemon to come and get it.
   *
   * <p>Runs on an event loop, so the {@code OpenStream} is sent without awaiting — every other send
   * site in {@link WorkspaceDaemonRegistry} runs on a virtual or worker thread and uses the
   * blocking form, which Mutiny's blocking guard would reject here.
   *
   * <p>The nonce is registered <em>before</em> the message goes out. That ordering is the only one
   * that works: a dial-back can arrive before the send's own callback does.
   *
   * <p><b>The capability is re-read here, and not only in {@link #originFor}.</b> That gate runs
   * once per resolution and this handler runs once per accepted connection, so a daemon that
   * reconnected on an older image in between would be asked for a target it cannot decode — and an
   * absent target decodes as {@code API}, which means the stream lands on the daemon's own API port
   * rather than being refused. Bounded (that port wants a bearer this side does not send), but
   * wrong, and wrong in the direction where a browser reads someone else's 401s as its editor. The
   * refusal is a closed socket, which is the same connection error the nonce's own expiry gives.
   */
  private void onAccepted(TunnelKey key, NetSocket socket) {
    Long workspaceId = key.workspaceId();
    Tunnel tunnel = tunnels.get(key);
    if (tunnel == null) {
      socket.close();
      return;
    }
    WorkspaceDaemonInfo.Info info = registry.lookup(workspaceId).orElse(null);
    if (info == null || info.capabilityVersion() < capabilityFor(key.target())) {
      LOG.debugf(
          "refusing a %s stream for workspace %s: its daemon no longer serves that target",
          key.target(), workspaceId);
      socket.close();
      return;
    }
    tunnel.accepted.add(socket);
    socket.closeHandler(v -> tunnel.accepted.remove(socket));
    // Collect whatever the proxy writes before the far end exists; DaemonStreamRoute replays it.
    Buffer early = Buffer.buffer();
    socket.handler(early::appendBuffer);

    String nonce = mintNonce();
    long timerId =
        vertx.setTimer(
            nonceTtlMs,
            id -> {
              Parked expired = pending.remove(nonce);
              if (expired != null) {
                // The daemon never came. Closing is what turns this into a connection error at the
                // proxy rather than a request that hangs until some other timeout notices. It is
                // also the one refusal the daemon still has left — an EDITOR stream into a
                // container with no editor is dropped there and never dialled back.
                LOG.debugf(
                    "daemon %s tunnel stream for workspace %s expired unclaimed",
                    key.target(), workspaceId);
                expired.socket().close();
              }
            });
    // The tunnel's dial-back rule rides the nonce, so the route checks it without a row read.
    pending.put(nonce, new Parked(workspaceId, socket, timerId, early, tunnel.dialBack));
    registry.requestStream(
        workspaceId, nonce, STREAM_PATH_PREFIX + nonce, key.target(), key.serviceId());
  }

  /**
   * Claim a nonce, once. The atomic {@code remove} is what makes single-use structural rather than
   * a rule someone has to remember — a replayed nonce finds nothing.
   */
  Optional<Parked> claim(String nonce) {
    Parked parked = nonce == null ? null : pending.remove(nonce);
    if (parked != null) {
      vertx.cancelTimer(parked.timerId());
    }
    return Optional.ofNullable(parked);
  }

  /**
   * A daemon's control socket went away: drop its <em>pending</em> nonces and nothing else.
   *
   * <p>Live tunnels deliberately survive. Each stream is an independent TCP connection, so a control
   * socket bouncing through a reconnect leaves an open terminal open — which is the whole reason
   * these calls do not ride the control socket in the first place, and would be quietly undone by
   * tearing tunnels down here.
   */
  void onDaemonGone(Long workspaceId) {
    pending.forEach(
        (nonce, parked) -> {
          if (parked.workspaceId().equals(workspaceId) && pending.remove(nonce, parked)) {
            vertx.cancelTimer(parked.timerId());
            parked.socket().close();
          }
        });
  }

  private void closeTunnel(TunnelKey key) {
    Tunnel gone = tunnels.remove(key);
    if (gone != null) {
      gone.close();
    }
  }

  /**
   * 32 bytes of {@link SecureRandom}, base64url. Not a {@code UUID}: this is a bearer credential,
   * and this codebase spells correlation ids as UUIDs — using one here would make the wrong thing
   * look right to the next reader.
   *
   * <p>Not static, and not by accident — see {@link #random}.
   */
  private String mintNonce() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static <T> T await(io.vertx.core.Future<T> future) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted opening a daemon tunnel", e);
    } catch (Exception e) {
      throw new IllegalStateException("could not open a daemon tunnel", e);
    }
  }

  @PreDestroy
  void closeAll() {
    tunnels.keySet().forEach(this::closeTunnel);
    pending.values().forEach(parked -> parked.socket().close());
    pending.clear();
  }
}
