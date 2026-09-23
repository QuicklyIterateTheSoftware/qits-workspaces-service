package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.containershost.EditorKeepalive;
import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.EditorHost;
import eu.wohlben.qits.workspaces.control.EditorLifecycle;
import eu.wohlben.qits.workspaces.control.EditorProxyTargets;
import eu.wohlben.qits.workspaces.control.WorkspaceEditorState;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels;
import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.httpproxy.HttpProxy;
import io.vertx.httpproxy.ProxyContext;
import io.vertx.httpproxy.ProxyInterceptor;
import io.vertx.httpproxy.ProxyResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The web editor's data path: everything arriving on the editor's origin is forwarded, byte for byte
 * and path for path, to the platform's one editor inside its workspace container.
 *
 * <p>The sibling of {@link ContainerProxyRoute} and built from its parts — the same hand-rolled
 * upgrade, the same both-direction backpressure, the same {@code DbRetry} at the lookup. What
 * differs is everything about how a request selects a workspace, and that is the whole design.
 *
 * <h2>A host, not a path prefix</h2>
 *
 * <p>openvscode-server serves from {@code /} with its own service worker, websockets and webviews,
 * and this platform rewrites no paths anywhere — so the editor cannot live under a prefix and is a
 * whole origin instead, aliased at the edge onto this service. This route therefore matches on the
 * <b>forwarded host</b> rather than on a path: {@link EditorHost} answers whether the first entry of
 * {@code X-Forwarded-Host} is the editor's, and anything that is not falls straight through to the
 * surface it was always going to reach ({@code rc.next()}).
 *
 * <p><b>The host selects a SURFACE and not a workspace.</b> There is one editor for the platform, so
 * the row is the one {@link EditorProxyTargets} finds and the name has nothing left to say about
 * which one it is — it used to carry a project label, and the whole per-project resolution went with
 * it. The container name is derived from that row, and the listener inside it is asked for by
 * <em>name</em> over the reverse tunnel, so no port is ever stated on this side — {@link
 * eu.wohlben.qits.workspaces.control.DaemonProxyTargets}' posture verbatim, and for its reason: a
 * component of a request that could name an origin would be an SSRF primitive aimed at everything on
 * the platform network. No editor row is a 404 with nothing dialled.
 *
 * <h2>The identity headers are required, and then removed</h2>
 *
 * <p>Two halves of one rule, and each is wrong without the other.
 *
 * <p><b>Required</b>, because this route performs no authentication of its own and must not invent
 * any: the platform session gate at the edge is the auth boundary, and the {@code X-Qits-*} headers
 * are what says a request came through it. That namespace is stripped from every inbound request at
 * the edge unconditionally, so a caller cannot forge one — which is exactly what makes their
 * presence evidence and their absence a refusal rather than an anonymous pass.
 *
 * <p><b>Removed</b>, because the editor must never see them. It runs an untrusted checkout with a
 * shell and unrestricted outbound network; a platform identity header it could read is a header it
 * could echo at something that trusts the namespace. For the same reason no bearer is added on the
 * way out — unlike {@link ContainerProxyRoute}, which presents qits' own credential to the daemon,
 * there is nothing to authenticate to here, and {@code Authorization} is dropped rather than
 * forwarded.
 *
 * <h2>The reverse tunnel is the only way in</h2>
 *
 * <p>There is no direct dial at the container's own address and there must not be one: the daemon
 * binds openvscode-server to the container's <b>loopback</b>, so nothing on {@code qits-net} can
 * reach it and a fallback pointed there is a connection refused dressed up as an origin. This route
 * used to carry that arm; what it bought in a shipped topology was a dial and then a 502, and what
 * it cost was that every hardening claim about the forwarding — the header strip, the verbatim
 * path, the bounded pipe — was provable only on the arm production traffic never takes. So a
 * RUNNING editor with no tunnel is its own answer, naming the daemon tunnel as the thing that is
 * missing.
 *
 * <h2>Five answers, because a waiting editor is not a broken one</h2>
 *
 * <p>The splash pattern is {@link ServiceProxyRoute}'s, gated on {@link WorkspaceEditorState}: a
 * container that is not up and an editor that has not finished starting are both <em>200 and a page
 * that refreshes itself</em>, an editor that has ended is a distinct 502 that stops the waiting, an
 * editor with no tunnel to it is a second distinct 502, and an editor nobody has opened yet is a
 * 404. A proxy that reported all of them as one connection error would make every editor problem
 * look like the same problem.
 */
