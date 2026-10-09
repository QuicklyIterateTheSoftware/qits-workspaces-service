package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The workspace-daemon reverse proxy: {@code /workspaces/container/{workspaceId}/*} forwards
 * verbatim to that workspace's in-container {@code qits-workspace-daemon}, reached through the
 * reverse tunnel its daemon dialled back for — the only way to a daemon since qits-780 deleted the
 * direct {@code container:13338} fallback. The target is looked up off the event loop and resolved
 * from our own state, never from the request.
 *
 * <p><b>This is the only way to reach a daemon.</b> Its HTTP API is where the file browser, the
 * commands surface, the coding-agent surface, the bootstrap surface and the two
 * interactive websockets all live, and until this route existed none of them had an address at all
 * (migration-plan.md §9 item 16). The daemon is deliberately not a gateway route: the gateway's
 * table is static configuration mapping one prefix to one {@code host:port}, and a daemon is one
 * process per container with no stable address to configure. {@link ContainerProxyPath} carries the
 * full argument.
 *
 * <p><b>Verbatim means verbatim: this route rewrites no path.</b> The daemon receives
 * {@code /workspaces/container/{workspaceId}/files}, not {@code /files}, and is <em>configured</em>
 * to know that leading part is its own address —
 * {@code WorkspaceContainerFactory} injects {@link ContainerProxyPath#base} as
 * {@code QITS_WORKSPACE_DAEMON_API_BASE_PATH} at container creation. That is a deliberate rule rather than an inherited shape: a hop that rewrites a path leaves the two ends
 * disagreeing about the destination's own address, and the disagreement shows up in redirects,
 * generated links and logs, a long way from the rewrite. Stripping here would also have to be done
 * twice — the upgrade path below takes the URI straight off the inbound request — so the rewrite
 * that looks like one line is two.
 *
 * <p><b>An upgrade takes a different road through this class, and that is deliberate.</b>
 * {@code WS /terminal/commands/{id}} and {@code WS /chat/commands/{id}} are proxied by
 * {@link #proxyUpgrade} rather than by {@code vertx-http-proxy}, because the library's upgrade path
 * skips its own interceptor chain <em>and</em> pipes the socket with no flow control whatsoever.
 * That method carries the measurement. Both ends still see an ordinary WebSocket: the browser's
 * socket traverses gateway → qits-workspaces → daemon, the daemon authenticates the handshake with
 * the same bearer it checks on every request, and neither hop uses websockets-next, so no framework
 * origin check is involved on either.
 *
 * <p><b>Two interceptors on the ordinary path, and the reason each is there.</b> The bearer is added
 * here rather than being anything the caller supplies — it is peer authentication between qits and
 * the container, so a caller-supplied one would be meaningless and a forwarded one would be a
 * credential leak. The host rewrite pins the authority the daemon sees to a constant, so it does not
 * change under the daemon when the origin does (which is exactly what the dial-back tunnel does).
 * The upgrade path arranges both for itself, one mechanism per path.
 *
 * <p>Security posture, stated plainly because it is easy to overstate: this route scopes a request
 * to an existing ACTIVE workspace and forwards it. It does not authorize the caller, because there
 * is no owner to authorize against — qits is single-user. See {@link DaemonProxyTargets}.
 */
@ApplicationScoped
public class ContainerProxyRoute {

  private static final Logger LOG = Logger.getLogger(ContainerProxyRoute.class);

  @Inject Vertx vertx;

  @Inject DaemonProxyTargets targets;

  @Inject WorkspaceTunnels tunnels;

  /** The bearer the daemon requires; the same value {@code WorkspaceContainerFactory} injects. */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /**
   * Only relevant when qits itself runs under a path prefix (a qits-in-qits service bridges {@code
   * -Dquarkus.http.root-path}): the route is registered on the root-path-mounted router, so it
   * matches relative to the prefix — but {@code rc.request().path()} returns the FULL path, so the
   * segment parse must strip the prefix first. {@code "/"} (the normal deployment) strips nothing.
   *
   * <p>This one may keep reading the {@code quarkus.*} key, unlike {@link CaptureCorsRoute}, and the
   * difference is worth stating because the two look identical. Build-time config items are absent
   * from a native image's runtime config, so such a lookup silently takes its {@code defaultValue}
   * there — harmless only when the default is also the deployed value. It is: every normal
   * deployment runs at root path {@code /}, and the one shape that does not bridges {@code
   * -Dquarkus.http.root-path} as a <em>system property</em>, which does reach the runtime config of
   * a binary. {@code quarkus.rest.path} had neither property, which is why it moved.
   */
  @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
  String rootPath;

  private String rootPrefix;

  void init(@Observes Router router) {
    rootPrefix = RootPath.prefix(rootPath);
    router.route(ContainerProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character
    // short of `start` — where the substring below would overflow. No segments, no workspace.
    int start = rootPrefix.length() + ContainerProxyPath.PREFIX.length();
    if (path.length() < start) {
      respond(rc, 404, "No workspace here.");
      return;
    }
    // Limit 2: the id, then the rest. The rest is only ever LOOKED AT here — it is not removed, and
    // the request that goes on the wire still carries the whole path. The daemon knows this prefix
    // is its address because it was told so at container creation; see the class javadoc.
    String[] segments = path.substring(start).split("/", 2);
    if (segments.length < 1 || segments[0].isEmpty()) {
      respond(rc, 404, "No workspace here.");
      return;
    }
    // A non-numeric first segment is simply not a workspace — 404 without touching the database,
    // and the same 404 an unknown or soft-deleted one gets.
    Long workspaceId;
    try {
      workspaceId = Long.valueOf(segments[0]);
    } catch (NumberFormatException notAnId) {
      respond(rc, 404, "No workspace here.");
      return;
    }

    // The request stays untouched while the lookup runs off the event loop — it reads a row and may
    // bind a listener, so it needs a worker thread and a transaction; the proxy resumes it when
    // forwarding.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(() -> resolve(workspaceId))
        .onFailure(e -> respond(rc, 502, "Workspace lookup failed."))
        .onSuccess(resolved -> route(rc, resolved));
  }

  /**
   * How to reach this workspace's daemon: through its reverse tunnel, or not at all.
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
  private Resolved resolve(Long workspaceId) {
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
  private record Resolved(
      WorkspaceTunnels.TunnelOrigin tunnel, DaemonProxyTargets.Reachability absence) {
    static Resolved tunnelled(WorkspaceTunnels.TunnelOrigin origin) {
      return new Resolved(origin, null);
    }

    static Resolved absent(DaemonProxyTargets.Reachability absence) {
      return new Resolved(null, absence);
    }
  }

  /**
   * Answer differently for each way a daemon can be absent. A naive proxy reports "no such
   * workspace", "container not running" and "daemon never connected" as one indistinguishable 502,
   * and then every daemon problem looks like the same problem.
   */
  private void route(RoutingContext rc, Resolved resolved) {
    if (resolved.tunnel() != null) {
      // The authority the interceptors pin is the daemon's own port, so the daemon sees the same
      // caller whichever loopback port the tunnel bound. The client is the tunnel's, never a shared
      // one; see WorkspaceTunnels for what sharing it would cost.
      forward(rc, resolved.tunnel().client(), resolved.tunnel().port(), "127.0.0.1");
      return;
    }
    switch (resolved.absence()) {
      case NO_WORKSPACE -> respond(rc, 404, "No workspace here.");
      // No tunnel: there is no other origin to fall back on. A RUNNER row's container is on a
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
  private void forward(RoutingContext rc, HttpClient client, int port, String host) {
    if (isWebSocketUpgrade(rc.request())) {
      proxyUpgrade(rc, client, port, host);
      return;
    }
    HttpProxy.reverseProxy(client)
        .origin(port, host)
        .addInterceptor(bearer(daemonApiToken))
        .addInterceptor(hostRewrite(ContainerProxyPath.DAEMON_API_PORT))
        .handle(rc.request());
  }

  private static boolean isWebSocketUpgrade(HttpServerRequest request) {
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
   * caller. Nothing about the contract moves. The path is still forwarded verbatim (the daemon is
   * configured to know this prefix is its own address), the daemon still authenticates the handshake
   * with the bearer this service presents, and neither end learns it is proxied.
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
  private void proxyUpgrade(RoutingContext rc, HttpClient client, int port, String host) {
    HttpServerRequest inbound = rc.request();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost(host)
                .setPort(port)
                .setURI(inbound.uri()))
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
    // Paused since `handle`, so the lookup could run off the event loop without losing bytes.
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
    inbound
        .toNetSocket()
        .onFailure(
            failure -> {
              LOG.debugf("could not take over the browser socket: %s", String.valueOf(failure));
              daemon.close();
            })
        .onSuccess(browser -> pipe(browser, daemon));
  }

  /**
   * Pump bytes both ways with a bound on each queue — the mirror of {@code DaemonStreamRoute.pipe},
   * and it has to be, because it is the same pipe one hop closer to the browser.
   *
   * <p><b>Raw bytes, not frames.</b> Neither end is decoded: what the browser sends is what the
   * daemon receives, frame boundaries, fragmentation, ping/pong and close codes included. That is
   * the property that lets the terminal's own close semantics (a bare 1000 meaning "detached, the
   * process survives") arrive intact, and it is why this is a {@code NetSocket} pipe rather than a
   * WebSocket client that would re-frame everything it forwarded.
   *
   * <p>The backpressure is the point. A write into a full queue is buffered on the heap without
   * complaint, so the reader on the other side has to be paused until it drains — otherwise a dev
   * server printing faster than a browser reads is an unbounded allocation in this process. Every
   * close, end and exception path closes the other side, so neither socket outlives its peer.
   */
  private static void pipe(NetSocket browser, NetSocket daemon) {
    browser.handler(
        buffer -> {
          daemon.write(buffer);
          if (daemon.writeQueueFull()) {
            browser.pause();
            daemon.drainHandler(drained -> browser.resume());
          }
        });
    daemon.handler(
        buffer -> {
          browser.write(buffer);
          if (browser.writeQueueFull()) {
            daemon.pause();
            browser.drainHandler(drained -> daemon.resume());
          }
        });
    browser.endHandler(end -> daemon.close());
    daemon.endHandler(end -> browser.close());
    browser.closeHandler(closed -> daemon.close());
    daemon.closeHandler(closed -> browser.close());
    browser.exceptionHandler(failure -> daemon.close());
    daemon.exceptionHandler(failure -> browser.close());
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

  /**
   * Errors here answer JSON, not the qits-branded HTML {@link EditorProxyRoute} uses. That route's
   * client is a browser rendering the editor; this one's is code calling a JSON API, and the shape matches what the daemon itself answers a failure with ({@code {"message": …}}) so a
   * client's error handling does not fork on which hop failed.
   */
  private void respond(RoutingContext rc, int status, String message) {
    rc.response()
        .setStatusCode(status)
        .putHeader("Content-Type", "application/json")
        .end(new io.vertx.core.json.JsonObject().put("message", message).encode());
  }
}
