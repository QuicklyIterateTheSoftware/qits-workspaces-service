package eu.wohlben.qits.workspaces.contracts;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The bearers a provider state hands its consumer</b>, for the door that judges the caller's
 * token rather than its role: the runner register door (the registration token's subject) —
 * qits-ci's {@code ContractBearers}, for this provider.
 *
 * <p>The {@code %test} suite runs no idp and no edge. A consumer, though, sends {@code
 * Authorization: Bearer <token>}. A state registers the bearer it returns as its {@code
 * authorization} param ({@link #grant}), and this augmentor turns a request carrying it into the
 * machine identity the edge would forward for that token: a JWT principal with its {@code sub}, the
 * platform audience and its roles. So the golden master and the pact both send the header a real
 * consumer sends.
 *
 * <p><b>Idle unless a state armed it</b>: with nothing granted it returns the identity unchanged,
 * so no other suite sees it. {@link #reset} disarms it again.
 *
 * <p><b>It is also how a test with several callers names each of them</b> (epic qits-543,
 * qits-1372): forward-auth and its {@code %test} dev user leave qits-auth-core with qits-1338, so a
 * test that used to send the forwarded identity header pair sends {@link #caller}'s value in {@code
 * Authorization} instead, which {@link SuiteCallerMechanism} answers. That identity is what
 * forward-auth made of the pair — a plain name holding the roles, <b>no token</b> — and its scheme is
 * not {@code Bearer}, so a class that turns the OIDC tenant on can use it beside real {@code
 * MockIdp} bearers. A test with one caller uses {@code @TestSecurity}. (Copied from
 * qits-projects-service's {@code ContractBearers}.)
 */
@ApplicationScoped
public class ContractBearers implements SecurityIdentityAugmentor {

  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** What a granted bearer stands for. */
  record Grant(String subject, Set<String> roles) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** The {@link #caller} values granted, answered by {@link SuiteCallerMechanism}. */
  private final Map<String, Grant> callers = new ConcurrentHashMap<>();

  /** The scheme of a {@link #caller} value: not {@code Bearer}, so no OIDC tenant reads it. */
  static final String CALLER_SCHEME = "Suite ";

  /**
   * The {@code Authorization} value of a caller this suite names: {@code subject} holding {@code
   * roles} (each may be a comma-separated list), as a plain principal with no token — the identity
   * the forwarded header pair used to establish. Granted afresh on every call on the running
   * application's instance, so a {@link #reset} between tests never strands it. No roles is a named
   * caller holding none.
   */
  public static String caller(String subject, String... roles) {
    String[] each =
        Arrays.stream(roles)
            .flatMap(role -> Arrays.stream(role.split(",")))
            .map(String::trim)
            .filter(role -> !role.isEmpty())
            .distinct()
            .toArray(String[]::new);
    String authorization = CALLER_SCHEME + subject + "." + String.join("+", each);
    // Through a method, never the field: the instance is a client proxy, whose own fields are not
    // the bean's.
    Arc.container().instance(ContractBearers.class).get().grantCaller(authorization, subject, each);
    return authorization;
  }

  /** {@link #caller}'s grant, on the bean itself. */
  void grantCaller(String authorization, String subject, String... roles) {
    callers.put(authorization, new Grant(subject, Set.of(roles)));
  }

  /**
   * The caller of a contract interaction that carries no credential of its own ({@link
   * GoldenMasterRecordingTest}, the pact verification): {@code dev} holding {@code qits:admin} and
   * {@code qits:system}, exactly the forward-auth dev user those recordings were made as, so no
   * committed answer moves. Named here because that user leaves qits-auth-core with qits-1338 (epic
   * qits-543).
   */
  public static String consumerCaller() {
    return caller("dev", "qits:admin", "qits:system");
  }

  /**
   * The identity {@link SuiteCallerMechanism} answers for an {@code Authorization} value: a {@link
   * #caller}'s plain principal, or a {@link #grant}ed bearer's token; empty for anything else, which
   * the shipped mechanisms then decide. With no forward-auth dev user a request carrying only a
   * granted bearer is otherwise anonymous, and an augmentor never sees it.
   */
  Optional<SecurityIdentity> identityFor(String authorization) {
    Grant named = callers.get(authorization);
    if (named != null) {
      QuarkusSecurityIdentity.Builder identity =
          QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(named.subject()));
      named.roles().forEach(identity::addRole);
      return Optional.of(identity.build());
    }
    Grant grant = grants.get(authorization);
    if (grant == null) {
      return Optional.empty();
    }
    QuarkusSecurityIdentity.Builder machine =
        QuarkusSecurityIdentity.builder().setPrincipal(new Token(grant.subject()));
    grant.roles().forEach(machine::addRole);
    return Optional.of(machine.build());
  }

  /** A request with {@code Authorization: <authorization>} is {@code subject} holding {@code roles}. */
  public void grant(String authorization, String subject, String... roles) {
    grants.put(authorization, new Grant(subject, Set.of(roles)));
  }

  public void reset() {
    grants.clear();
    callers.clear();
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