@ApplicationScoped
public class EditorProxyRoute {

  private static final Logger LOG = Logger.getLogger(EditorProxyRoute.class);

  /**
   * Ahead of everything that could answer for {@code /} on this host, and behind nothing that has to
   * run first.
   *
   * <p>Read off Quarkus 3.34.6 and quinoa 2.8.2: the SPA's built files are served by {@code
   * GeneratedStaticResourcesProcessor} at <b>1060</b>, and Quinoa's SPA-routing fallback — the one
   * that answers {@code index.html} for every path nothing else took — at <b>40000</b>. So an editor
   * request must be claimed before both, and 1000 ({@code RouteConstants.ROUTE_ORDER_BEFORE_DEFAULT},
   * spelled as a literal because that constant is a runtime class this module does not otherwise
   * import) is where it is claimed.
   *
   * <p><b>This is what keeps the editor host off {@code index.html}</b>, and it is a stronger
   * guarantee than {@code quarkus.quinoa.ignored-path-prefixes} could give: that key is a list of
   * PATH prefixes, and an editor origin is defined by its host and serves every path there is —
   * {@code /}, {@code /static/…}, {@code /stable-<commit>/…} — so no prefix could name it. This
   * route simply never calls {@code rc.next()} once it has recognised an editor origin. Every answer
   * it can give, the 404 and the splash included, is an answer.
   *
   * <p>It is deliberately NOT ahead of this application's own routes, which take Vert.x's
   * auto-sequence from 0: they are all under {@code /workspaces}, nothing on an editor host ever
   * asks for one, and ordering behind them keeps this route from being in the way of the machine
   * surface it has nothing to do with.
   */
  private static final int ROUTE_ORDER = 1000;

  /**
   * The gateway's reserved namespace, lowercased for comparison. A literal rather than a shared
   * constant because {@code qits-auth-core} publishes the two header <em>names</em> as config and
   * not the prefix they share — and it is the prefix that matters here: what must not reach the
   * editor is the whole namespace, including any header the gateway learns to assert next year.
   */
  private static final String RESERVED_PREFIX = "x-qits-";

  /**
   * Where {@link #route} parks the workspace row id, so an upgraded socket's keepalive still knows
   * whose container it is holding open once the {@code RoutingContext} is all that is left of the
   * lookup.
   */
  private static final String ROW_ID = "qits.editor.rowId";

  @Inject EditorProxyTargets targets;

  @Inject WorkspaceTunnels tunnels;

  @Inject ContainerRuntime containers;

  @Inject EditorKeepalive keepalive;

  /**
   * The daemon's report about the editor inside the container. An {@code Instance<>} for the reason
   * {@code EditorService} holds one: the port is a {@code domain} seam, and a deployment with no
   * control plane answers empty — which this route reads as "still starting", the same as a first
   * frame that has not arrived.
   */
  @Inject Instance<WorkspaceEditorState> editorStates;

  /**
   * The header the edge asserts a session's user in. Read from {@code qits-auth-core}'s own key
   * rather than hard-coded, so this refusal and {@code ForwardAuthMechanism}'s acceptance can never
   * be looking at two different headers.
   */
  @ConfigProperty(name = "qits.auth.forward.user-header", defaultValue = "X-Qits-User")
  String userHeader;

  void init(@Observes Router router) {
    router.route().order(ROUTE_ORDER).handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    if (!EditorHost.isEditorHost(rc.request().getHeader("X-Forwarded-Host"))) {
      // Not the editor's origin: the workspaces host, qits-net, a health probe. This route claims
      // nothing it was not addressed by name.
      rc.next();
      return;
    }
    if (!hasPlatformIdentity(rc.request())) {
      refuse(rc);
      return;
    }

    // Paused while the lookup runs off the event loop — it reads rows and may bind a listener, so it
    // needs a worker thread and a transaction; the proxy resumes it when forwarding.
    rc.request().pause();
    rc.vertx()
        .executeBlocking(this::resolve)
        .onFailure(e -> respond(rc, 502, "The editor could not be looked up."))
        .onSuccess(resolved -> route(rc, resolved));
  }

