package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Principal;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

/**
 * Binds an agent's control socket to its own workspace.
 *
 * <p>Both control sockets ({@link DaemonControlSocket}, {@link LegacyDaemonControlSocket}) take the
 * workspace from the path. {@code qits:system} callers are trusted to name any workspace, as today.
 * A {@code qits:agent} caller is not (phase 4 of principal-bound-git-refs-plan.md): the token's
 * {@code sub} must be the idp client commissioned for that workspace's container — the pair the
 * workspace row holds in {@code commissioned_client_id}. Anything else is refused with 403 before
 * the socket opens.
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
    return DaemonControlSocket.class.getName().equals(endpointId)
        || LegacyDaemonControlSocket.class.getName().equals(endpointId);
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
              boolean legacy = LegacyDaemonControlSocket.class.getName().equals(context.endpointId());
              String segment = context.pathParam(legacy ? "workspaceId" : "id");
              String caller = subjectOf(identity);
              return Uni.createFrom()
                  .item(() -> commissionedClientOf(legacy, segment))
                  .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                  .map(
                      held -> {
                        if (caller != null && caller.equals(held)) {
                          return CheckResult.permitUpgradeSync();
                        }
                        LOG.warnf(
                            "Refused an agent control socket: %s is not the client commissioned for"
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
  private static String subjectOf(SecurityIdentity identity) {
    Principal principal = identity.getPrincipal();
    if (principal instanceof JsonWebToken jwt && jwt.getSubject() != null) {
      return jwt.getSubject();
    }
    return principal == null ? null : principal.getName();
  }

  /**
   * The client commissioned for the workspace the path names, or null — no such ACTIVE workspace,
   * no commission, a segment that is not an id, or a legacy label more than one workspace carries.
   * Null never matches a caller.
   */
  String commissionedClientOf(boolean legacy, String segment) {
    if (segment == null || segment.isBlank()) {
      return null;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              if (legacy) {
                List<Workspace> matches =
                    workspaces.list(
                        "workspaceId = ?1 and status = ?2", segment, WorkspaceStatus.ACTIVE);
                return matches.size() == 1 ? matches.get(0).commissionedClientId : null;
              }
              Long id;
              try {
                id = Long.valueOf(segment);
              } catch (NumberFormatException notAnId) {
                return null;
              }
              return workspaces.findActiveById(id).map(w -> w.commissionedClientId).orElse(null);
            });
  }
}
