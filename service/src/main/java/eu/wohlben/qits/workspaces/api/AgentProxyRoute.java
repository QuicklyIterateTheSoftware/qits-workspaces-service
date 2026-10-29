package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.api.DaemonForwarder.Resolved;
import eu.wohlben.qits.workspaces.control.AgentProxyPath;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.daemonhost.DaemonAgentBindingCheck;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The agent proxy (epic qits-1667): {@code /workspaces/agent/{agentId}/…} reaches the daemon of the
 * workspace the agent is placed in <em>now</em>, so a caller names the agent and never has to learn
 * — or chase — its workspace. A raw Vert.x route for the reason {@link ContainerProxyRoute} is one:
 * WebSocket upgrades and byte streams do not go through JAX-RS.
 *
 * <p><b>Unlike {@link ContainerProxyRoute}, this route maps its path.</b> Its few sub-paths are its
 * own address, not the daemon's, and each is mapped onto the daemon's path under {@link
 * ContainerProxyPath#base} of the agent's workspace — the address the daemon is told is its own. So
 * the daemon still only ever sees {@code /workspaces/container/{workspaceId}/…}:
 *
 * <ul>
 *   <li>{@code WS terminal} → {@code <base>terminal/commands/{commandId}}, RUNNING with a command
 *   <li>{@code WS chat} → {@code <base>chat/commands/{commandId}}, RUNNING with a command
 *   <li>{@code log?q} → {@code <base>commands/{commandId}/log?q}, any command (the last one of a
 *       YIELDED or STOPPED agent is fine, while its workspace is live)
 *   <li>{@code files[/…]?q}, {@code detection}, {@code component-map} → {@code
 *       <base>agent-worktrees/{agentId}/…}, a workspace
 * </ul>
 *
 * Anything else is 404 "No such agent route"; an agent with no workspace 409 "The agent is waiting
 * for a workspace"; a route that needs RUNNING, called while it is not, 409 "The agent has no
 * running session (runState X)". An unknown, non-UUID or REMOVED agent is 404. The forwarding itself
 * — tunnel, bearer, pinned authority, the hand-rolled upgrade — is {@link DaemonForwarder}'s,
 * shared with {@link ContainerProxyRoute}.
 *
 * <p><b>Who is let through.</b> An anonymous caller is 401 (and kept out before this runs by {@code
 * quarkus.http.auth.permission.agent-proxy}). {@code qits:system}, {@code qits:admin} and {@code
 * qits:admin-agent}: every agent, every route, every method. A {@code qits:agent} caller: every route
 * of its <em>own</em> agent — its subject ({@link DaemonAgentBindingCheck#subjectOf}) is the agent's
 * {@code commissioned_token_subject}; not the workspace's bound subject, which would let one agent
 * into every other agent of its slot. {@code qits:desk} and {@code qits:desk-admin}: the {@code GET}
 * routes (log, files, detection, component-map) of every agent, and no WebSocket. A desk token also
 * holds {@code qits:agent}, and is judged by the strongest rule it meets: its own agent (it has
 * none) else the desk's. Anyone else is 403 "Not permitted to reach this agent.", logged with
 * subject, roles and agent id — never a credential.
 */
@ApplicationScoped
public class AgentProxyRoute {

  private static final Logger LOG = Logger.getLogger(AgentProxyRoute.class);

  /** Every agent, every route, every method. */
  private static final List<String> ANY_AGENT_ROLES =
      List.of("qits:system", "qits:admin", "qits:admin-agent");

  /** Every agent, GET routes only. */
  private static final List<String> DESK_ROLES = List.of("qits:desk", "qits:desk-admin");

  /** Its own agent only. */
  private static final String AGENT_ROLE = "qits:agent";

  private static final String NOT_YOURS = "Not permitted to reach this agent.";

  private static final String NO_ROUTE = "No such agent route";

  private static final String NO_AGENT = "No agent here.";

  private static final String WAITING = "The agent is waiting for a workspace";

  @Inject DaemonForwarder forwarder;

  @Inject AgentWorktreeRepository agents;

  /** See {@code ContainerProxyRoute.rootPath}: the same key, read for the same reason. */
  @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
  String rootPath;

  private String rootPrefix;

  void init(@Observes Router router) {
    rootPrefix = RootPath.prefix(rootPath);
    router.route(AgentProxyPath.PREFIX + "*").handler(this::handle);
  }

  /** What a sub-path asks of the agent, and where it goes. */
  private enum Kind {
    /** {@code WS terminal}: RUNNING with a command. */
    TERMINAL(true, true),
    /** {@code WS chat}: RUNNING with a command. */
    CHAT(true, true),
    /** {@code log}: any command, on a live workspace. */
    LOG(false, false),
    /** {@code files[/…]}, {@code detection}, {@code component-map}: the agent's worktree. */
    WORKTREE(false, false);

    final boolean webSocket;
    final boolean needsRunning;

    Kind(boolean webSocket, boolean needsRunning) {
      this.webSocket = webSocket;
      this.needsRunning = needsRunning;
    }
  }

  /** A parsed sub-path: its kind, and the rest (raw) for the worktree routes. */
  private record Target(Kind kind, String rest, String query) {}

  private void handle(RoutingContext rc) {
    String path = rc.request().path();
    int start = rootPrefix.length() + AgentProxyPath.PREFIX.length();
    if (path.length() < start) {
      DaemonForwarder.respond(rc, 404, NO_AGENT);
      return;
    }
    String[] segments = path.substring(start).split("/", 2);
    UUID agentId;
    try {
      agentId = UUID.fromString(segments[0]);
    } catch (IllegalArgumentException notAnId) {
      // Not an agent at all: 404 without touching the database, as an unknown one answers.
      DaemonForwarder.respond(rc, 404, NO_AGENT);
      return;
    }
    Target target =
        targetOf(
            segments.length < 2 ? "" : segments[1],
            rc.request().query(),
            DaemonForwarder.isWebSocketUpgrade(rc.request()));
    if (target == null) {
      DaemonForwarder.respond(rc, 404, NO_ROUTE);
      return;
    }

    // Untouched while the caller is judged and the rows are read off the event loop; the forwarder
    // resumes it. Nothing goes on the wire, and no upgrade is answered, before the decision.
    rc.request().pause();
    QuarkusHttpUser.getSecurityIdentity(rc, null)
        .subscribe()
        .with(
            identity -> admit(rc, agentId, target, identity),
            failure -> DaemonForwarder.respond(rc, 401, "Authentication required."));
  }

  /** The route a sub-path names, or null for none. The WebSocket routes are upgrades only. */
  private static Target targetOf(String rest, String query, boolean upgrade) {
    switch (rest) {
      case "terminal":
        return upgrade ? new Target(Kind.TERMINAL, rest, query) : null;
      case "chat":
        return upgrade ? new Target(Kind.CHAT, rest, query) : null;
      case "log":
        return upgrade ? null : new Target(Kind.LOG, rest, query);
      case "files", "detection", "component-map":
        return upgrade ? null : new Target(Kind.WORKTREE, rest, query);
      default:
        if (!upgrade && rest.startsWith("files/")) {
          return new Target(Kind.WORKTREE, rest, query);
        }
        return null;
    }
  }

  /** What the caller's roles allow before the agent row is read. */
  private enum Standing {
    ANY,
    OWN_OR_DESK,
    NONE
  }

  private static Standing standingOf(SecurityIdentity identity) {
    if (ANY_AGENT_ROLES.stream().anyMatch(identity::hasRole)) {
      return Standing.ANY;
    }
    if (identity.hasRole(AGENT_ROLE) || DESK_ROLES.stream().anyMatch(identity::hasRole)) {
      return Standing.OWN_OR_DESK;
    }
    return Standing.NONE;
  }

  /** The outcome of the worker hop: a refusal or an answer, else where to forward. */
  private record Outcome(int status, String message, boolean refusal, Resolved resolved, String uri) {
    static Outcome answer(int status, String message) {
      return new Outcome(status, message, false, null, null);
    }

    static Outcome refused(int status) {
      return new Outcome(status, NOT_YOURS, true, null, null);
    }

    static Outcome forward(Resolved resolved, String uri) {
      return new Outcome(0, null, false, resolved, uri);
    }
  }

  private void admit(RoutingContext rc, UUID agentId, Target target, SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      refuse(rc, 401, "Authentication required.", identity, agentId);
      return;
    }
    Standing standing = standingOf(identity);
    if (standing == Standing.NONE) {
      refuse(rc, 403, NOT_YOURS, identity, agentId);
      return;
    }
    boolean deskMayRead =
        DESK_ROLES.stream().anyMatch(identity::hasRole)
            && !target.kind().webSocket
            && rc.request().method() == HttpMethod.GET;
    String subject =
        identity.hasRole(AGENT_ROLE) ? DaemonAgentBindingCheck.subjectOf(identity) : null;
    rc.vertx()
        .executeBlocking(
            () -> {
              Placement agent =
                  DbRetry.call("agent proxy lookup", () -> placementOf(agentId));
              if (agent == null) {
                return Outcome.answer(404, NO_AGENT);
              }
              if (standing != Standing.ANY) {
                boolean own =
                    subject != null
                        && !subject.isBlank()
                        && subject.equals(agent.tokenSubject());
                if (!own && !deskMayRead) {
                  return Outcome.refused(403);
                }
              }
              return place(agentId, agent, target);
            })
        .onFailure(e -> DaemonForwarder.respond(rc, 502, "Agent lookup failed."))
        .onSuccess(
            outcome -> {
              if (outcome.refusal()) {
                refuse(rc, outcome.status(), outcome.message(), identity, agentId);
              } else if (outcome.resolved() == null) {
                DaemonForwarder.respond(rc, outcome.status(), outcome.message());
              } else {
                forwarder.route(rc, outcome.resolved(), outcome.uri());
              }
            });
  }

  /** What this route reads of an agent row, copied out of its transaction. */
  private record Placement(
      Long workspaceId, AgentRunState runState, String commandId, String tokenSubject) {}

  /**
   * The agent's current placement, or null when there is no such agent or it is REMOVED. Its own
   * transaction, surrounded by the retry at the caller and never inside it — {@link
   * DaemonForwarder#resolve} carries the rule.
   */
  private Placement placementOf(UUID agentId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              AgentWorktree row = agents.findById(agentId);
              if (row == null || row.state == AgentState.REMOVED) {
                return null;
              }
              return new Placement(
                  row.workspaceId, row.runState, row.commandId, row.commissionedTokenSubject);
            });
  }

  /** The 409 an unplaced or not-running agent gets, else the daemon and the path to forward to. */
  private Outcome place(UUID agentId, Placement agent, Target target) {
    boolean running = agent.runState() == AgentRunState.RUNNING;
    if (target.kind().needsRunning
        && !running
        && agent.runState() != AgentRunState.QUEUED) {
      return notRunning(agent);
    }
    if (agent.workspaceId() == null) {
      return Outcome.answer(409, WAITING);
    }
    if ((target.kind().needsRunning && !running)
        || (target.kind() != Kind.WORKTREE && agent.commandId() == null)) {
      return notRunning(agent);
    }
    String base = ContainerProxyPath.base(agent.workspaceId());
    String query = target.query() == null ? "" : "?" + target.query();
    String uri =
        switch (target.kind()) {
          case TERMINAL -> base + "terminal/commands/" + agent.commandId() + query;
          case CHAT -> base + "chat/commands/" + agent.commandId() + query;
          case LOG -> base + "commands/" + agent.commandId() + "/log" + query;
          case WORKTREE -> base + "agent-worktrees/" + agentId + "/" + target.rest() + query;
        };
    return Outcome.forward(forwarder.resolve(agent.workspaceId()), uri);
  }

  private static Outcome notRunning(Placement agent) {
    return Outcome.answer(
        409, "The agent has no running session (runState " + agent.runState() + ")");
  }

  /** Answer a refusal and say who was refused — subject, roles, agent; never a credential. */
  private void refuse(
      RoutingContext rc, int status, String message, SecurityIdentity identity, UUID agentId) {
    LOG.infof(
        "Refused the agent proxy (%d): %s with roles %s for agent %s",
        status,
        identity == null ? null : DaemonAgentBindingCheck.subjectOf(identity),
        identity == null ? Set.of() : identity.getRoles(),
        agentId);
    DaemonForwarder.respond(rc, status, message);
  }
}