  /**
   * Whether this request came through the platform edge.
   *
   * <p><b>403 and not 401</b>, and the difference is honest rather than pedantic: a 401 promises a
   * challenge, and this hop has none to issue — the login lives at the edge, which redirects a
   * browser with no session into it long before a request reaches here. So a request that arrives
   * without the namespace did not come through that gate at all, and the only true thing to say is
   * that it will not be served.
   */
  private boolean hasPlatformIdentity(HttpServerRequest request) {
    String user = request.getHeader(userHeader);
    return user != null && !user.isBlank();
  }

  /**
   * Everything the answer depends on, gathered on a worker thread in one go.
   *
   * <p>The order is what makes it cheap. The tunnel is asked <b>before</b> the container runtime,
   * because a live control socket at the editor capability is stronger evidence that the container
   * is up than an orchestrator round-trip is — the same reasoning {@link ContainerProxyRoute#resolve}
   * records — and an editor session is a stream of requests, so a status call per request would cost
   * more than the container does.
   *
   * <p><b>{@code DbRetry} wraps the lookup and nothing else.</b> {@code editor()} is {@code
   * @Transactional}, so the retry has to surround the transactional call for each attempt to get a
   * new transaction; and no editor row is an <em>answer</em> rather than a failure, so it 404s on
   * the first attempt instead of sitting on the deadline. This runs on the thread {@code
   * executeBlocking} gave it, holding no monitor and no session — the other half of the same rule.
   */
  private Resolved resolve() {
    EditorProxyTargets.EditorTarget target =
        DbRetry.call("editor proxy lookup", () -> targets.editor()).orElse(null);
    if (target == null) {
      return new Resolved(null, null, null, false);
    }
    Long rowId = target.workspaceRowId();
    // Somebody has this editor on screen. Debounced on the keepalive's own side and a no-op entirely
    // while the idle-stop switch is unset, which is how it ships.
    keepalive.touched(rowId);
    EditorLifecycle state = editorState(rowId);

    WorkspaceTunnels.TunnelOrigin tunnel =
        tunnels.originFor(rowId, StreamTarget.EDITOR).orElse(null);
    if (tunnel != null) {
      return new Resolved(target, state, tunnel, true);
    }
    // No tunnel: no daemon, or one too old to know what an editor is. Then — and only then — ask
    // whether the container is even up, because that is what tells a stopped workspace's splash from
    // a starting one's. There is no second way in to fall back to; see the class note.
    String container = containers.containerName(target.workspaceId(), target.repositoryId());
    return new Resolved(target, state, null, containers.isRunning(container));
  }

