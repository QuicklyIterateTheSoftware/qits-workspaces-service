package eu.wohlben.qits.workspaces.wiring;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The credentials a workspace runner is commissioned at qits-idp: its one-use registration TOKEN,
 * minted when an operator creates the runner (and at every rotation), and its own CLIENT, minted by
 * the register door. Both are given back when the runner is deleted. qits-ci's {@code
 * IdpCommissioner} is the shape; the HTTP is this service's generated clients ({@link IdpClients},
 * {@link IdpTokens}), and the retry is {@link IdpCredentialCommissioner#patiently}, the one loop
 * every commission here runs through.
 *
 * <p><b>Two kinds, and qits-idp maps each to one role</b> ({@code CommissionRoles}, qits-845): a
 * {@link #REGISTRATION_KIND} token grants {@code qits:workspaces-runner-registration}, which opens the
 * register door and the install script and nothing else; a {@link #RUNNER_KIND} client grants {@code
 * qits:workspaces-runner}, which opens the runner socket. Both are named by the runner's id as their
 * context, which is what {@link CommissionReconciler} judges them against. Neither states a project
 * claim or Git refs: a runner acts in no project and pushes nothing.
 *
 * <p><b>Commissioning is patient and throws; giving back is one attempt and never throws.</b> The
 * same split {@link IdpCredentialCommissioner} makes, for its reasons: a commission is somebody
 * waiting on an answer, and a give-back runs after the row is already gone, so a failure leaves an
 * orphan for the reconcile rather than an error for nobody.
 *
 * <p><b>Wired by the same switch</b>, {@code quarkus.oidc-client.qits.client-enabled} and the
 * client's own id and secret. With any of them missing {@link #enabled()} is false: commissioning
 * throws {@link CommissionFailedException} (the caller answers 503 before it gets there), and every
 * give-back and listing does nothing.
 */
@ApplicationScoped
public class IdpRunnerCommissioner {

  private static final Logger LOG = Logger.getLogger(IdpRunnerCommissioner.class);

  /** A workspace runner's own client, named by the runner's id. */
  public static final String RUNNER_KIND = "workspaces-runner";

  /** A workspace runner's one-use registration token, named by the runner's id. */
  public static final String REGISTRATION_KIND = "workspaces-runner-registration";

  /** A commission that could not be made: not wired, or every attempt the window allowed failed. */
  public static class CommissionFailedException extends IllegalStateException {
    public CommissionFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** A runner's commissioned client. The secret is answered once, by qits-idp. */
  public record RunnerClient(String clientId, String secret) {
    @Override
    public String toString() {
      return "RunnerClient[clientId=" + clientId + "]";
    }
  }

  /**
   * A commissioned token. {@code token} is the value, answered once and never logged; {@code
   * subject} is the {@code sub} the edge puts on the JWT it mints for it.
   */
  public record IssuedToken(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "IssuedToken[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live token of this service's, as the reconcile reads it. Never a value. */
  public record LiveToken(String tokenId, String contextKind, String contextId, Instant createdAt) {}

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-id")
  Optional<String> clientId;

  @ConfigProperty(name = "quarkus.oidc-client.qits.credentials.secret")
  Optional<String> clientSecret;

  /** The workspace credential's window: a runner's commission waits out the same idp cutover. */
  @ConfigProperty(name = "qits.workspace.commission.patience")
  Duration patience;

  @Inject @RestClient IdpClients clients;

  @Inject @RestClient IdpTokens tokens;

  /** Whether this process can commission at all. */
  public boolean enabled() {
    return authorization() != null;
  }

  /**
   * Commission the registration token for {@code runnerId}.
   *
   * @throws CommissionFailedException when not wired, or when no attempt landed
   */
  public IssuedToken registrationToken(UUID runnerId) {
    return token(REGISTRATION_KIND, runnerId.toString());
  }

  /**
   * Commission a token of {@code contextKind} for {@code contextId}, stating nothing else. The
   * general form of {@link #registrationToken}.
   *
   * @throws CommissionFailedException when not wired, or when no attempt landed
   */
  public IssuedToken token(String contextKind, String contextId) {
    String authorization = requireAuthorization(contextKind, contextId);
    IdpTokens.TokenRequest request = new IdpTokens.TokenRequest(contextKind, contextId);
    return commission(
        "a " + contextKind + " token for " + contextId,
        () -> {
          IdpTokens.TokenResponse issued = tokens.commission(authorization, request);
          if (issued == null
              || IdpCredentialCommissioner.blank(issued.tokenId())
              || IdpCredentialCommissioner.blank(issued.token())
              || IdpCredentialCommissioner.blank(issued.subject())) {
            throw new IllegalStateException(
                "qits-idp answered a token commission for " + contextId + " with no usable token");
          }
          return new IssuedToken(issued.tokenId(), issued.token(), issued.subject());
        });
  }

  /**
   * Commission the runner's own client: kind {@link #RUNNER_KIND}, context {@code runnerId}, no
   * project claim and no Git refs.
   *
   * @throws CommissionFailedException when not wired, or when no attempt landed
   */
  public RunnerClient runnerClient(UUID runnerId) {
    String contextId = runnerId.toString();
    String authorization = requireAuthorization(RUNNER_KIND, contextId);
    IdpClients.CommissionRequest request =
        new IdpClients.CommissionRequest(RUNNER_KIND, contextId, null);
    return commission(
        "a " + RUNNER_KIND + " client for " + contextId,
        () -> {
          IdpClients.CommissionResponse issued = clients.commission(authorization, request);
          if (issued == null
              || IdpCredentialCommissioner.blank(issued.clientId())
              || IdpCredentialCommissioner.blank(issued.secret())) {
            throw new IllegalStateException(
                "qits-idp answered a client commission for " + contextId + " with no usable pair");
          }
          return new RunnerClient(issued.clientId(), issued.secret());
        });
  }

  /** Give a runner's client back. One attempt; 404 is success; a failure is the reconcile's. */
  public void decommissionClient(String runnerClientId) {
    String authorization = authorization();
    if (authorization == null || IdpCredentialCommissioner.blank(runnerClientId)) {
      return;
    }
    try {
      clients.decommission(authorization, runnerClientId);
    } catch (WebApplicationException http) {
      if (http.getResponse().getStatus() != 404) {
        LOG.warnf(
            "qits-idp answered %d while decommissioning runner client %s; the reconcile will reap"
                + " it",
            http.getResponse().getStatus(), runnerClientId);
      }
    } catch (RuntimeException unreachable) {
      LOG.warnf(
          "Could not reach qits-idp to decommission runner client %s; the reconcile will reap it:"
              + " %s",
          runnerClientId, unreachable.toString());
    }
  }

  /**
   * Delete a token. One attempt; 404 is success. Answers whether the token is gone, so a caller
   * that wants to say so can; a failure is left for the reconcile.
   */
  public boolean deleteToken(String tokenId) {
    String authorization = authorization();
    if (authorization == null || IdpCredentialCommissioner.blank(tokenId)) {
      return false;
    }
    try {
      tokens.delete(authorization, tokenId);
      return true;
    } catch (WebApplicationException http) {
      if (http.getResponse().getStatus() == 404) {
        return true;
      }
      LOG.warnf(
          "qits-idp answered %d while deleting token %s; the reconcile will reap it",
          http.getResponse().getStatus(), tokenId);
    } catch (RuntimeException unreachable) {
      LOG.warnf(
          "Could not reach qits-idp to delete token %s; the reconcile will reap it: %s",
          tokenId, unreachable.toString());
    }
    return false;
  }

  /**
   * Every live token this service owns, or EMPTY when it could not be read (or nothing is wired).
   * Empty and an empty list are different answers on purpose: the reconcile deletes from what this
   * returns, so a listing that failed must reap nothing rather than read as "nothing is out there".
   */
  public Optional<List<LiveToken>> liveTokens() {
    String authorization = authorization();
    if (authorization == null) {
      return Optional.empty();
    }
    try {
      List<IdpTokens.TokenView> answer = tokens.list(authorization);
      if (answer == null) {
        return Optional.empty();
      }
      return Optional.of(
          answer.stream()
              .filter(t -> !IdpCredentialCommissioner.blank(t.tokenId()))
              .map(
                  t ->
                      new LiveToken(
                          t.tokenId(), t.contextKind(), t.contextId(), instant(t.createdAt())))
              .toList());
    } catch (RuntimeException e) {
      LOG.warnf("Could not list this service's tokens at qits-idp: %s", e.toString());
      return Optional.empty();
    }
  }

  private <T> T commission(String what, java.util.function.Supplier<T> attempt) {
    try {
      return IdpCredentialCommissioner.patiently(patience, what, attempt, failure -> false);
    } catch (IllegalStateException failed) {
      throw new CommissionFailedException(failed.getMessage(), failed.getCause());
    }
  }

  private String requireAuthorization(String contextKind, String contextId) {
    String authorization = authorization();
    if (authorization == null) {
      throw new CommissionFailedException(
          "Cannot commission a "
              + contextKind
              + " credential for "
              + contextId
              + ": this service has no idp client wired (quarkus.oidc-client.qits.*)",
          null);
    }
    return authorization;
  }

  private String authorization() {
    return IdpCredentialCommissioner.authorization(enabled, clientId, clientSecret);
  }

  private static Instant instant(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException unparseable) {
      return null;
    }
  }
}
