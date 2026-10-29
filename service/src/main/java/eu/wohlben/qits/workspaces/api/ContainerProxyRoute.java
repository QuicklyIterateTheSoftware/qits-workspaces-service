package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.workspaces.control.ContainerProxyPath;
import eu.wohlben.qits.workspaces.api.DaemonForwarder.Resolved;
import eu.wohlben.qits.workspaces.control.DaemonProxyTargets;
import eu.wohlben.qits.workspaces.daemonhost.DaemonAgentBindingCheck;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * {@code DaemonForwarder.proxyUpgrade} rather than by {@code vertx-http-proxy}, because the library's upgrade path
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
 * <p><b>Who is let through, because this route opens a shell and writes files.</b> The read doors
 * take every {@code qits:agent} caller; this one cannot, since what lies behind it is a terminal
 * and the file browser of somebody's container. An anonymous caller never reaches {@link #handle}
 * ({@code quarkus.http.auth.permission.container-proxy} demands an authenticated identity, upgrades
 * included) and is answered 401 here as well. {@code qits:system}, {@code qits:admin} and {@code
 * qits:admin-agent} may reach any workspace, whatever else they hold. A {@code qits:agent} caller
 * reaches only a workspace it is bound to: its subject must be the row's {@code
 * Workspace.boundSubject} — the binding {@code DaemonAgentBindingCheck} holds on the control socket
 * and {@code DaemonStreamRoute} on the dial-back — or the token subject of a live agent placed in
 * that workspace, because qits-1152 gave each agent worktree a token of its own. Any other caller
 * ({@code qits:ci-run}, a runner, no role) is 403. The identity is whatever Quarkus built (forward
 * auth for a person, a validated bearer for a machine); this class reads no header to decide it.
 * Which workspace a request is scoped to, and that it is ACTIVE, is still {@link
 * DaemonProxyTargets}'.
 */
@ApplicationScoped
public class ContainerProxyRoute {

  private static final Logger LOG = Logger.getLogger(ContainerProxyRoute.class);

  @Inject DaemonForwarder forwarder;

  @Inject WorkspaceRepository workspaceRows;

  @Inject AgentWorktreeRepository agents;

  /**
   * Roles that reach any workspace's daemon, even beside {@code qits:agent}. Never {@code
   * qits:desk} (qits-1596): this route is a shell and a file writer inside the container, where the
   * agents' push-scoped tokens (and on an admin row the docker socket) live, so it would hand the
   * delivery desk a way to commit and push. A desk token holds {@code qits:agent} too and is judged
   * as one: its subject is bound to no workspace, so it is 403.
   */
  private static final List<String> ANY_WORKSPACE_ROLES =
      List.of("qits:system", "qits:admin", "qits:admin-agent");

  /** The role that reaches only a workspace it is bound to. */
  private static final String AGENT_ROLE = "qits:agent";

  private static final String NOT_YOURS = "Not permitted to reach this workspace.";

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

    // The request stays untouched while the caller is judged and the lookup runs off the event loop
    // — both read rows, so they need a worker thread and a transaction; the proxy resumes it when
    // forwarding. Nothing goes on the wire, and no upgrade is answered, before the decision.
    rc.request().pause();
    QuarkusHttpUser.getSecurityIdentity(rc, null)
        .subscribe()
        .with(
            identity -> admit(rc, workspaceId, identity),
            failure -> respond(rc, 401, "Authentication required."));
  }

  /**
   * Decide who may reach this workspace's daemon, then look the daemon up. The class javadoc says
   * who is admitted; only the agent binding needs the database, and it runs inside the same worker
   * hop as {@link DaemonForwarder#resolve}, before it.
   */
  private void admit(RoutingContext rc, Long workspaceId, SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous()) {
      refuse(rc, 401, "Authentication required.", identity, workspaceId);
      return;
    }
    String boundSubject;
    if (ANY_WORKSPACE_ROLES.stream().anyMatch(identity::hasRole)) {
      boundSubject = null;
    } else if (identity.hasRole(AGENT_ROLE)) {
      boundSubject = DaemonAgentBindingCheck.subjectOf(identity);
      if (boundSubject == null || boundSubject.isBlank()) {
        refuse(rc, 403, NOT_YOURS, identity, workspaceId);
        return;
      }
    } else {
      refuse(rc, 403, NOT_YOURS, identity, workspaceId);
      return;
    }
    String subject = boundSubject;
    rc.vertx()
        .executeBlocking(
            () -> {
              if (subject != null) {
                Binding binding =
                    DbRetry.call(
                        "workspace daemon proxy binding", () -> bindingOf(workspaceId, subject));
                if (binding == Binding.NO_WORKSPACE) {
                  return Resolved.absent(DaemonProxyTargets.Reachability.NO_WORKSPACE);
                }
                if (binding == Binding.NOT_BOUND) {
                  return Resolved.REFUSED;
                }
              }
              return forwarder.resolve(workspaceId);
            })
        .onFailure(e -> respond(rc, 502, "Workspace lookup failed."))
        .onSuccess(
            resolved -> {
              if (resolved == Resolved.REFUSED) {
                refuse(rc, 403, NOT_YOURS, identity, workspaceId);
              } else {
                route(rc, resolved);
              }
            });
  }

  /** What an agent's subject is to a workspace. */
  private enum Binding {
    BOUND,
    NOT_BOUND,
    NO_WORKSPACE
  }

  /**
   * Whether {@code subject} is bound to the workspace: the ACTIVE row's own bound subject, or the
   * token of a live agent placed in it. An unknown workspace stays the 404 it always was, so an
   * agent learns nothing here that an admin would not. Its own transaction, surrounded by the retry
   * at the caller and never inside it — {@link DaemonForwarder#resolve} carries the rule.
   */
  private Binding bindingOf(Long workspaceId, String subject) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Optional<Workspace> row = workspaceRows.findActiveById(workspaceId);
              if (row.isEmpty()) {
                return Binding.NO_WORKSPACE;
              }
              if (subject.equals(row.get().boundSubject())
                  || agents.existsLiveInWorkspaceWithTokenSubject(workspaceId, subject)) {
                return Binding.BOUND;
              }
              return Binding.NOT_BOUND;
            });
  }

  /** Answer a refusal and say who was refused — subject, roles, workspace; never a credential. */
  private void refuse(
      RoutingContext rc, int status, String message, SecurityIdentity identity, Long workspaceId) {
    LOG.infof(
        "Refused the workspace daemon proxy (%d): %s with roles %s for workspace %d",
        status,
        identity == null ? null : DaemonAgentBindingCheck.subjectOf(identity),
        identity == null ? Set.of() : identity.getRoles(),
        workspaceId);
    respond(rc, status, message);
  }

  private void route(RoutingContext rc, Resolved resolved) {
    // Verbatim: the daemon receives the inbound URI, prefix and query included. See the class
    // javadoc for why this route rewrites nothing.
    forwarder.route(rc, resolved, rc.request().uri());
  }

  private void respond(RoutingContext rc, int status, String message) {
    DaemonForwarder.respond(rc, status, message);
  }
}