  /** The daemon's report, or null — an unreadable one is "nothing reported", which is waiting. */
  private EditorLifecycle editorState(Long rowId) {
    if (!editorStates.isResolvable()) {
      return null;
    }
    try {
      return editorStates.get().editorStateFor(rowId).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * What one request resolved to: the workspace, what its editor last said, and the one way to
   * reach it.
   *
   * @param target the editor's workspace, or null when there is no editor row yet
   * @param editorState the daemon's last report, or null when there is none
   * @param tunnel the reverse tunnel's entrance, when a capable daemon is connected
   * @param containerRunning whether the container is up at all — the splash's fork
   */
  private record Resolved(
      EditorProxyTargets.EditorTarget target,
      EditorLifecycle editorState,
      WorkspaceTunnels.TunnelOrigin tunnel,
      boolean containerRunning) {}

  /** Answer differently for each way an editor can be absent; see the class note. */
  private void route(RoutingContext rc, Resolved resolved) {
    if (resolved.target() == null) {
      // Nobody has opened the editor yet, so there is no row and no container — and nothing was
      // dialled to find that out. A GET at this origin deliberately does not create one: the door
      // does, which is where somebody asked for it.
      respond(rc, 404, "There is no editor for this address.");
      return;
    }
    if (!resolved.containerRunning()) {
      splash(rc, "The editor's workspace is not running. Opening it from qits starts it");
      return;
    }
    if (resolved.editorState() == EditorLifecycle.ENDED) {
      // Terminal, and that is why it is a status rather than a splash: the editor is not coming back
      // in this container, so a page that kept refreshing would spin for the container's lifetime.
      respond(
          rc,
          502,
          "The editor stopped inside this workspace — recreate the container from the workspace"
              + " page.");
      return;
    }
    if (resolved.editorState() != EditorLifecycle.RUNNING) {
      // STARTING, or nothing reported yet. Both are "wait", and a reader cannot act on the
      // difference: the container is up and the editor inside it is not serving.
      splash(rc, "The editor is starting");
      return;
    }
    if (resolved.tunnel() == null) {
      // The container is up and its editor says it is serving, and there is still no way in: the
      // daemon is not on its control socket, or it is an image that predates the editor stream. See
      // the class note on why this is a refusal and not a dial.
      respond(
          rc,
          502,
          "The editor is running but this workspace's daemon tunnel is not — reconnect it by"
              + " recreating the container from the workspace page.");
      return;
    }
    rc.put(ROW_ID, resolved.target().workspaceRowId());
    // The client is the tunnel's and never a shared one — see WorkspaceTunnels for what sharing it
    // across a reused ephemeral port would cost.
    forward(rc, resolved.tunnel().client(), resolved.tunnel().port(), "127.0.0.1");
  }

  /**
   * One origin, two transports, and the path forwarded verbatim on both — there is nothing to
   * rewrite, because the editor serves from {@code /} and this route's whole reason for being a host
   * rather than a prefix is that it never had to.
   */
  private void forward(RoutingContext rc, HttpClient client, int port, String host) {
    if (isWebSocketUpgrade(rc.request())) {
      proxyUpgrade(rc, client, port, host);
      return;
    }
    HttpProxy.reverseProxy(client).origin(port, host).addInterceptor(stripIdentity()).handle(
        rc.request());
  }

  private static boolean isWebSocketUpgrade(HttpServerRequest request) {
    return request.headers().contains(HttpHeaders.UPGRADE, "websocket", true);
  }

  /**
   * Carry a WebSocket upgrade to the editor ourselves, rather than letting {@code vertx-http-proxy}
   * do it. openvscode-server is mostly websocket — the extension host, every terminal, every file
   * watcher — so this is the path that carries the editor rather than an exception to it.
   *
   * <p>The library's upgrade path is not usable here for the two reasons {@link
   * ContainerProxyRoute#proxyUpgrade} measures out of 4.5.26: it returns before its interceptor
   * chain is installed, so the identity strip above would be dead on exactly the requests that carry
   * a session, and the pipe it builds is three bare {@code a.handler(b::write)} installs with no
   * {@code writeQueueFull}, no {@code pause} and no {@code drainHandler}. An editor streaming a
   * build log into a terminal is the fast producer that turns into an unbounded allocation in this
   * process.
   *
   * <p>{@code Host} is deliberately not copied, so the editor sees the origin's own authority rather
   * than the browser's — the same thing the ordinary path's client default arranges, so the editor
   * cannot tell which transport a request took. What says the public name is {@code
   * X-Forwarded-Host}, which is forwarded untouched.
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
              LOG.debugf("editor upgrade could not be opened: %s", String.valueOf(failure));
              inbound.resume();
              respond(rc, 502, "The editor is not reachable — try reloading in a moment.");
            })
        .onSuccess(outbound -> openUpgrade(rc, inbound, outbound));
  }

  /** Send the handshake on, streaming whatever precedes the 101 with a queue bound on it. */
  private void openUpgrade(
      RoutingContext rc, HttpServerRequest inbound, HttpClientRequest outbound) {
    for (Map.Entry<String, String> header : inbound.headers()) {
      if (HttpHeaders.CONNECTION.toString().equalsIgnoreCase(header.getKey())) {
        outbound.headers().set(HttpHeaders.CONNECTION, HttpHeaders.UPGRADE);
      } else if (!HttpHeaders.HOST.toString().equalsIgnoreCase(header.getKey())
          && !isPlatformCredential(header.getKey())) {
        outbound.headers().add(header.getKey(), header.getValue());
      }
    }

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
              LOG.debugf("the editor refused the upgrade: %s", String.valueOf(failure));
              respond(rc, 502, "The editor closed the connection.");
            })
        .onSuccess(response -> completeUpgrade(rc, inbound, response));
  }

