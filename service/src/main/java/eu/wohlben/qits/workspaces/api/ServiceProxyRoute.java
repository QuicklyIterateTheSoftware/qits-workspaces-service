package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.ServiceProxyPath;
import eu.wohlben.qits.workspaces.control.ServiceSupervisor;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels.TunnelOrigin;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalInt;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The service web-view reverse proxy: {@code /service/{workspaceId}/{serviceId}/*} on the qits
 * origin forwards to the service's dev server, reached by the workspace container's DNS name on the
 * shared {@code qits-net} network — verbatim passthrough, no prefix stripping (the one rewrite is
 * the {@code Host} header, see {@link #hostRewrite}). The dev server itself serves under the prefix
 * (it was launched with {@code QITS_PUBLIC_BASE}, see {@link ServiceProxyPath}), so assets and the
 * HMR websocket stay inside it; vertx-http-proxy forwards WebSocket upgrades by default. Because
 * the frame shares the qits origin, the UI's DOM picker reads {@code iframe.contentDocument}
 * directly — no injection.
 *
 * <p>Security posture: the origin is resolved exclusively from supervisor state — the container
 * name and port come from the service definition recorded at launch, never from any request
 * component; unknown keys 404 without connecting anywhere (the SSRF constraints from the feature
 * doc). Two accepted consequences, both bounded by the existing trust model ("qits runs these apps
 * as processes with the user's privileges"): the framed app's JS runs same-origin with qits, and
 * every web-viewable service is reachable by anyone who can reach qits itself. Note {@code
 * /service/*} is a raw router route, so websockets-next's {@code SameOriginUpgradeCheck} does not
 * guard it — but the global {@code QitsAuthPolicy} (auth-core) does: in every auth build variant
 * this route requires an authenticated identity like the rest of the UI surface (the oauth session
 * cookie or the proxy's forward-auth headers ride along automatically, the iframe being
 * same-origin).
 *
 * <h2>A RUNNER row's dev server is reached through the tunnel (qits-625, qits-815)</h2>
 *
 * <p>Its container is on a runner's node with no {@code qits-net}, so there is no container name
 * to dial and {@link ServiceSupervisor} records no origin for it. The proxy asks {@link
 * WorkspaceTunnels} for a {@link StreamTarget#SERVICE} stream instead, naming the service by its
 * id; the daemon resolves the id to its own supervised web-view port. Nothing else about the route
 * changes — the tunnel carries raw bytes, so the {@code Host} rewrite and the HMR upgrade cross it
 * as they cross the direct path. There is no direct fallback for such a row: an unconnected daemon
 * answers that it is not connected, and one whose image predates the SERVICE target answers that
 * the image must be recreated. A DIRECT row keeps the direct origin, untouched.
 */
@ApplicationScoped
public class ServiceProxyRoute {

  @Inject Vertx vertx;

  @Inject ServiceSupervisor supervisor;

  @Inject WorkspaceTunnels tunnels;

  /** The row's placement, read on the worker beside the supervisor lookup. */
  @Inject WorkspaceRepository workspaces;

  /**
   * Only relevant when qits itself runs under a path prefix (a qits-in-qits service bridges {@code
   * -Dquarkus.http.root-path}): the route below is registered on the root-path-mounted router, so
   * it matches relative to the prefix — but {@code rc.request().path()} returns the FULL path, so
   * the segment parse must strip the prefix first. {@code "/"} (the normal deployment) strips
   * nothing.
   *
   * <p>This one may keep reading the {@code quarkus.*} key, unlike {@link CaptureCorsRoute}, and the
   * difference is worth stating because the two look identical. Build-time config items are absent
   * from a native image's runtime config, so such a lookup silently takes its {@code defaultValue}
   * there — harmless only when the default is also the deployed value. It is: every normal
   * deployment runs at root path {@code /}, and the one shape that does not bridges
   * {@code -Dquarkus.http.root-path} as a <em>system property</em>, which does reach the runtime
   * config of a binary. {@code quarkus.rest.path} had neither property, which is why it moved.
   */
  @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
  String rootPath;

  private HttpClient proxyClient;
  private String rootPrefix;

  void init(@Observes Router router) {
    proxyClient = vertx.createHttpClient();
    rootPrefix = RootPath.prefix(rootPath);
    router.route(ServiceProxyPath.PREFIX + "*").handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character
    // short of `start` — where the substring below would overflow. No segments, no service.
    int start = rootPrefix.length() + ServiceProxyPath.PREFIX.length();
    if (path.length() < start) {
      respond(rc, 404, "No service here.");
      return;
    }
    String[] segments = path.substring(start).split("/", 3);
    if (segments.length < 2 || segments[0].isEmpty() || segments[1].isEmpty()) {
      respond(rc, 404, "No service here.");
      return;
    }
    // The first segment is the workspace id. A non-numeric one is simply not a workspace — 404
    // without touching the supervisor, the same as an unknown one.
    Long workspaceId;
    try {
      workspaceId = Long.valueOf(segments[0]);
    } catch (NumberFormatException notAnId) {
      respond(rc, 404, "No service here.");
      return;
    }
    String serviceId = segments[1];

    if (segments.length == 2) {
      // Redirect the bare /service/{w}/{d} to the trailing-slash form so relative URLs inside the
      // framed document resolve under the base path.
      String query = rc.request().query();
      rc.response()
          .setStatusCode(302)
          .putHeader("Location", path + "/" + (query == null ? "" : "?" + query))
          .end();
      return;
    }

    // The request stays untouched while the supervisor lookup runs off the event loop (its monitor
    // can be held for the duration of a service launch); the proxy resumes it when forwarding. The
    // placement read and a RUNNER row's tunnel resolution (a row read, then a bind on first use)
    // ride the same worker.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(() -> lookup(workspaceId, serviceId))
        .onFailure(e -> respond(rc, 502, "Service lookup failed."))
        .onSuccess(lookup -> route(rc, lookup));
  }

  /**
   * What the event loop needs to answer: the supervisor's target and, for a READY service on a
   * RUNNER row, how the tunnel answered. {@code tunnel} is null for every DIRECT row.
   */
  private record Lookup(Optional<ServiceSupervisor.ProxyTarget> target, TunnelRoute tunnel) {}

  /** A RUNNER row's way in: a tunnel origin, or the reason there is none. Exactly one is set. */
  private record TunnelRoute(TunnelOrigin origin, String refusal) {}

  static final String NOT_CONNECTED = "the workspace daemon is not connected";

  static final String PREDATES_SERVICE_TUNNEL =
      "this workspace's image predates tunnelled web views — recreate it";

  /** Blocking: the supervisor monitor, a row read and possibly a tunnel bind. */
  private Lookup lookup(Long workspaceId, String serviceId) {
    Optional<ServiceSupervisor.ProxyTarget> target = supervisor.proxyTarget(workspaceId, serviceId);
    // Only a READY service is ever forwarded, so only then does the placement matter.
    if (target.isEmpty()
        || target.get().status() != ServiceStatus.READY
        || !isRunnerRow(workspaceId)) {
      return new Lookup(target, null);
    }
    return new Lookup(target, tunnelRoute(workspaceId, serviceId));
  }

  private boolean isRunnerRow(Long workspaceId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaces
                    .findActiveById(workspaceId)
                    .map(w -> w.placement == WorkspacePlacement.RUNNER)
                    .orElse(Boolean.FALSE));
  }

  /**
   * A RUNNER row's tunnel to {@code serviceId}. The capability is read first because {@link
   * WorkspaceTunnels#originFor} answers "no daemon" and "a daemon too old for SERVICE" alike, and
   * the two need different answers: one is a blip, the other never heals without a new image.
   */
  private TunnelRoute tunnelRoute(Long workspaceId, String serviceId) {
    OptionalInt capability = tunnels.daemonCapability(workspaceId);
    if (capability.isEmpty()) {
      return new TunnelRoute(null, NOT_CONNECTED);
    }
    if (capability.getAsInt() < WorkspaceTunnels.capabilityFor(StreamTarget.SERVICE)) {
      return new TunnelRoute(null, PREDATES_SERVICE_TUNNEL);
    }
    return tunnels
        .originFor(workspaceId, StreamTarget.SERVICE, serviceId)
        .map(origin -> new TunnelRoute(origin, null))
        .orElseGet(() -> new TunnelRoute(null, NOT_CONNECTED));
  }

  private void route(RoutingContext rc, Lookup lookup) {
    Optional<ServiceSupervisor.ProxyTarget> target = lookup.target();
    if (target.isEmpty()) {
      respond(rc, 404, "No web-viewable service here.");
      return;
    }
    ServiceStatus status = target.get().status();
    switch (status) {
      case STARTING, RESTARTING -> respondSplash(rc, status);
      case STOPPED, CRASHED ->
          respond(
              rc,
              502,
              "The service is not running (" + status + ") — start it from the workspace page.");
      case READY -> {
        if (lookup.tunnel() != null) {
          proxyThroughTunnel(rc, lookup.tunnel(), target.get().webViewPort());
          return;
        }
        ProxyOrigin origin = target.get().origin();
        if (origin == null) {
          respond(
              rc,
              502,
              "The workspace container is not reachable — try restarting the workspace container.");
          return;
        }
        // Per-request proxy over the shared client: the origin is fixed here, from supervisor
        // state only (the container's name + port on the shared network) — never derived from the
        // request.
        HttpProxy.reverseProxy(proxyClient)
            .origin(origin.port(), origin.host())
            .addInterceptor(hostRewrite(origin.port()))
            .handle(rc.request());
      }
    }
  }

  /**
   * A RUNNER row's READY service: through the workspace's own SERVICE tunnel, over the client that
   * tunnel owns (never the shared one — see {@link WorkspaceTunnels} on reused ephemeral ports). The
   * {@code Host} is the one the direct path presents, {@code localhost:<webView port>}, so the dev
   * server cannot tell the two transports apart.
   */
  private void proxyThroughTunnel(RoutingContext rc, TunnelRoute tunnel, int webViewPort) {
    if (tunnel.origin() == null) {
      respond(rc, 502, tunnel.refusal());
      return;
    }
    HttpProxy.reverseProxy(tunnel.origin().client())
        .origin(tunnel.origin().port(), "127.0.0.1")
        .addInterceptor(hostRewrite(webViewPort))
        .handle(rc.request());
  }

  /**
   * Present qits to the framed dev server as {@code localhost} rather than the workspace
   * container's DNS name. Angular's (Vite/webpack) dev server rejects any {@code Host} that isn't
   * localhost/an IP/allow-listed ("This host is not allowed"); {@code localhost} is always allowed.
   * This restores the Host the dev server saw before qits moved onto {@code qits-net}: back then
   * qits reached containers through a published {@code 127.0.0.1:port}, so the check passed;
   * reaching them by DNS name is what started sending the rejected Host. TCP still targets the
   * fixed origin ({@code .origin(...)}); only the Host/:authority header changes, so no per-app
   * {@code allowedHosts} config is needed. ({@code ProxyInterceptor} has no abstract method — it is
   * not a functional interface — so this must be an explicit implementation, not a lambda.)
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

  /** A qits-branded splash that refreshes itself until the dev server is up. */
  private void respondSplash(RoutingContext rc, ServiceStatus status) {
    String html =
        "<!doctype html><html><head><title>qits</title>"
            + "<meta http-equiv=\"refresh\" content=\"2\">"
            + "<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;color:#666}</style></head>"
            + "<body><p>service is "
            + status.name().toLowerCase()
            + "… this page refreshes automatically</p></body></html>";
    rc.response().setStatusCode(200).putHeader("Content-Type", "text/html").end(html);
  }

  private void respond(RoutingContext rc, int status, String message) {
    String html =
        "<!doctype html><html><head><title>qits</title>"
            + "<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;color:#666}</style></head>"
            + "<body><p>"
            + message
            + "</p></body></html>";
    rc.response().setStatusCode(status).putHeader("Content-Type", "text/html").end(html);
  }
}
