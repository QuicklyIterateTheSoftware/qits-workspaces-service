package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The forwarding half of the daemon proxy, shared by {@link ContainerProxyRoute} and {@link
 * AgentProxyRoute}: find a workspace's daemon ({@link #resolve}), answer its absence, or carry the
 * request to it ({@link #route}) under a daemon path the caller chooses.
 *
 * <p>{@link ContainerProxyRoute} passes the inbound URI unchanged — that route rewrites no path,
 * and its javadoc says why. {@link AgentProxyRoute} passes the path it mapped the agent's route to,
 * which is always under {@link ContainerProxyPath#base}, the daemon's own address. On the ordinary
 * path the outgoing URI is set on the proxied request (only when it differs from the inbound one);
 * on an upgrade the hand-rolled request line carries it.
 *
 * <p><b>An upgrade takes a different road, and that is deliberate.</b> {@code WS
 * /terminal/commands/{id}} and {@code WS /chat/commands/{id}} are proxied by {@link #proxyUpgrade}
 * rather than by {@code vertx-http-proxy}, because the library's upgrade path skips its own
 * interceptor chain <em>and</em> pipes the socket with no flow control whatsoever. That method
 * carries the measurement.
 *
 * <p><b>Two interceptors on the ordinary path, and the reason each is there.</b> The bearer is added
 * here rather than being anything the caller supplies — it is peer authentication between qits and
 * the container, so a caller-supplied one would be meaningless and a forwarded one would be a
 * credential leak. The host rewrite pins the authority the daemon sees to a constant, so it does not
 * change under the daemon when the origin does (which is exactly what the dial-back tunnel does).
 * The upgrade path arranges both for itself, one mechanism per path.
 */
@ApplicationScoped
class DaemonForwarder {

  private static final Logger LOG = Logger.getLogger(DaemonForwarder.class);

  @Inject DaemonProxyTargets targets;

  @Inject WorkspaceTunnels tunnels;

  /** The bearer the daemon requires; the same value {@code WorkspaceContainerFactory} injects. */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /**
   * How to reach this workspace's daemon: through its reverse tunnel, or not at all. Blocking: call
   * it on a worker thread.
   *
   * <p>The tunnel branch does not consult the workspace row or docker. A live control socket is
   * stronger evidence that the container is up than {@code docker inspect} is, and it costs one
   * round-trip less per request; {@code WorkspaceTunnels} is keyed on the same row id, and a
   * soft-deleted workspace's daemon is not connected. With no tunnel, {@link
   * DaemonProxyTargets#resolve} only says which absence it is (qits-780: a daemon too old to serve a
   * tunnel has no route any more, and answers the same 503 as one that is not connected).
   *
   * <p><b>That lookup holds through a postgres cutover rather than answering 404.</b> It reads the
   * workspace row, and a connection severed mid-flight surfaces here as an exception. {@code
   * DbRetry} is what turns that into a held request: connection-class failures only, bounded by its
   * own deadline, and a genuine absence is not a failure at all — it returns {@code NO_WORKSPACE}
   * and still 404s on the first attempt.
   *
   * <p><b>The wrap is HERE, at the caller, and that placement is the rule rather than a
   * convenience.</b> {@code resolve} is {@code @Transactional}, so retrying inside it would re-run
   * statements on a transaction already marked for rollback; the retry has to surround the
   * transactional call so each attempt gets a new one. This runs on the worker thread {@code
   * executeBlocking} gave it, holding no monitor and no session, which is the other half of the same
   * rule.
   */
  Resolved resolve(Long workspaceId) {
    return tunnels
        .originFor(workspaceId)
        .map(Resolved::tunnelled)
        .orElseGet(
            () ->
                Resolved.absent(
                    DbRetry.call(
                        "workspace daemon proxy lookup", () -> targets.resolve(workspaceId))));
  }

  /** Either a tunnel entrance, or the reason there is none. */
  record Resolved(WorkspaceTunnels.TunnelOrigin tunnel, DaemonProxyTargets.Reachability absence) {
    /** The caller may not reach this daemon; compared by identity, never routed. */
    static final Resolved REFUSED = new Resolved(null, null);

    static Resolved tunnelled(WorkspaceTunnels.TunnelOrigin origin) {
      return new Resolved(origin, null);
    }

    static Resolved absent(DaemonProxyTargets.Reachability absence) {
      return new Resolved(null, absence);
    }
  }

  /**
   * Answer differently for each way a daemon can be absent, else forward to {@code daemonUri} (path
   * and query, as the daemon is to receive them). A naive proxy reports "no such workspace",
   * "container not running" and "daemon never connected" as one indistinguishable 502, and then
   * every daemon problem looks like the same problem.
   */
  void route(RoutingContext rc, Resolved resolved, String daemonUri) {
    if (resolved.tunnel() != null) {
      // The authority the interceptors pin is the daemon's own port, so the daemon sees the same
      // caller whichever loopback port the tunnel bound. The client is the tunnel's, never a shared
      // one; see WorkspaceTunnels for what sharing it would cost.
      forward(rc, resolved.tunnel().client(), resolved.tunnel().port(), "127.0.0.1", daemonUri);
      return;
    }
    switch (resolved.absence()) {
      case NO_WORKSPACE -> respond(rc, 404, "No workspace here.");
      // No tunnel: there is no other origin to fall back on. A TARGET row's container is on a
      // runner's node, and a daemon too old for the tunnel has no route any more (qits-780).
      case NOT_CONNECTED -> respond(rc, 503, "workspace daemon not connected");
      case NO_CONTAINER ->
          respond(
              rc,
              502,
              "The workspace container is not running — start it from the workspace page.");
    }
  }

  /**
   * One origin, two transports. An ordinary request goes through {@code vertx-http-proxy}; a
   * WebSocket upgrade does not, and {@link #proxyUpgrade} says why.
   */
  private void forward(
      RoutingContext rc, HttpClient client, int port, String host, String daemonUri) {
    if (isWebSocketUpgrade(rc.request())) {
      proxyUpgrade(rc, client, port, host, daemonUri);
      return;
    }
    HttpProxy proxy =
        HttpProxy.reverseProxy(client)
            .origin(port, host)
            .addInterceptor(bearer(daemonApiToken))
            .addInterceptor(hostRewrite(ContainerProxyPath.DAEMON_API_PORT));
    if (!daemonUri.equals(rc.request().uri())) {
      proxy.addInterceptor(uriRewrite(daemonUri));
    }
    proxy.handle(rc.request());
  }

  static boolean isWebSocketUpgrade(HttpServerRequest request) {
    return request.headers().contains(HttpHeaders.UPGRADE, "websocket", true);
  }

  /**
   * Carry a WebSocket upgrade to the daemon ourselves, rather than letting {@code vertx-http-proxy}
   * do it.
   *
   * <p><b>{@code vertx-http-proxy} handles an upgrade with no flow control at all.</b> Read out of
   * 4.5.26: {@code ReverseProxy.handle} branches to {@code handleWebSocketUpgrade} and returns
   * before the interceptor iterator is installed — so both interceptors above are dead on this path
   * — and the pipe it then builds is three bare handler installs, {@code
   * serverRequest.handler(clientRequest::write)} before the 101 and {@code a.handler(b::write)} both
   * ways after it. No {@code writeQueueFull}, no {@code pause}, no {@code drainHandler}, and a
   * failure arm that prints {@code "Handle this case"} to {@code System.err} followed by a stack
   * trace. A fast producer in the container — a chatty dev server on a terminal socket, a runaway
   * log stream — has nothing telling it to slow down, and the bytes pile up in this process's heap.
   * {@code DaemonStreamRoute} pauses and drains correctly on the tunnel hop; this is the same pipe
   * one hop closer to the browser, and it was the only one without the discipline.
   *
   * <p>So the upgrade is done by hand: the same request, the same headers, the same raw byte pipe —
   * and {@code pause}/{@code drainHandler} in both directions, plus a failure arm that answers the
   * caller. The request line carries {@code daemonUri}, the path the calling route chose, and the
   * daemon still authenticates the handshake with the bearer this service presents.
   *
   * <p>The bearer is set on the <em>outbound</em> request here rather than mutated onto the inbound
   * one, which is what the interceptor-skipping defect used to force. It is peer authentication
   * between qits and the container — set, never forwarded — exactly as {@link #bearer} does for an
   * ordinary request. There is still one mechanism per path.
   *
   * <p>{@code Host} is deliberately not copied, so the daemon sees the origin's own authority rather
   * than the browser's. That is what {@link #hostRewrite} arranges for an ordinary request, and the
   * client's own default arranges here. Nothing in {@code WorkspaceApi} reads it either way.
   */
  private void proxyUpgrade(
      RoutingContext rc, HttpClient client, int port, String host, String daemonUri) {
    HttpServerRequest inbound = rc.request();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(host)
                .setPort(port)
                .setURI(daemonUri))
        .onFailure(
            failure -> {
              LOG.debugf("workspace-daemon upgrade could not be opened: %s", String.valueOf(failure));
              inbound.resume();
              respond(
                  rc,
                  502,
                  "The workspace container is not reachable — try restarting the workspace"
                      + " container.");
            })
        .onSuccess(outbound -> openUpgrade(rc, inbound, outbound));
  }

  /** Send the handshake on, streaming whatever precedes the 101 with a queue bound on it. */
  private void openUpgrade(
      RoutingContext rc, HttpServerRequest inbound, HttpClientRequest outbound) {
    for (Map.Entry<String, String> header : inbound.headers()) {
      if (HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().set(HttpHeaders.CONNECTION, HttpHeaders.UPGRADE);
      } else if (!HttpHeaders.HOST.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().add(header.getKey(), header.getValue());
      }
    }
    outbound.headers().set("Authorization", "Bearer " + daemonApiToken);

    Future<HttpClientResponse> handshake = outbound.connect();
    inbound.handler(
        buffer -> {
          outbound.write(buffer);
          if (outbound.writeQueueFull()) {
            inbound.pause();
            outbound.drainHandler(drained -> inbound.resume());
          }
        });
    inbound.endHandler(end -> outbound.end());
    // Paused since the route's `handle`, so the lookup could run off the event loop without losing
    // bytes.
    inbound.resume();

    handshake
        .onFailure(
            failure -> {
              LOG.debugf("workspace-daemon refused the upgrade: %s", String.valueOf(failure));
              respond(rc, 502, "The workspace container closed the connection.");
            })
        .onSuccess(response -> completeUpgrade(rc, inbound, response));
  }

  /**
   * Marry the two sockets once the daemon has agreed, or pass its refusal on.
   *
   * <p>A refusal is forwarded with its own status and body rather than flattened into a 502: the
   * daemon answers 401 for a bad bearer and 404 for a command that is not running, and those say
   * what to do. {@code vertx-http-proxy} answers the status alone with no body at all.
   *
   * <p>The handoff is handed to {@link UpgradePipe#marry} straight after {@code netSocket()}, on
   * the daemon response's context and before anything else runs there, and the browser's {@code
   * toNetSocket()} is called on {@code inboundContext}, the request's own: each side has a window
   * in which an event reaches a socket with no handler and is dropped, and that class says how the
   * two are closed. {@code inboundContext} comes straight off {@code inbound} rather than being
   * threaded down from {@code handle} through both {@link ContainerProxyRoute} and {@link
   * AgentProxyRoute}: {@code HttpServerRequestInternal.context()} answers the same context {@code
   * Vertx.currentContext()} would give the route's own {@code handle}, and reading it here — at
   * the one place this class needs it — keeps both routes' call chains untouched.
   */
  private void completeUpgrade(
      RoutingContext rc, HttpServerRequest inbound, HttpClientResponse response) {
    HttpServerResponse out = inbound.response();
    if (response.statusCode() != 101) {
      out.setStatusCode(response.statusCode());
      for (Map.Entry<String, String> header : response.headers()) {
        // Hop-by-hop framing belongs to this response, not to the origin's.
        if (!HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())
            && !HttpHeaders.TRANSFER_ENCODING.toString().equalsIgnoreCase(header.getKey())) {
          out.headers().add(header.getKey(), header.getValue());
        }
      }
      // pipeTo, not a handler: it carries the backpressure and the end/failure wiring for free.
      response.pipeTo(out);
      return;
    }
    out.setStatusCode(101);
    out.headers().addAll(response.headers());
    NetSocket daemon = response.netSocket();
    Context inboundContext = ((HttpServerRequestInternal) inbound).context();
    UpgradePipe.marry(daemon, inboundContext, inbound::toNetSocket);
  }

  /**
   * Present qits' own credential to the daemon. The daemon's bearer is peer authentication between
   * this service and the container — it says "qits is calling", not "this user is calling", and the
   * daemon has no user identity to check anyway. So it is <em>set</em>, replacing whatever the
   * inbound request carried: forwarding a caller-supplied Authorization would be both meaningless
   * and a way to smuggle a credential into a container.
   */
  private static ProxyInterceptor bearer(String token) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().headers().set("Authorization", "Bearer " + token);
        return context.sendRequest();
      }
    };
  }

  /**
   * Pin the authority the daemon sees. Nothing in {@code WorkspaceApi} reads {@code Host} today, so
   * this changes no behaviour — it fixes one, which is the point: without it the daemon's view of
   * who called it is whatever the origin happens to be, and the origin is exactly what moves when
   * the dial-back tunnel replaces the direct container address. A header that quietly changes
   * meaning between two deployments of the same code is worth spending three lines to prevent.
   * ({@code ProxyInterceptor} has no abstract method — it is not a functional interface — so this
   * must be an explicit implementation, not a lambda.)
   */
  private static ProxyInterceptor hostRewrite(int port) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().setAuthority(HostAndPort.create("localhost", port));
        return context.sendRequest();
      }
    };
  }

  /** Send the request to the daemon path the calling route mapped, not the inbound one. */
  private static ProxyInterceptor uriRewrite(String daemonUri) {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        context.request().setURI(daemonUri);
        return context.sendRequest();
      }
    };
  }

  /**
   * Errors here answer JSON. This route's client is code calling a JSON API, and the shape matches
   * what the daemon itself answers a failure with ({@code {"message": …}}) so a client's error
   * handling does not fork on which hop failed.
   */
  static void respond(RoutingContext rc, int status, String message) {
    rc.response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(new io.vertx.core.json.JsonObject().put("message", message).encode());
  }
}
