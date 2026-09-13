package eu.wohlben.qits.workspaces.wiring;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Supplies the bearer for Workspaces' qits-projects REST client.
 *
 * <p>The same {@code qits} named client every outbound call this service makes now shares
 * (service-client-identity-plan.md, C4), asking one audience — {@code qits-platform} — rather than a
 * qits-projects-specific one.
 */
@ApplicationScoped
public class IdpProjectsBearer {

  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @Inject @NamedOidcClient("qits") OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  public Optional<String> authorization() {
    if (!enabled) {
      return Optional.empty();
    }
    String token = tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken();
    return Optional.ofNullable(token).filter(value -> !value.isBlank()).map(value -> "Bearer " + value);
  }
}
