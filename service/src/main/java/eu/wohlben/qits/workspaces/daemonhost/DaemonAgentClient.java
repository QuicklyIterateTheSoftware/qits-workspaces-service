package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.control.ProxyOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceAgentLauncher;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host's own client for a workspace-daemon's coding-agent surface — {@link
 * WorkspaceAgentLauncher} over the wire.
 *
 * <p><b>It reaches a daemon the way {@code ContainerProxyRoute} does, and nothing about that is
 * copied for tidiness.</b> The resolution is the same two branches in the same order — the reverse
 * tunnel when the daemon serves one, the container's own address otherwise — because those two are
 * strictly complementary and getting the order wrong means dialling a port a modern daemon has
 * stopped listening on. The tunnel's client is used and never a shared one, for the reason {@link
 * WorkspaceTunnels} spells out at length: an ephemeral port is reused, and a pooled connection
 * behind a shared client is how one workspace's request lands in another's container. The bearer is
 * this service's own peer credential, set rather than forwarded, because there is nobody to forward
 * one from — the caller here is a scheduler thread. And the authority is pinned to {@code
 * localhost:<daemon port>} exactly as the proxy's {@code hostRewrite} pins it, so the daemon cannot
 * tell whether a request arrived through the tunnel, through the container's address, or from the
 * host at all — and must not be able to.
 *
 * <p><b>It lives beside the tunnel rather than in {@code api/}.</b> Nothing here serves a route:
 * this is the host talking to a container, which is what {@code daemonhost} is. {@code
 * ContainerProxyRoute} is in {@code api/} because it is a route a browser hits; this has no caller
 * outside this process.
 *
 * <p><b>Bodies are hand-built {@code JsonObject}s and read the same way</b>, matching the daemon's
 * own {@code CommandJson}: two keys out and three read, against a native image that would otherwise
 * need a databind registration for a shape this small.
 *
 * <p><b>No {@code DbRetry} around the row lookup</b>, unlike the proxy's. That wrap is placement-
 * sensitive and deliberately rare, and it exists there because a browser is holding a request open
 * and a blip costs a live workspace its file browser. Here the caller is a poll: a lookup that fails
 * during a postgres cutover reads as UNREACHABLE, and the next tick asks again.
 */
@ApplicationScoped
public class DaemonAgentClient implements WorkspaceAgentLauncher {

  private static final Logger LOG = Logger.getLogger(DaemonAgentClient.class);

  /**
   * The MCP scope every launch this service makes asks for. {@code ACTIONS} fails with an
   * explanation — no service in the split serves that server — so there is one value to send.
   */
  private static final String SCOPE = "REPOSITORY";

  /** Chat mode: the stream-json conversation, which is what a headless dispatch can drive. */
  private static final String MODE = "CHAT";

  @Inject Vertx vertx;

  @Inject DaemonProxyTargets targets;

  @Inject WorkspaceTunnels tunnels;

  /** The bearer the daemon requires; the same value {@code WorkspaceContainerFactory} injects. */
  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /** Not where we connect — the authority we present. See {@code ContainerProxyRoute}'s twin. */
  @ConfigProperty(name = "qits.workspace.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /**
   * How long one call to a daemon may take. Short: both calls are local to the host or one hop down
   * a loopback tunnel, and the caller polls — a request that hangs is worse than one that is retried
   * two seconds later.
   */
  @ConfigProperty(
      name = "qits.workspace.agent-dispatch.request-timeout-ms",
      defaultValue = "10000")
  long requestTimeoutMs;

  /**
   * The direct branch's client; the tunnel branch must use the tunnel's own.
   *
   * <p><b>Keep-alive is off, deliberately.</b> A pooled connection outlives the container it was
   * opened to, and a workspace container is stopped, recreated and replaced under a name and an
   * address that are then reused — which is the hazard {@link WorkspaceTunnels} refuses to share a
   * client over, one layer out. {@code ContainerProxyRoute} can afford a pool because it is serving
   * a browser's stream of requests; this client makes one call every couple of seconds per
   * workspace, so a pool buys nothing and the connection it would hold open is one that can only go
   * stale. A stale one costs the whole request timeout before the caller learns anything.
   */
  private HttpClient directClient;

  @PostConstruct
  void open() {
    directClient = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(false));
  }

  @PreDestroy
  void close() {
    if (directClient != null) {
      directClient.close();
    }
  }

  /**
   * {@code GET /commands?status=RUNNING} — one question, answered by whether any of them is an
   * agent's.
   *
   * <p><b>A CHAT command counts as an agent run even with no session lineage yet.</b> {@code
   * agentSessions} is the lineage the SPA reads to badge a run as agent-driven, and it is the right
   * signal for a run that has been going for a while — but it is populated from the harness's first
   * {@code SessionStart}, which arrives some seconds after the launch. Reading lineage alone would
   * leave a window in which a just-launched agent looks like nothing at all, and a re-press landing
   * in that window would put a second agent on the same checkout. The kind is known at launch, so
   * it closes the window; the lineage is kept beside it for a run whose kind an older daemon does
   * not report.
   */
  @Override
  public AgentState agentState(Long workspaceRowId) {
    Route route = route(workspaceRowId);
    if (route == null) {
      return AgentState.UNREACHABLE;
    }
    Answer answer = send(route, HttpMethod.GET, "commands?status=RUNNING", null);
    if (answer == null || answer.status() != 200) {
      return AgentState.UNREACHABLE;
    }
    return anyAgentRunning(answer.body()) ? AgentState.RUNNING : AgentState.IDLE;
  }

