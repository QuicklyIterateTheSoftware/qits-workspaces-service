package eu.wohlben.qits.workspaces.contracts;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The bearers a provider state hands its consumer</b>, for the door that judges the caller's
 * token rather than its role: the runner register door (the registration token's subject) —
 * qits-ci's {@code ContractBearers}, for this provider.
 *
 * <p>The {@code %test} suite runs no idp and no edge: every request is the forwarded dev user. A
 * consumer, though, sends {@code Authorization: Bearer <token>}. A state registers the bearer it
 * returns as its {@code authorization} param ({@link #grant}), and this augmentor turns a request
 * carrying it into the machine identity the edge would forward for that token: a JWT principal with
 * its {@code sub}, the platform audience and its roles. So the golden master and the pact both send
 * the header a real consumer sends.
 *
 * <p><b>Idle unless a state armed it</b>: with nothing granted it returns the identity unchanged,
 * so no other suite sees it. {@link #reset} disarms it again.
 */
@ApplicationScoped
public class ContractBearers implements SecurityIdentityAugmentor {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** What a granted bearer stands for. */
  record Grant(String subject, Set<String> roles) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** A request with {@code Authorization: <authorization>} is {@code subject} holding {@code roles}. */
  public void grant(String authorization, String subject, String... roles) {
    grants.put(authorization, new Grant(subject, Set.of(roles)));
  }

  public void reset() {
    grants.clear();
  }

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
    if (grants.isEmpty()) {
      return Uni.createFrom().item(identity);
    }
    RoutingContext routing = HttpSecurityUtils.getRoutingContextAttribute(attributes);
    String authorization = routing == null ? null : routing.request().getHeader("Authorization");
    if (authorization == null || authorization.isBlank()) {
      return Uni.createFrom().item(identity);
    }
    Grant grant = grants.get(authorization);
    if (grant == null) {
      return Uni.createFrom().item(identity);
    }
    QuarkusSecurityIdentity.Builder machine =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject()));
    grant.roles().forEach(machine::addRole);
    return Uni.createFrom().item(machine.build());
  }

  /** The JWT the edge would forward: a subject and the platform audience, nothing else. */
  private record Token(String subject) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      return Set.of("sub", "aud");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        default -> null;
      };
    }
  }
}
