package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.control.AgentDaemon;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host's own client for a workspace daemon's agent worktrees — {@link AgentDaemon} over the
 * wire ({@code agent-worktrees} on the daemon's HTTP API, qits-1152).
 *
 * <p><b>It reaches a daemon the way {@code ContainerProxyRoute} does: through its reverse tunnel,
 * and only that.</b> The tunnel's own client is used and never a shared one ({@link
 * WorkspaceTunnels} says why: an ephemeral port is reused, and a pooled connection behind a shared
 * client is how one workspace's request lands in another's container). The bearer is this
 * service's peer credential, and the authority is pinned to {@code localhost:<daemon port>}, as the
 * proxy pins it. The path is the full proxied one ({@link ContainerProxyPath#base}), because that
 * prefix is the daemon's own address.
 *
 * <p><b>Each exchange is composed and awaited once</b> (2026-09-08): awaiting the response and then
 * asking it for its body is two blocking steps with an event loop between them, and the body can
 * have been delivered and dropped by the second.
 *
 * <p>Bodies are hand-built {@code JsonObject}s, matching the daemon's own JSON: a handful of keys,
 * no databind registration for the native image.
 */
@ApplicationScoped
public class DaemonAgentClient implements AgentDaemon {

  private static final Logger LOG = Logger.getLogger(DaemonAgentClient.class);

  /**
   * Which surface asked; the daemon requires one. {@code ticket.dispatch} is the key written for
   * the agent a work item's dispatch starts (the vocabulary is the harness library's
   * {@code AgentSurface}, which this classpath does not carry).
   */
  static final String SURFACE = "ticket.dispatch";

  /**
   * Interactive mode: the harness's own TUI in a terminal, which a person can watch and type into.
   * The daemon holds a turn until the harness has started, and types it on one line (qits-1152).
   * The daemon still takes {@code CHAT}, the stream-json conversation.
   */
  static final String MODE = "INTERACTIVE";

  static final String BASE = "agent-worktrees";

  /** The daemon's 404 {@code message} for a path it does not serve, as opposed to an unknown agent. */
  static final String NO_SUCH_ENDPOINT = "No such endpoint";

  @Inject WorkspaceTunnels tunnels;

  @ConfigProperty(name = "qits.workspace.daemon-api-token", defaultValue = "qits-workspace-daemon")
  String daemonApiToken;

  /** One daemon call's bound; a start creates worktrees, so it is not short. */
  @ConfigProperty(name = "qits.workspace.agent-dispatch.request-timeout-ms", defaultValue = "30000")
  long requestTimeoutMs;

  @Override
  public boolean reachable(Long workspaceRowId) {
    return route(workspaceRowId) != null;
  }

  @Override
  public Started start(Long workspaceRowId, Start start) {
    Route route = route(workspaceRowId);
    if (route == null) {
      return Started.of(Outcome.UNREACHABLE, "the daemon is not connected");
    }
    JsonObject env = new JsonObject();
    if (start.env() != null) {
      for (Map.Entry<String, String> entry : start.env().entrySet()) {
        if (entry.getValue() != null) {
          env.put(entry.getKey(), entry.getValue());
        }
      }
    }
    JsonObject body =
        new JsonObject()
            .put("agentId", start.agentId())
            .put("workId", start.workId())
            .put("entityId", start.entityId())
            .put("wrapperBranch", start.wrapperBranch())
            .put("harness", start.harness())
            .put("env", env)
            .put("surface", SURFACE)
            .put("mode", MODE);
    if (start.sessionId() != null) {
      body.put("sessionId", start.sessionId());
    }
    if (start.instruction() != null) {
      body.put("instruction", start.instruction());
    }
    EntityFacts facts = start.facts();
    if (facts != null) {
      body.put("entityTitle", facts.title())
          .put("entityStatus", facts.status())
          .put("entityBlocked", facts.blocked());
    }
    if (start.blockSource() != null) {
      body.put("blockSource", start.blockSource());
    }
    Answer answer = send(route, HttpMethod.POST, BASE, body);
    if (answer == null) {
      return Started.of(Outcome.UNREACHABLE, "the daemon did not answer");
    }
    if (answer.status() == 409) {
      return Started.of(Outcome.REFUSED, answer.body());
    }
    if (answer.status() < 200 || answer.status() >= 300) {
      LOG.warnf(
          "workspace %s's daemon answered %s to starting agent %s: %s",
          workspaceRowId, Integer.valueOf(answer.status()), start.agentId(), answer.body());
      return Started.of(
          answer.status() >= 500 ? Outcome.UNREACHABLE : Outcome.REFUSED, answer.body());
    }
    JsonObject json = json(answer.body());
    return new Started(
        Outcome.OK,
        json == null ? null : blankToNull(json.getString("commandId")),
        json == null ? null : blankToNull(json.getString("sessionId")),
        json != null && Boolean.TRUE.equals(json.getBoolean("resumed")),
        null);
  }

  @Override
  public Outcome turn(Long workspaceRowId, String agentId, String text) {
    if (text == null || text.isBlank()) {
      return Outcome.REFUSED;
    }
    return simple(
        workspaceRowId, HttpMethod.POST, path(agentId, "turn"), new JsonObject().put("text", text));
  }

  @Override
  public Outcome entity(Long workspaceRowId, String agentId, EntityFacts facts, String blockSource) {
    JsonObject body =
        new JsonObject()
            .put("title", facts.title())
            .put("status", facts.status())
            .put("blocked", facts.blocked());
    if (blockSource != null) {
      body.put("blockSource", blockSource);
    }
    return simple(workspaceRowId, HttpMethod.POST, path(agentId, "entity"), body);
  }

  @Override
  public CleanupAnswer cleanupCheck(Long workspaceRowId, String agentId) {
    Route route = route(workspaceRowId);
    if (route == null) {
      return CleanupAnswer.unreachable("the daemon is not connected");
    }
    Answer answer = send(route, HttpMethod.GET, path(agentId, "cleanup-check"), null);
    if (answer == null) {
      return CleanupAnswer.unreachable("the daemon did not answer");
    }
    return cleanupAnswerOf(answer.status(), answer.body());
  }

  /**
   * A cleanup check's answer read (qits-1233). A 404 is the daemon saying it does not know the
   * agent — its {@code UnknownAgentException}, which is what a daemon that already removed the
   * agent answers — unless its body says the endpoint itself is missing. Any other answer that is
   * no check (a 5xx, another 4xx, a body that does not parse) is unreachable, with the status in
   * the detail. Package-private for its test.
   */
  static CleanupAnswer cleanupAnswerOf(int status, String body) {
    if (status == 404) {
      JsonObject error = json(body);
      if (error != null && NO_SUCH_ENDPOINT.equals(error.getString("message"))) {
        return CleanupAnswer.unreachable("the daemon has no cleanup check (404 No such endpoint)");
      }
      return CleanupAnswer.unknownAgent();
    }
    if (status != 200) {
      return CleanupAnswer.unreachable("the daemon answered " + status);
    }
    CleanupCheck check = cleanupCheckOf(json(body));
    return check == null
        ? CleanupAnswer.unreachable("the daemon's answer is no cleanup check")
        : CleanupAnswer.found(check);
  }

  /**
   * A refusal (409) is the daemon's cleanup check as its body; one that says {@code busy} is {@link
   * Outcome#BUSY}, not leftover work (qits-1233).
   */
  @Override
  public Outcome remove(Long workspaceRowId, String agentId, boolean force) {
    String path = path(agentId, null) + (force ? "?force=true" : "");
    Route route = route(workspaceRowId);
    if (route == null) {
      return Outcome.UNREACHABLE;
    }
    Answer answer = send(route, HttpMethod.DELETE, path, null);
    Outcome outcome = outcomeOf(workspaceRowId, HttpMethod.DELETE, path, answer);
    if (outcome == Outcome.REFUSED && answer.status() == 409) {
      CleanupCheck refusal = cleanupCheckOf(json(answer.body()));
      if (refusal != null && refusal.busy()) {
        return Outcome.BUSY;
      }
    }
    return outcome;
  }

  /** The cleanup check's body, or null when it is not one. Package-private for its test. */
  static CleanupCheck cleanupCheckOf(JsonObject json) {
    if (json == null || json.getValue("clean") == null) {
      return null;
    }
    List<String> dirty = new ArrayList<>();
    JsonArray dirtyArray = json.getJsonArray("dirty");
    if (dirtyArray != null) {
      for (int i = 0; i < dirtyArray.size(); i++) {
        dirty.add(String.valueOf(dirtyArray.getValue(i)));
      }
    }
    List<CleanupCheck.Unpushed> unpushed = new ArrayList<>();
    JsonArray unpushedArray = json.getJsonArray("unpushed");
    if (unpushedArray != null) {
      for (int i = 0; i < unpushedArray.size(); i++) {
        JsonObject entry = unpushedArray.getJsonObject(i);
        if (entry == null) {
          continue;
        }
        Integer commits = entry.getInteger("commits");
        unpushed.add(
            new CleanupCheck.Unpushed(
                entry.getString("repository"),
                entry.getString("branch"),
                commits == null ? 0 : commits));
      }
    }
    // busy (qits-1233) is optional: a daemon that predates it is never busy.
    return new CleanupCheck(
        Boolean.TRUE.equals(json.getBoolean("clean")),
        dirty,
        unpushed,
        Boolean.TRUE.equals(json.getBoolean("busy")));
  }

  private Outcome simple(Long workspaceRowId, HttpMethod method, String path, JsonObject body) {
    Route route = route(workspaceRowId);
    if (route == null) {
      return Outcome.UNREACHABLE;
    }
    return outcomeOf(workspaceRowId, method, path, send(route, method, path, body));
  }

  private static Outcome outcomeOf(
      Long workspaceRowId, HttpMethod method, String path, Answer answer) {
    if (answer == null) {
      return Outcome.UNREACHABLE;
    }
    int status = answer.status();
    if (status >= 200 && status < 300) {
      return Outcome.OK;
    }
    if (status == 404) {
      return Outcome.UNKNOWN_AGENT;
    }
    if (status >= 500) {
      // Shown (qits-1233): a remove the daemon failed to confirm stalls the cleanup sweep.
      LOG.warnf(
          "workspace %s's daemon answered %s to %s %s: %s",
          workspaceRowId, Integer.valueOf(status), method, path, answer.body());
      return Outcome.UNREACHABLE;
    }
    LOG.debugf(
        "workspace %s's daemon refused %s %s with %s: %s",
        workspaceRowId, method, path, Integer.valueOf(status), answer.body());
    return Outcome.REFUSED;
  }

  private static String path(String agentId, String verb) {
    return BASE + "/" + agentId + (verb == null ? "" : "/" + verb);
  }

  private record Route(Long workspaceRowId, HttpClient client, String host, int port) {}

  private record Answer(int status, String body) {}

  private Route route(Long workspaceRowId) {
    if (workspaceRowId == null) {
      return null;
    }
    Optional<WorkspaceTunnels.TunnelOrigin> tunnel = tunnels.originFor(workspaceRowId);
    if (tunnel.isEmpty()) {
      LOG.debugf("workspace %s's daemon is not connected", workspaceRowId);
      return null;
    }
    return new Route(workspaceRowId, tunnel.get().client(), "127.0.0.1", tunnel.get().port());
  }

  private Answer send(Route route, HttpMethod method, String path, JsonObject body) {
    RequestOptions options =
        new RequestOptions()
            .setMethod(method)
            .setServer(SocketAddress.inetSocketAddress(route.port(), route.host()))
            .setHost("localhost")
            .setPort(Integer.valueOf(ContainerProxyPath.DAEMON_API_PORT))
            .setURI(ContainerProxyPath.base(route.workspaceRowId()) + path)
            .setTimeout(requestTimeoutMs);
    try {
      return await(
          route
              .client()
              .request(options)
              .compose(
                  request -> {
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
                          .map(buffer -> new Answer(response.statusCode(), buffer.toString()))),
          requestTimeoutMs);
    } catch (RuntimeException e) {
      // Shown, without the trace (qits-1233): a lost answer to a remove is how an agent's row
      // outlived its worktree. The trace stays at debug.
      LOG.infof(
          "workspace %s's daemon did not answer %s %s: %s",
          route.workspaceRowId(), method, path, rootMessage(e));
      LOG.debugf(
          e, "workspace %s's daemon did not answer %s %s", route.workspaceRowId(), method, path);
      return null;
    }
  }

  private static JsonObject json(String body) {
    try {
      return body == null || body.isBlank() ? null : new JsonObject(body);
    } catch (RuntimeException notJson) {
      return null;
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private <T> T await(Future<T> future, long timeoutMs) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling a workspace daemon", e);
    } catch (Exception e) {
      throw new IllegalStateException("a workspace daemon call failed", e);
    }
  }
}
