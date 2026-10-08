package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Principal;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

/**
 * Binds an agent's control socket to its own workspace.
 *
 * <p>The control socket ({@link DaemonControlSocket}) takes the workspace from the path. {@code qits:system} callers are trusted to name any workspace, as today.
 * A {@code qits:agent} caller is not (phase 4 of principal-bound-git-refs-plan.md): the token's
 * {@code sub} must be the identity bound to that workspace's container: for a DIRECT row the idp
 * client commissioned for it ({@code commissioned_client_id}), and for a RUNNER row the subject of
 * its workspace token ({@code commissioned_token_subject}, qits-625 — the {@code sub} the edge puts
 * on the JWT it mints for the token). Anything else is refused with 403 before the socket opens: a
 * RUNNER row's client id never matches, because it holds none, and another row's token never does.
 * (The pre-id label path, {@code LegacyDaemonControlSocket}, is gone with its label branch here:
 * qits-780, after a week with no daemon dialling it.)
 *
 * <p>A caller that holds both roles is treated as {@code qits:system}: agents switch to their own
 * role later, and until then a workspace container still presents the owner's roles.
 *
 * <p>An upgrade check and not a test in {@code @OnOpen}, because only a check can refuse the upgrade
 * itself. {@code @OnOpen} runs after the 101, where the only answer left is to close a socket that
 * was already let in.
 */
@ApplicationScoped
public class DaemonAgentBindingCheck implements HttpUpgradeCheck {

  private static final Logger LOG = Logger.getLogger(DaemonAgentBindingCheck.class);

  static final String SYSTEM_ROLE = "qits:system";

  static final String AGENT_ROLE = "qits:agent";

  @Inject WorkspaceRepository workspaces;

  @Override
  public boolean appliesTo(String endpointId) {
    return DaemonControlSocket.class.getName().equals(endpointId);
  }

  @Override
  public Uni<CheckResult> perform(HttpUpgradeContext context) {
    return context
        .securityIdentity()
        .chain(
            identity -> {
              if (!isAgentOnly(identity)) {
                // qits:system keeps today's behaviour; a caller with neither role is the endpoint's
                // own @RolesAllowed to refuse.
                return CheckResult.permitUpgrade();
              }
              String segment = context.pathParam("id");
              String caller = subjectOf(identity);
              return Uni.createFrom()
                  .item(() -> boundSubjectOf(segment))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .map(
                      held -> {
                        if (caller != null && caller.equals(held)) {
                          return CheckResult.permitUpgradeSync();
                        }
                        LOG.warnf(
                            "Refused an agent control socket: %s is not the identity bound to"
                                + " workspace '%s'",
                            caller, segment);
                        return CheckResult.rejectUpgradeSync(403);
                      });
            });
  }

  private static boolean isAgentOnly(SecurityIdentity identity) {
    return identity != null && identity.hasRole(AGENT_ROLE) && !identity.hasRole(SYSTEM_ROLE);
  }

  /** The token's {@code sub}; the principal's name when the identity is not a JWT. */
  static String subjectOf(SecurityIdentity identity) {
    Principal principal = identity.getPrincipal();
    if (principal instanceof JsonWebToken jwt && jwt.getSubject() != null) {
      return jwt.getSubject();
    }
    return principal == null ? null : principal.getName();
  }

  /**
   * The subject an agent must present for the workspace the path names ({@code
   * Workspace.boundSubject}): its token subject when the row holds a workspace token — every RUNNER
   * row, and a DIRECT admin or editor row on an edge plane (qits-1084) — else its commissioned
   * client. Decided by what the row holds, never by its placement. Null — no such ACTIVE workspace,
   * no credential, or a segment that is not an id — never matches a caller.
   */
  String boundSubjectOf(String segment) {
    if (segment == null || segment.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              Long id;
              try {
                id = Long.valueOf(segment);
              } catch (NumberFormatException notAnId) {
                return null;
              }
              return workspaces.findActiveById(id).map(w -> w.boundSubject()).orElse(null);
            });
  }
}
