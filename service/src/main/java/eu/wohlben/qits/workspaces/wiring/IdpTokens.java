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
 * qits-idp's commissioned-token API, {@code /api/tokens}: the third credential qits-idp issues, an
 * opaque {@code qits_tok_…} value the edge introspects. {@link IdpClients}' sibling, and it shares
 * everything that one's javadoc says about the address and the caller: the {@code qits-idp} config
 * key, whose base is {@code quarkus.oidc-client.qits.auth-server-url}, and HTTP Basic with this
 * service's own static client. Only a static {@code qits:system} client may commission a token
 * (qits-idp {@code IdpTokensController.commission}), and this service's is one.
 *
 * <p><b>Written for more than one kind.</b> It mints a workspace runner's registration token
 * ({@code IdpRunnerCommissioner}) and the per-workspace token of kind {@code workspace} a RUNNER row
 * holds (qits-625, {@code IdpCredentialCommissioner}), which is why the request carries {@code
 * claims} and {@code gitRefs} although the runner states neither.
 *
 * <p><b>The value is answered once.</b> qits-idp stores a hash; the listing never carries a value,
 * and a caller that loses one deletes the token and commissions again.
 */
@Path("/api/tokens")
@RegisterRestClient(configKey = "qits-idp")
public interface IdpTokens {

  /** Commission a token for one context. 201 with the value, answered this once. */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  TokenResponse commission(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, TokenRequest request);

  /** The caller's own live tokens. No values, and no other owner's. */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  List<TokenView> list(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  /** Delete one. 204, or 404 when it is already gone, which is the same outcome. */
  @DELETE
  @Path("/{tokenId}")
  void delete(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("tokenId") String tokenId);

  /**
   * Replace the Git refs a token states (qits-625): the twin of {@link IdpClients#updateGitRefs},
   * owner-only, and the body must carry a list — {@code []} is "may push nothing". 200 with the
   * token's view, which nothing here reads; 404 for a foreign or unknown token. The edge picks the
   * new list up within its introspection cache.
   */
  @PUT
  @Path("/{tokenId}/git-refs")
  @Consumes(MediaType.APPLICATION_JSON)
  void updateGitRefs(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("tokenId") String tokenId,
      IdpClients.GitRefsRequest request);

  /**
   * What a caller asks for: the context the token is for, and optionally what that context is about
   * ({@code claims}) and may push ({@code gitRefs}). Absent members state nothing, which is what a
   * runner's registration token asks for.
   */
  record TokenRequest(
      String contextKind,
      String contextId,
      @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> claims,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<String> gitRefs) {

    /** A token that states no claims and no Git refs. */
    TokenRequest(String contextKind, String contextId) {
      this(contextKind, contextId, null, null);
    }

    /** The same request stating an empty Git ref list: the fail-closed retry. */
    TokenRequest pushingNothing() {
      return new TokenRequest(contextKind, contextId, claims, List.of());
    }
  }

  /**
   * qits-idp's {@code IdpTokensController.CommissionResponse}, narrowed to the three fields this
   * service uses: the id to delete it by, the value (once), and the {@code sub} the edge puts on the
   * JWT it mints for the token, which is how a door here recognises the caller as this token.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenResponse(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      // A record names every component, and this one's second is a credential.
      return "TokenResponse[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /** One live token, as the reconcile reads it. Never a value. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record TokenView(
      String tokenId, String subject, String contextKind, String contextId, String createdAt) {}
}
