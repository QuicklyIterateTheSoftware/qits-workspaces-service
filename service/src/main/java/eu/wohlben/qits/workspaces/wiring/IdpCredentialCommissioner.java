package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.WorkspaceCredential;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The {@link CredentialCommissioner} this context ships: qits-idp's commission API over HTTP,
 * authenticated with this service's own idp client id and secret.
 *
 * <p><b>One switch decides whether this is wired, and it is the extension's own.</b> {@code
 * quarkus.oidc-client.client-enabled} already governs whether a token is fetched for qits-containers
 * ({@code containers/ContainersClientProducer} reads the same key for the same reason) — off, there
 * is no secret in this process to authenticate with and there is nothing to commission against. So
 * off means {@link #commission} answers empty, no container carries a credential, and every
 * workspace behaves exactly as it did before this class existed. A key of our own would be a second
 * thing to get wrong.
 *
 * <p><b>{@code @DefaultBean}, and keep it.</b> A test-scoped double must win, and two unqualified
 * beans of one type fail the build at {@code ArcProcessor#validate} — for every test at once. Same
 * annotation and same reason as {@code HttpRepositoryLookup}.
 *
 * <p><b>Commissioning is patient, and decommissioning is not.</b> A commission failure fails a
 * workspace launch, and the measured failure mode is a window rather than a verdict: the 2026-08-12
 * rebootstrap replaced qits-platform-idp and the next three container launches died with {@code 401}
 * while the following ones passed. So a commission holds through the answers that are about the
 * moment — unreachable, 401, 403, 5xx — for {@code qits.workspace.commission.patience}, and takes
 * everything else at its word. A decommission runs after the container is already gone and has
 * nobody to report to, so it is one attempt: what a failure leaves behind is an orphan, and {@link
 * CommissionReconciler} is the structural answer to those.
 *
 * <p><b>404 on a decommission is success.</b> The credential is not there, which is the state the
 * call was asking for.
 */
@ApplicationScoped
@DefaultBean
public class IdpCredentialCommissioner implements CredentialCommissioner {

  private static final Logger LOG = Logger.getLogger(IdpCredentialCommissioner.class);

  /** How long a patient commission waits between attempts. */
  private static final Duration RETRY_PAUSE = Duration.ofSeconds(3);

  /**
   * The single switch, read from the extension's own key rather than shadowed by one of ours — see
   * the class javadoc. Required: a deployment that deletes the shipped line should fail to start
   * rather than quietly stop commissioning.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean enabled;

  /** This service's own idp client — the {@code owner} every commission is recorded under. */
  @ConfigProperty(name = "quarkus.oidc-client.client-id")
  Optional<String> clientId;

  /** Its secret. Absent whenever the switch above is off, which is the shipped posture. */
  @ConfigProperty(name = "quarkus.oidc-client.credentials.secret")
  Optional<String> clientSecret;

  /**
   * How long a commission holds through an idp that cannot answer yet. Thirty seconds: it covers a
   * redeploy's trailing edge, and it is a launch's own thread that spends it.
   */
  @ConfigProperty(name = "qits.workspace.commission.patience")
  Duration patience;

  @Inject @RestClient IdpClients clients;

  /**
   * Whether the "this idp refuses Git refs" warning was logged. Once per process: every launch
   * would repeat it until the idp is upgraded, and one line says all there is to say.
   */
  private final AtomicBoolean warnedGitRefsRefused = new AtomicBoolean();

  /**
   * {@inheritDoc}
   *
   * <p><b>A 400 to a commission that states Git refs is read as an idp without C2</b>, and the
   * commission is asked again without them: the credential is then unscoped for Git, as every
   * workspace credential was before, and the workspace still launches. A 400 to a commission that
   * states none stays an answer about the request.
   */
  @Override
  public Optional<WorkspaceCredential> commission(
      Long rowId, String projectId, List<String> gitRefs) {
    String authorization = authorization();
    if (authorization == null || rowId == null) {
      return Optional.empty();
    }
    IdpClients.CommissionRequest request =
        new IdpClients.CommissionRequest(
            CONTEXT_KIND,
            Long.toString(rowId),
            claims(projectId),
            gitRefs == null ? null : List.copyOf(gitRefs));
    Instant giveUpAt = Instant.now().plus(patience);
    // Never pause past the window itself: a pause longer than the patience would make a short
    // patience mean one attempt while looking like a window.
    Duration pause = RETRY_PAUSE.compareTo(patience) > 0 ? patience : RETRY_PAUSE;
    int attempts = 0;
    while (true) {
      attempts++;
      try {
        IdpClients.CommissionResponse issued = clients.commission(authorization, request);
        if (issued == null || blank(issued.clientId()) || blank(issued.secret())) {
          throw new IllegalStateException(
              "qits-idp answered a commission for workspace "
                  + rowId
                  + " with no usable credential in it");
        }
        return Optional.of(new WorkspaceCredential(issued.clientId(), issued.secret()));
      } catch (RuntimeException failure) {
        if (request.gitRefs() != null && status(failure) == 400) {
          warnGitRefsRefused(rowId, failure);
          request = request.withoutGitRefs();
          continue;
        }
        if (!holdThrough(failure) || !Instant.now().isBefore(giveUpAt) || !sleep(pause)) {
          throw new IllegalStateException(
              "Could not commission a credential for workspace "
                  + rowId
                  + " after "
                  + attempts
                  + " attempt(s): "
                  + failure,
              failure);
        }
        LOG.infof(
            "Attempt %d to commission a credential for workspace %s did not land (%s) — asking"
                + " again, holding through the window",
            attempts, rowId, failure.toString());
      }
    }
  }

  @Override
  public void decommission(String clientId) {
    String authorization = authorization();
    if (authorization == null || blank(clientId)) {
      return;
    }
    try {
      clients.decommission(authorization, clientId);
    } catch (WebApplicationException http) {
      if (http.getResponse().getStatus() == 404) {
        // Already gone: the state this call was asking for.
        return;
      }
      LOG.warnf(
          "qits-idp answered %d while decommissioning %s; the reconcile will reap it",
          http.getResponse().getStatus(), clientId);
    } catch (RuntimeException transportFailure) {
      LOG.warnf(
          "Could not reach qits-idp to decommission %s; the reconcile will reap it: %s",
          clientId, transportFailure.toString());
    }
  }

  /**
   * One attempt, and a failure throws: the caller keeps the narrowing pending and the reconcile
   * sends it again, so there is no window to hold through here.
   */
  @Override
  public void updateGitRefs(String clientId, List<String> gitRefs) {
    String authorization = authorization();
    if (authorization == null || blank(clientId)) {
      return;
    }
    try {
      clients.updateGitRefs(
          authorization, clientId, new IdpClients.GitRefsRequest(List.copyOf(gitRefs)));
    } catch (WebApplicationException http) {
      throw new IllegalStateException(
          "qits-idp answered "
              + http.getResponse().getStatus()
              + " to the Git ref update of "
              + clientId,
          http);
    }
  }

  /** The HTTP status of a failed call, or -1 when nothing answered. */
  private static int status(RuntimeException failure) {
    return failure instanceof WebApplicationException http ? http.getResponse().getStatus() : -1;
  }

  private void warnGitRefsRefused(Long rowId, RuntimeException failure) {
    if (warnedGitRefsRefused.compareAndSet(false, true)) {
      LOG.warnf(
          "qits-idp refused a commission that states Git refs (%s). Commissioning without them:"
              + " workspace credentials are not limited to their Git refs until qits-idp accepts"
              + " the gitRefs member. This is logged once.",
          failure.toString());
    } else {
      LOG.debugf("Commissioning workspace %s without Git refs: qits-idp refused them", rowId);
    }
  }

  @Override
  public List<Commission> list() {
    String authorization = authorization();
    if (authorization == null) {
      return List.of();
    }
    try {
      List<IdpClients.CommissionView> answer = clients.list(authorization);
      return answer == null
          ? List.of()
          : answer.stream()
              .map(v -> new Commission(v.clientId(), v.contextKind(), v.contextId()))
              .toList();
    } catch (RuntimeException e) {
      // Empty is the safe shape of "nothing was learned": the reconcile decommissions what this
      // answers and nothing else, so an unreadable listing reaps nothing rather than everything.
      LOG.warnf("Could not list this service's commissions at qits-idp: %s", e.toString());
      return List.of();
    }
  }

  /**
   * What the commission states about this context: the project, or nothing.
   *
   * <p><b>A blank project is no member, never a blank value and never {@code "*"}.</b> qits-idp
   * refuses both of those, and refusing is the right answer to them — but this service must not turn
   * "the registry could not name the project" into a failed workspace launch, so the unresolved case
   * simply asks for what every workspace credential asked for before scoping existed. What it costs
   * is the narrowing; the container still starts and still has an identity.
   */
  private static Map<String, String> claims(String projectId) {
    String project = projectId == null ? "" : projectId.trim();
    if (project.isEmpty()) {
      LOG.warn(
          "Commissioning a workspace credential with no project scope: the repository registry named"
              + " no project for it. The credential will be issued unscoped, as it was before"
              + " per-context scoping.");
      return null;
    }
    return Map.of(CredentialCommissioner.PROJECT_CLAIM, project);
  }

  /**
   * The {@code Basic} header, or null when this deployment has no credential to present — which is
   * the whole "not wired" condition, checked in one place so all three verbs answer it identically.
   */
  private String authorization() {
    if (!enabled) {
      return null;
    }
    String id = clientId.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    String secret = clientSecret.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    if (id == null || secret == null) {
      return null;
    }
    return basic(id, secret);
  }

  /** {@code client_secret_basic}, the same spelling qits-idp's token endpoint already accepts. */
  static String basic(String clientId, String secret) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The failures another attempt could change. Unreachable and 5xx are about the moment by
   * definition; 401 and 403 are the idp-cutover lesson — the same call with the same credential
   * succeeds a minute later once the replacement's store is up, and there is no way to tell that
   * apart from a wrong secret from here. Everything else — a 400 on the body, a 409 — is an answer
   * about the request.
   */
  static boolean holdThrough(RuntimeException failure) {
    if (!(failure instanceof WebApplicationException http)) {
      return true; // connection refused, DNS, timeout: nothing was learned
    }
    int status = http.getResponse().getStatus();
    return status == 401 || status == 403 || status >= 500;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  /** Wait, or report that this thread is being asked to stop — in which case the loop is over. */
  private static boolean sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