  /**
   * Marry the two sockets once the editor has agreed, or pass its refusal on.
   *
   * <p>A refusal is forwarded with the origin's own status and body rather than flattened into a
   * 502: the editor answers its own 404s and 400s, and those say what happened. {@code
   * vertx-http-proxy} answers the status alone with no body at all.
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
    NetSocket editor = response.netSocket();
    Long rowId = rc.get(ROW_ID);
    inbound
        .toNetSocket()
        .onFailure(
            failure -> {
              LOG.debugf("could not take over the browser socket: %s", String.valueOf(failure));
              editor.close();
            })
        .onSuccess(browser -> pipe(browser, editor, rowId));
  }

  /**
   * Pump bytes both ways with a bound on each queue — {@link ContainerProxyRoute}'s pipe, one origin
   * over.
   *
   * <p><b>Raw bytes, not frames.</b> Neither end is decoded, so fragmentation, ping/pong and close
   * codes cross intact; re-framing would break the editor's own close semantics exactly as it would
   * a terminal's.
   *
   * <p>The keepalive rides the browser's side and not the editor's, because it is a statement about
   * a person: an editor talking to a browser that has gone away is not somebody using it. It is
   * called per frame deliberately — a keystroke is a frame, and the debounce is what makes that
   * affordable (one map operation, and not even that while the idle-stop switch is unset). An open
   * tab that reads a file for an hour sends nothing over HTTP and everything over this socket, so a
   * keepalive that only fired per request would let the sweep stop a container somebody is looking
   * at.
   */
  private void pipe(NetSocket browser, NetSocket editor, Long workspaceRowId) {
    browser.handler(
        buffer -> {
          keepalive.touched(workspaceRowId);
          editor.write(buffer);
          if (editor.writeQueueFull()) {
            browser.pause();
            editor.drainHandler(drained -> browser.resume());
          }
        });
    editor.handler(
        buffer -> {
          browser.write(buffer);
          if (browser.writeQueueFull()) {
            editor.pause();
            browser.drainHandler(drained -> editor.resume());
          }
        });
    browser.endHandler(end -> editor.close());
    editor.endHandler(end -> browser.close());
    browser.closeHandler(closed -> editor.close());
    editor.closeHandler(closed -> browser.close());
    browser.exceptionHandler(failure -> editor.close());
    editor.exceptionHandler(failure -> browser.close());
  }

  /**
   * Take the platform's identity off the request before the container sees it.
   *
   * <p>The whole {@code X-Qits-*} namespace and {@code Authorization}, and nothing is put back in
   * their place: unlike the daemon, the editor is not a peer this service authenticates to. It runs
   * an untrusted checkout with a shell, so a platform header it can read is a header it can echo at
   * something that trusts the namespace — and a bearer handed to it would be a credential moved
   * inside the sandbox for no one's benefit.
   *
   * <p>({@code ProxyInterceptor} has no abstract method — it is not a functional interface — so this
   * must be an explicit implementation, not a lambda.)
   */
  private static ProxyInterceptor stripIdentity() {
    return new ProxyInterceptor() {
      @Override
      public Future<ProxyResponse> handleProxyRequest(ProxyContext context) {
        MultiMap headers = context.request().headers();
        List<String> remove = new ArrayList<>();
        for (String name : headers.names()) {
          if (isPlatformCredential(name)) {
            remove.add(name);
          }
        }
        remove.forEach(headers::remove);
        return context.sendRequest();
      }
    };
  }

  /** Whether a header names this platform's own identity, in either spelling of the rule. */
  private static boolean isPlatformCredential(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.startsWith(RESERVED_PREFIX) || lower.equals("authorization");
  }

  /** The refusal: this request did not come through the platform's session gate. */
  private void refuse(RoutingContext rc) {
    respond(
        rc,
        403,
        "This editor is reached through the platform, and this request did not come that way.");
  }

  /**
   * A qits-branded page that refreshes itself until the editor answers — {@link ServiceProxyRoute}'s
   * splash, and deliberately the same one: what a reader has in front of them while a container
   * comes up should not depend on which of the two they opened.
   */
  private void splash(RoutingContext rc, String message) {
    page(rc, 200, message + "… this page refreshes automatically", "<meta http-equiv=\"refresh\""
        + " content=\"2\">");
  }

  /**
   * Errors answer HTML, not the JSON {@link ContainerProxyRoute} uses. The client here is a browser
   * that asked for a whole application and will render whatever comes back; a JSON envelope would be
   * shown to a person as its own source.
   */
  private void respond(RoutingContext rc, int status, String message) {
    page(rc, status, message, "");
  }

  private void page(RoutingContext rc, int status, String message, String head) {
    String html =
        "<!doctype html><html><head><title>qits editor</title>"
            + head
            + "<style>body{font-family:system-ui,sans-serif;display:flex;align-items:center;"
            + "justify-content:center;height:100vh;margin:0;color:#666}</style></head>"
            + "<body><p>"
            + message
            + "</p></body></html>";
    rc.response().setStatusCode(status).putHeader("Content-Type", "text/html").end(html);
  }
}
