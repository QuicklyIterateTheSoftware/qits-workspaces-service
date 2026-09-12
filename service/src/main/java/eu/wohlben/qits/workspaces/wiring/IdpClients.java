package eu.wohlben.qits.workspaces.wiring;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-idp's commission API — the second call this service makes to another service, and the only
 * one that mints anything.
 *
 * <p><b>The path is a cross-repo contract.</b> {@code /api/clients} is served under qits-idp's own
 * {@code /idp} segment, which is what the configured base url carries — and that base is {@code
 * quarkus.oidc-client.auth-server-url}, the address this service already holds because it fetches
 * tokens from the same place. Deriving it means there is no second address to configure and no way
 * for the two to disagree about which idp this is.
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

  /** Commission a credential for one context. 201 with the pair; the secret is answered once. */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  CommissionResponse commission(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, CommissionRequest request);

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

  /**
   * Replace the Git refs one of this caller's commissions states (contract C2). Only the owner may
   * ask; 404 for a foreign or unknown client. The next token of that client carries the new list.
   */
  @PUT
  @Path("/{clientId}/git-refs")
  @Consumes(MediaType.APPLICATION_JSON)
  void updateGitRefs(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("clientId") String clientId,
      GitRefsRequest request);

  /** The body of {@link #updateGitRefs}: the whole new list. */
  record GitRefsRequest(List<String> gitRefs) {}

  /**
   * What a caller asks for: which context this credential is being commissioned for, and what that
   * context is about.
   *
   * <p>{@code claims} narrows the credential qits-idp is about to mint — {@code
   * {"project":"<projectId>"}} here, so every token this workspace's container presents says which
   * project it may act in. The issuer refuses anything it will not grant, including the wildcard, so
   * a value this service cannot resolve travels as no member at all rather than as {@code "*"}.
   *
   * <p><b>An idp that predates the member ignores it</b> rather than refusing the call — the
   * commission API tolerates unknown members, measured against the live one — which is what lets
   * this service ship before the issuer does. What that costs is the scope, not the workspace.
   */
  record CommissionRequest(
      String contextKind,
      String contextId,
      Map<String, String> claims,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<String> gitRefs) {

    /** The commission as it was before C2: no {@code gitRefs} member at all. */
    CommissionRequest(String contextKind, String contextId, Map<String, String> claims) {
      this(contextKind, contextId, claims, null);
    }

    /** The same request with no Git refs stated — the fallback for an idp that refused them. */
    CommissionRequest withoutGitRefs() {
      return new CommissionRequest(contextKind, contextId, claims, null);
    }
  }

  /**
   * qits-idp's {@code IdpClientsController.CommissionResponse}, narrowed to the two fields this
   * context is entitled to. {@code owner}, {@code contextKind}, {@code contextId} and {@code
   * createdAt} come back too and are this caller's own arguments echoed; not binding to them is what
   * keeps that service free to change them.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CommissionResponse(String clientId, String secret) {}

  /** One live commission, as the reconcile reads it. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record CommissionView(String clientId, String contextKind, String contextId) {}
}
