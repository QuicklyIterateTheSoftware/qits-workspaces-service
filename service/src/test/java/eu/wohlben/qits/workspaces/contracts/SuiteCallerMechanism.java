package eu.wohlben.qits.workspaces.contracts;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.Set;

/**
 * <b>Turns the suite's own credentials into their identities</b> (epic qits-543, qits-1372): a
 * {@link ContractBearers#caller} value, and a bearer a provider state {@link ContractBearers#grant}ed.
 * Copied from qits-projects-service.
 *
 * <p>{@link ContractBearers} is an augmentor, and an augmentor only ever sees an identity some
 * mechanism produced. That used to be forward-auth's {@code %test} dev user, which every request
 * without a header became; with it gone (qits-1338, blanked in the test config until then) such a
 * request is anonymous and a guarded door answers 401 before anything could rename it. So the
 * suite's credentials get a mechanism of their own: this one looks the {@code Authorization} value
 * up and answers that identity. Any other request — no header, a real {@code MockIdp} bearer, a
 * value nobody granted — it leaves alone, for the shipped mechanisms to decide.
 *
 * <p>Tried before the shipped mechanisms (a higher priority). A {@link ContractBearers#caller}
 * value's scheme is not {@code Bearer}, so a class that turns the OIDC tenant on never sees it; and
 * no idp ever minted a granted value, so neither would the tenant accept it. It never challenges: a
 * refusal is the shipped mechanisms' to describe.
 */
@ApplicationScoped
public class SuiteCallerMechanism implements HttpAuthenticationMechanism {

  @Inject ContractBearers bearers;

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String authorization = context.request().getHeader("Authorization");
    if (authorization == null || authorization.isBlank()) {
      return Uni.createFrom().optional(Optional.empty());
    }
    return Uni.createFrom().optional(bearers.identityFor(authorization));
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().optional(Optional.empty());
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }

  @Override
  public int getPriority() {
    return DEFAULT_PRIORITY + 1000;
  }
}