  /**
   * {@code POST /agents} — the launch, with the instruction as the seed turn.
   *
   * <p><b>{@code deliverTaskPrompt} is false and must stay false.</b> True seeds the session with an
   * instruction to fetch the real prompt through an MCP tool named {@code taskPrompt}, and that tool
   * is implemented nowhere on the platform — the agent would be told to call something that does not
   * exist and would sit there. The SPA's own client carries the same rule in the same words; this is
   * the second caller of that API and it makes the same promise.
   */
  @Override
  public boolean launch(Long workspaceRowId, String instruction) {
    Route route = route(workspaceRowId);
    if (route == null) {
      return false;
    }
    JsonObject body =
        new JsonObject()
            .put("scope", SCOPE)
            .put("mode", MODE)
            .put("initialContext", instruction == null ? "" : instruction)
            .put("deliverTaskPrompt", false);
    Answer answer = send(route, HttpMethod.POST, "agents", body);
    if (answer == null) {
      return false;
    }
    if (answer.status() < 200 || answer.status() >= 300) {
      LOG.warnf(
          "workspace %s's daemon answered %s to an agent launch",
          workspaceRowId, Integer.valueOf(answer.status()));
      return false;
    }
    return true;
  }

  /** Where a daemon is and which client may target it. Null when it is not reachable at all. */
  private record Route(Long workspaceRowId, HttpClient client, String host, int port) {}

  /** One answered request. */
  private record Answer(int status, String body) {}

  /**
   * The proxy's resolution, minus the four ways it distinguishes an absence: this caller acts the
   * same on all of them — it waits and asks again.
   */
  private Route route(Long workspaceRowId) {
    Optional<WorkspaceTunnels.TunnelOrigin> tunnel = tunnels.originFor(workspaceRowId);
    if (tunnel.isPresent()) {
      return new Route(
          workspaceRowId, tunnel.get().client(), "127.0.0.1", tunnel.get().port());
    }
    DaemonProxyTargets.DaemonTarget target;
    try {
      target = targets.resolve(workspaceRowId);
    } catch (RuntimeException e) {
      LOG.debugf(e, "could not resolve workspace %s's daemon", workspaceRowId);
      return null;
    }
    if (target.reachability() != DaemonProxyTargets.Reachability.READY) {
      return null;
    }
    ProxyOrigin origin = target.origin();
    return new Route(workspaceRowId, directClient, origin.host(), origin.port());
  }

  /**
   * One request to a daemon, blocking.
   *
   * <p>{@code setServer} is where we connect and {@code setHost}/{@code setPort} are what we claim
   * to be calling — which is how the authority stays {@code localhost:<daemon port>} whether the
   * connection went to the container's address or to a loopback tunnel port. The path is the FULL
   * proxied one ({@link ContainerProxyPath#base}), because that prefix is the daemon's own address:
   * {@code WorkspaceContainerFactory} injected it at container creation, and the proxy forwards
   * paths verbatim for exactly this reason. A client that posted a bare {@code /agents} would 404
   * against every container this service ever made.
   *
   * <p><b>The whole exchange is composed and awaited ONCE, and that is not style.</b> Awaiting the
   * response and then asking it for its body is two blocking steps with an event loop running
   * between them — and by the time the second one is reached the response may already have been
   * delivered and dropped, so {@code body()} never completes and the call sits on its timeout
   * instead of answering. Measured here on 2026-09-08, as a request that took exactly {@code
   * requestTimeoutMs} and then reported the daemon unreachable while the daemon had in fact
   * answered. Composing keeps every step on the event loop and leaves one thing to block on.
   *
   * <p>Blocking, so it must be called off the event loop. Its callers are the dispatch door's
   * request thread and its wait thread; neither is one.
   */
  private Answer send(Route route, HttpMethod method, String path, JsonObject body) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(method)
            .setServer(SocketAddress.inetSocketAddress(route.port(), route.host()))
            .setHost("localhost")
            .setPort(Integer.valueOf(daemonApiPort))
            .setURI(ContainerProxyPath.base(route.workspaceRowId()) + path)
            .setTimeout(requestTimeoutMs);
    try {
      return await(
          route
              .client()
              .request(options)
              .compose(
                  request -> {
                    // Peer authentication between qits and the container: set, never forwarded.
                    // There is nothing to forward here anyway — no caller is holding this open.
                    request.putHeader("Authorization", "Bearer " + daemonApiToken);
                    if (body == null) {
                      return request.send();
                    }
                    request.putHeader("Content-Type", "application/json");
                    return request.send(body.encode());
                  })
              .compose(
                  response ->
                      response
                          .body()
                          .map(buffer -> new Answer(response.statusCode(), buffer.toString()))));
    } catch (RuntimeException e) {
      LOG.debugf(
          e, "workspace %s's daemon did not answer %s %s", route.workspaceRowId(), method, path);
      return null;
    }
  }

  /** Whether the daemon's running-command list holds one that an agent is driving. */
  private static boolean anyAgentRunning(String body) {
    JsonArray entries;
    try {
      entries = new JsonObject(body).getJsonArray("entries");
    } catch (RuntimeException notJson) {
      return false;
    }
    if (entries == null) {
      return false;
    }
    for (int i = 0; i < entries.size(); i++) {
      JsonObject entry = entries.getJsonObject(i);
      JsonObject command = entry == null ? null : entry.getJsonObject("command");
      if (command == null || !"RUNNING".equals(command.getString("status"))) {
        continue;
      }
      JsonArray sessions = command.getJsonArray("agentSessions");
      if ("CHAT".equals(command.getString("kind")) || (sessions != null && !sessions.isEmpty())) {
        return true;
      }
    }
    return false;
  }

  private <T> T await(Future<T> future) {
    try {
      return future
          .toCompletionStage()
          .toCompletableFuture()
          .get(requestTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling a workspace daemon", e);
    } catch (Exception e) {
      throw new IllegalStateException("a workspace daemon call failed", e);
    }
  }
}
