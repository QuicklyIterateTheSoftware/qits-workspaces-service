package eu.wohlben.qits.workspaces.daemonhost;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

/**
 * Where a daemon's dial-back lands: {@code /workspaces/daemon/stream/{nonce}}. The nonce names a TCP
 * connection {@link WorkspaceTunnels} parked when the proxy opened one, and this route marries the
 * two into a byte pipe.
 *
 * <h2>A raw Vert.x route, not websockets-next</h2>
 *
 * <p>{@code io.quarkus.websockets.next.Connection} exposes {@code sendBinary(Buffer) → Uni} and
 * nothing else — no {@code writeQueueFull}, no {@code drainHandler}. A byte tunnel without a
 * backpressure signal is an unbounded heap buffer, so the framework's own socket abstraction is the
 * one thing this cannot be built on. {@code request.toWebSocket()} yields a {@link ServerWebSocket},
 * which is a proper {@code WriteStream} and makes both ends of the pipe the same types.
 * {@code ServiceProxyRoute} and {@code CaptureCorsRoute} are the precedent for a raw route here.
 *
 * <p>It shares a prefix with {@code DaemonControlSocket}'s {@code /workspaces/daemon/{id}} and does
 * not collide: {@code {id}} matches exactly one segment, so no daemon can be named {@code stream}.
 * This route is registered ahead of the framework's own so the two never race for the path.
 *
 * <h2>The nonce is the whole authentication</h2>
 *
 * <p>Deliberately, and it is not the shared API token by another name. The control socket names its
 * caller with a path parameter, so anything on {@code qits-net} can claim to be any workspace's
 * daemon; a dial-back that named its own workspace would reproduce that in a second place. This one
 * names nothing: it is host-minted, single-use (the claim is an atomic map removal), short-lived,
 * and bound to the workspace it was sent to. An unknown or already-claimed nonce gets a bare 404
 * that says nothing about which of the two it was.
 *
 * <h2>A RUNNER row's dial-back also carries its token (qits-625, qits-812)</h2>
 *
 * <p>A runner-placed daemon dials back through the edge, which refuses an anonymous upgrade, so it
 * sends {@code Authorization: Bearer $QITS_TOKEN} and the edge forwards a JWT. For a nonce parked
 * for a RUNNER row ({@link WorkspaceTunnels.DialBack}) the nonce is then the second factor: the
 * request must carry a bearer this service's OIDC tenant validated, and its {@code sub} must be the
 * row's token subject. Anything else — no bearer, another subject, an identity that is not a
 * validated token — gets the same bare 404 an unknown nonce gets, and the parked socket is dropped.
 * A DIRECT row's dial-back is unchanged: the nonce alone.
 */
@ApplicationScoped
public class DaemonStreamRoute {

  private static final Logger LOG = Logger.getLogger(DaemonStreamRoute.class);

  /**
   * Ahead of websockets-next' own registration, so the prefix is unambiguous by ordering as well as
   * by segment count.
   */
  private static final int ROUTE_ORDER = 100;

  @Inject WorkspaceTunnels tunnels;

  void init(@Observes Router router) {
    router
        .route(WorkspaceTunnels.STREAM_PATH_PREFIX + "*")
        .order(ROUTE_ORDER)
        .handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character
    // short of the prefix itself — where the substring below would overflow. No segment means no
    // nonce, and it gets the same bare 404 an unknown nonce does.
    String path = rc.request().path();
    if (path.length() < WorkspaceTunnels.STREAM_PATH_PREFIX.length()) {
      rc.response().setStatusCode(404).end();
      return;
    }
    String nonce = path.substring(WorkspaceTunnels.STREAM_PATH_PREFIX.length());
    WorkspaceTunnels.Parked parked = tunnels.claim(nonce).orElse(null);
    if (parked == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    if (!admits(parked.dialBack(), identityOf(rc))) {
      LOG.debugf(
          "refused a dial-back for runner workspace %s: not its token's bearer",
          parked.workspaceId());
      parked.socket().close();
      rc.response().setStatusCode(404).end();
      return;
    }
    rc.request()
        .toWebSocket()
        .onFailure(
            t -> {
              LOG.debugf("daemon stream upgrade failed: %s", String.valueOf(t));
              parked.socket().close();
            })
        .onSuccess(socket -> pipe(socket, parked));
  }

  /**
   * Whether a dial-back presenting {@code identity} may take a stream parked under {@code dialBack}:
   * always for a DIRECT row's (the nonce is the whole authentication), and for a RUNNER row's only
   * when {@code identity} is a validated token whose {@code sub} is the row's bound subject.
   */
  static boolean admits(WorkspaceTunnels.DialBack dialBack, SecurityIdentity identity) {
    if (dialBack == null || !dialBack.runner()) {
      return true;
    }
    if (dialBack.boundSubject() == null
        || identity == null
        || identity.isAnonymous()
        || !(identity.getPrincipal() instanceof JsonWebToken)) {
      return false;
    }
    return dialBack.boundSubject().equals(DaemonAgentBindingCheck.subjectOf(identity));
  }

  /** The identity the request authenticated as, or null when none was established. */
  private static SecurityIdentity identityOf(RoutingContext rc) {
    return rc.user() instanceof QuarkusHttpUser user ? user.getSecurityIdentity() : null;
  }

  /**
   * Pump bytes both ways. The mirror image of the daemon's own pump, and it has to be — the same
   * three constraints apply from this end.
   *
   * <p><b>{@code writeBinaryMessage}, never {@code write} or {@code pipeTo}.</b> A WebSocket's
   * {@code write(Buffer)} emits one frame of whatever length it is handed, and a {@code NetSocket}
   * read chunk sits at exactly Netty's 65536 default maximum frame size — so a large response
   * would trip the peer's limit and drop the socket. And {@code handler}, never {@code
   * binaryMessageHandler}: the latter aggregates and enforces a maximum message size, which a byte
   * stream has no business having.
   *
   * <p>The bytes the proxy wrote before the daemon dialled back are replayed first, in order. They
   * are collected by an interim handler at accept time rather than left to a paused socket, because
   * the request line can genuinely arrive before there is anywhere to put it — and losing it
   * presents as a request that is never answered rather than as anything that looks like a bug.
   */
  private static void pipe(ServerWebSocket remote, WorkspaceTunnels.Parked parked) {
    NetSocket local = parked.socket();
    // Whatever the proxy wrote before the daemon arrived, first and in order. Without this the
    // request line itself can be lost, and the symptom is a request that is simply never answered.
    if (parked.early().length() > 0) {
      remote.writeBinaryMessage(parked.early());
    }
    remote.handler(
        buffer -> {
          local.write(buffer);
          if (local.writeQueueFull()) {
            remote.pause();
            local.drainHandler(v -> remote.resume());
          }
        });
    local.handler(
        buffer -> {
          remote.writeBinaryMessage(buffer);
          if (remote.writeQueueFull()) {
            local.pause();
            remote.drainHandler(v -> local.resume());
          }
        });
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.exceptionHandler(t -> local.close());
    local.exceptionHandler(t -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
  }
}
