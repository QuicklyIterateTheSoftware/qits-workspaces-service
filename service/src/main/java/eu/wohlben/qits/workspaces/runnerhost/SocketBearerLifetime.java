package eu.wohlben.qits.workspaces.runnerhost;

import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the workspace runner socket open past the bearer it was opened with.
 *
 * <p><b>Copied from qits-ci-service's {@code runnerhost/SocketBearerLifetime}</b>, where it was
 * written for qits-545, and kept as close to it as the one path allows; there is no shared server
 * module to take it from (qits-771 decided none). What it undoes, in that class's words: quarkus-oidc
 * stamps every identity it builds from a bearer with {@code quarkus.identity.expire-time} — the
 * token's {@code exp} — and websockets-next arms a timer on that attribute for every connection it
 * admits and closes it "Authentication expired" when it fires; there is no key to turn that off. A
 * runner mints a {@code client_credentials} token when it dials, and qits-idp gives it an hour, so
 * every CI runner lost its socket exactly one hour after each connect until this existed (measured
 * 2026-09-29, an hour to the second each time).
 *
 * <p><b>Why dropping the attribute is right rather than refreshing the token.</b> The socket is
 * authenticated at the upgrade and nowhere after it, by design: a runner is cut off by its deletion
 * ({@code WorkspaceRunnerRegistry.deleted} — a {@code retire} and a 1008 close, whatever its bearer
 * says) and refused at its next dial by the missing row. It has no fresh token to present
 * mid-connection. So the attribute is removed, for this one path only, and every other request
 * keeps its expiry exactly as quarkus-oidc stated it — the daemon control socket included, which
 * this class deliberately does not cover.
 */
@ApplicationScoped
public class SocketBearerLifetime implements SecurityIdentityAugmentor {

  /** quarkus-oidc's {@code OidcUtils.QUARKUS_IDENTITY_EXPIRE_TIME}, which websockets-next reads. */
  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  /** The upgrades whose connections outlive their bearer. */
  static final Set<String> SOCKET_PATHS = Set.of(WorkspacesRunnerProtocol.SOCKET_PATH);

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity,
      AuthenticationRequestContext context,
      Map<String, Object> attributes) {
    RoutingContext request = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String path = request == null ? null : request.normalizedPath();
    return Uni.createFrom().item(forPath(identity, path));
  }

  /** The identity as a connection on {@code path} should hold it. */
  static SecurityIdentity forPath(SecurityIdentity identity, String path) {
    if (path == null
        || !SOCKET_PATHS.contains(path)
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null) {
      return identity;
    }
    Map<String, Object> kept = new HashMap<>(identity.getAttributes());
    kept.remove(EXPIRE_TIME);
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(identity.getPrincipal())
        .addRoles(identity.getRoles())
        .addCredentials(identity.getCredentials())
        .addPermissions(identity.getPermissions())
        .addAttributes(kept)
        .addPermissionChecker(identity::checkPermission)
        .build();
  }
}
