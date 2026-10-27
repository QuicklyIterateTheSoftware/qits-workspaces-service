package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-idp's client commission API, {@code /api/clients} — read and given back, never asked for a
 * new client. Every container this service starts holds a workspace token ({@link IdpTokens}), so
 * what is left here is housekeeping: the listing {@code CommissionReconciler} reaps from and {@code
 * targethost/LegacyRunnerClients} gives the retired runner's clients back from, and the delete both
 * of them call.
 *
 * <p><b>The path is a cross-repo contract.</b> {@code /api/clients} is served under qits-idp's own
 * {@code /idp} segment, which is what the configured base url carries — and that base is {@code
 * quarkus.oidc-client.qits.auth-server-url} (epic qits-540 dossier, 'Plan (as of 2026-09-13)', C4), the address this
 * service already holds because it fetches tokens from the same place. Deriving it means there is no
 * second address to configure and no way for the two to disagree about which idp this is.
 *
 * <p><b>Authorization is HTTP Basic, and the credential is this service's own idp client.</b> That
 * is the whole mechanism qits-idp offers here and it adds nothing to configure: a caller already
 * holds an id and a secret, which is how it gets tokens at all. It travels as an explicit {@code
 * @HeaderParam} rather than through a filter so the one place that composes it is the one place that
 * reads the keys — {@link IdpCredentialCommissioner}.
 */
@Path("/api/clients")
@RegisterRestClient(configKey = "qits-idp")
public interface IdpClients {

  /** The caller's own live commissions. No secrets, and no way to ask about another owner's. */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  List<CommissionView> list(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  /** Give one back. 204, or 404 when it is already gone — which is the same outcome. */
  @DELETE
  @Path("/{clientId}")
  void decommission(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("clientId") String clientId);

  /** One live commission, as the reconcile reads it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CommissionView(String clientId, String contextKind, String contextId) {}
}
