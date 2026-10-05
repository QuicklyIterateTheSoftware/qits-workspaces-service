package eu.wohlben.qits.workspaces.daemonhost;

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
import java.util.regex.Pattern;

/**
 * Keeps a RUNNER workspace's daemon control socket open past the bearer it was opened with
 * (qits-625, qits-812).
 *
 * <p><b>A copy in shape of qits-ci-service's {@code runnerhost/SocketBearerLifetime}</b> (qits-545),
 * as this service's own {@code runnerhost.SocketBearerLifetime} is for the runner socket. What it
 * undoes, in that class's words: quarkus-oidc stamps every identity it builds from a bearer with
 * {@code quarkus.identity.expire-time} — the token's {@code exp} — and websockets-next arms a timer
 * on that attribute for every connection it admits and closes it "Authentication expired" when it
 * fires; there is no key to turn that off. A RUNNER row's daemon dials through the edge with its
 * workspace {@code qits_tok_}, and the edge forwards that as a JWT that lives 300 seconds — so with
 * the attribute in place no runner-placed daemon could hold its socket for longer than five
 * minutes.
 *
 * <p><b>Why dropping the attribute is right rather than refreshing the token.</b> The socket is
 * authenticated at the upgrade and nowhere after it, by design ({@link DaemonAgentBindingCheck}
 * binds the caller to the row's token subject there). The daemon has no fresh JWT to present
 * mid-connection — the JWT is the edge's, not its own. What cuts a RUNNER daemon off is the row's
 * token: delete-container, recreate and resolution delete it, and the next dial is refused at the
 * edge.
 *
 * <p><b>Only {@code /workspaces/daemon/{id}}, and only a token subject ({@code tok-…}).</b> Exactly
 * one path segment after the prefix, so the tunnel's dial-back ({@code /workspaces/daemon/stream/…},
 * a one-shot upgrade) and every other request keep their expiry. And only an identity whose subject
 * is a token's: a DIRECT daemon presents its commissioned client's hour-long JWT and keeps today's
 * lifetime exactly — excluded on purpose, because the DIRECT path is invariant.
 */
@ApplicationScoped
public class DaemonSocketBearerLifetime implements SecurityIdentityAugmentor {

  /** quarkus-oidc's {@code OidcUtils.QUARKUS_IDENTITY_EXPIRE_TIME}, which websockets-next reads. */
  static final String EXPIRE_TIME = "quarkus.identity.expire-time";

  /** {@code DaemonControlSocket}'s path: one segment, which is never the tunnel's {@code stream}. */
  static final Pattern SOCKET_PATH = Pattern.compile("^/workspaces/daemon/(?!stream$)[^/]+$");

  /** The {@code sub} prefix qits-idp gives a commissioned token's JWT ({@code tok-workspace-…}). */
  static final String TOKEN_SUBJECT_PREFIX = "tok-";

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
        || !SOCKET_PATH.matcher(path).matches()
        || identity.isAnonymous()
        || identity.getAttribute(EXPIRE_TIME) == null) {
      return identity;
    }
    String subject = DaemonAgentBindingCheck.subjectOf(identity);
    if (subject == null || !subject.startsWith(TOKEN_SUBJECT_PREFIX)) {
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
