package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.WorkspaceToken;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Request;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.arc.Arc;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.OidcClients;
import io.quarkus.oidc.client.runtime.OidcClientConfig;
import io.quarkus.oidc.client.runtime.OidcClientsConfig;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <b>What qits-workspaces asks qits-idp, and why</b> (qits-1149) — the table {@code
 * IdpConsumerPactTest} runs against pact mock servers and {@code ConsumerPactFilesTest} writes to
 * {@code pacts/qits-workspaces-service_qits-idp-service.json}.
 *
 * <p><b>Four callers, one provider.</b>
 *
 * <ul>
 *   <li>{@link IdpCredentialCommissioner} — the commission doors for a workspace's credential: a
 *       token ({@code /idp/api/tokens}, a workspace's or an agent's), minted when the scheduler
 *       starts a slot or places an agent ({@code AgentService.reconcile}); tokens listed and handed
 *       back by {@code CommissionReconciler.reconcile}, and a token handed back with its container
 *       ({@code deleteWorkspaceContainer}, {@code discardWorkspace}, {@code recreateWorkspaceContainer}, and
 *       the scheduler's removal of a slot or an agent).
 *   <li>{@code targethost/LegacyRunnerClients} — the retired workspaces runners' clients and tokens
 *       (epic qits-1481), listed and given back once at boot through the same commissioner.
 *   <li>quarkus-oidc-client's {@code qits} client — the platform-audience bearer this service sends
 *       qits-containers, qits-githost and qits-projects, minted with {@code client_secret_basic}
 *       ({@code POST /idp/token}).
 *   <li>the quarkus-oidc tenant — the signing keys every inbound bearer is checked against ({@code
 *       GET /idp/jwks}, discovery off).
 * </ul>
 *
 * <p><b>The Basic pair is the state's.</b> qits-idp's doors take HTTP Basic, and its verification
 * sends the {@code authorization} param its state returns; each row's commissioner presents the
 * pair that param decodes to, so the mock server sees the header the pact names.
 *
 * <p><b>The base url ends in {@code /idp}</b>, as {@code quarkus.oidc-client.qits.auth-server-url}
 * does in every deployment.
 */
public final class IdpContract {

  // qits-idp's state names (golden masters 2026.1018.175524).
  static final String A_CLIENT = "a commissioned client";
  static final String A_TOKEN = "a commissioned token";
  static final String SIGNING_KEY = "a published signing key";
  static final String NO_TOKEN = "no commissioned token with the given id";
  static final String PEER_CALLER = "a service client calling its peers";

  // One state per commission body this service sends (qits-idp qits-1149 round 3): the state's
  // params are the row's values, and the body binds each with a provider-state expression.
  static final String WORKSPACE_TOKEN = "a workspace's token being commissioned";
  static final String WORKSPACE_TOKEN_REFUSED =
      "a workspace's token whose stated Git refs are refused";
  static final String WORKSPACE_TOKEN_PUSHING_NOTHING =
      "a workspace's token being commissioned to push nothing";
  static final String AGENT_TOKEN = "an agent's token being commissioned";
  static final String AGENT_TOKEN_REFUSED = "an agent's token whose stated Git refs are refused";
  static final String AGENT_TOKEN_PUSHING_NOTHING =
      "an agent's token being commissioned to push nothing";

  static final Trigger RECONCILE = Trigger.schedule("AgentService.reconcile");
  static final Trigger COMMISSIONS = Trigger.schedule("CommissionReconciler.reconcile");
  static final Trigger LEGACY_RUNNERS = Trigger.schedule("LegacyRunnerClients.giveBack");

  private static final String BASIC = "{authorization}";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.IDP,
          List.of(
              // --- a workspace's credential ---------------------------------------------------
              ContractRow.of(
                  RECONCILE,
                  WORKSPACE_TOKEN,
                  "commissionToken",
                  basic(workspaceCommission(WORKSPACE_TOKEN, "${gitRef}")),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    WorkspaceToken issued =
                        commissionerAgainst(url, params)
                            .commissionToken(
                                workspaceId(params),
                                params.get("projectId"),
                                List.of(params.get("gitRef")),
                                false)
                            .orElseThrow();
                    assertRecordedToken(WORKSPACE_TOKEN, issued.tokenId(), issued.subject());
                  }),
              // An agent's own token (qits-1152, qits-1213): the workspace kind, context agent-<id>,
              // minted when the dispatch door records the agent (AgentService.mintToken).
              ContractRow.of(
                  Trigger.operation("dispatchAgent"),
                  AGENT_TOKEN,
                  "commissionToken",
                  basic(agentCommission(AGENT_TOKEN, "${gitRef}", "${gitRef}-*")),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    WorkspaceToken issued =
                        commissionerAgainst(url, params)
                            .commissionAgentToken(
                                params.get("agentId"),
                                params.get("projectId"),
                                List.of(params.get("gitRef"), params.get("gitRef") + "-*"),
                                false)
                            .orElseThrow();
                    assertRecordedToken(AGENT_TOKEN, issued.tokenId(), issued.subject());
                  }),
              ContractRow.of(
                  COMMISSIONS,
                  A_TOKEN,
                  "listTokens",
                  basic(null),
                  List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
                  (url, params) -> {
                    List<CredentialCommissioner.TokenCommission> held =
                        commissionerAgainst(url, params).listTokens();
                    assertFalse(held.isEmpty(), "the recording holds a token");
                    held.forEach(t -> assertTrue(t.createdAt() != null, "createdAt parses"));
                  }),
              ContractRow.of(
                  COMMISSIONS,
                  A_TOKEN,
                  "deleteToken",
                  basic(null),
                  List.of(),
                  (url, params) -> commissionerAgainst(url, params).deleteToken(params.get("tokenId"))),
              // A workspace's token goes back with its container (qits-950, B1): the container
              // delete door, a discard, and a recreate (whose next container is minted a fresh one)
              // each hand it back through WorkspaceService.decommissionFor or deleteToken(clearToken),
              // and the scheduler does for a slot it removes and an agent it removes. The request is
              // the reconcile's; what the rows add is the chain from each verb to the delete.
              tokenHandback(Trigger.operation("deleteWorkspaceContainer")),
              tokenHandback(Trigger.operation("discardWorkspace")),
              tokenHandback(Trigger.operation("recreateWorkspaceContainer")),
              tokenHandback(RECONCILE),
              // --- the retired runners' credentials (epic qits-1481) -------------------------
              // LegacyRunnerClients lists both and gives back every one of the retired kinds. Its
              // client requests (/idp/api/clients) are the only ones this service still sends:
              // nothing commissions a client any more (qits-876), and the reconcile's client arm is
              // gone (qits-1683).
              ContractRow.of(
                  LEGACY_RUNNERS,
                  A_CLIENT,
                  "listClients",
                  basic(null),
                  List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
                  (url, params) ->
                      assertFalse(commissionerAgainst(url, params).list().isEmpty())),
              ContractRow.of(
                  LEGACY_RUNNERS,
                  A_CLIENT,
                  "decommissionClient",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params).decommission(params.get("clientId"))),
              ContractRow.of(
                  LEGACY_RUNNERS,
                  A_TOKEN,
                  "listTokens",
                  basic(null),
                  List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
                  (url, params) ->
                      assertFalse(commissionerAgainst(url, params).listTokens().isEmpty())),
              tokenHandback(LEGACY_RUNNERS),
              // --- the tenant's signing keys ---------------------------------------------------
              ContractRow.of(
                  Trigger.event("StartupEvent"),
                  SIGNING_KEY,
                  "getJwks",
                  Request.NONE,
                  List.of(
                      "$.keys[*].kid",
                      "$.keys[*].kty",
                      "$.keys[*].alg",
                      "$.keys[*].use",
                      "$.keys[*].n",
                      "$.keys[*].e"),
                  (url, params) -> {
                    JsonNode keys = getJson(url + "/idp/jwks").path("keys");
                    assertFalse(keys.isEmpty(), "at least one key");
                    for (JsonNode key : keys) {
                      assertEquals("RSA", key.path("kty").asText());
                      assertEquals("RS256", key.path("alg").asText());
                      assertFalse(key.path("kid").asText().isBlank(), "kid");
                    }
                  }),
              // --- the peer bearer, and the 404s a handback reads as done ----------------------
              // The peer bearer: client_secret_basic, the client grant and the platform audience,
              // exactly as the shipped `qits` client sends it. A handback (delete) of a token
              // qits-idp no longer holds answers 404, which the commissioner reads as done.
              ContractRow.of(
                  RECONCILE,
                  PEER_CALLER,
                  "issueToken",
                  Request.form("grant_type=client_credentials&audience=qits-platform")
                      .header("Authorization", BASIC),
                  List.of("$.access_token"),
                  (url, params) -> {
                    String token = mintAgainst(url, params);
                    assertFalse(token == null || token.isBlank(), "a bearer was minted");
                  }),
              ContractRow.of(
                  COMMISSIONS,
                  NO_TOKEN,
                  "deleteToken",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params).deleteToken(params.get("tokenId"))),
              // A token mint whose stated Git refs qits-idp refuses: 400, the answer the commissioner
              // tells apart from every other refusal (IdpCredentialCommissioner.status) to ask again
              // with gitRefs: [] and fail closed. Each refused row is that first request and the
              // status it reads, made through the commissioner's own client; each "push nothing"
              // row is the retry, the same request's pushingNothing(). The refused ref is the
              // state's.
              ContractRow.of(
                  RECONCILE,
                  WORKSPACE_TOKEN_REFUSED,
                  "commissionToken",
                  basic(workspaceCommission(WORKSPACE_TOKEN_REFUSED, "${refusedGitRef}")),
                  List.of(),
                  (url, params) -> {
                    IdpCredentialCommissioner commissioner = commissionerAgainst(url, params);
                    assertRefused(
                        () ->
                            commissioner.tokens.commission(
                                basicOf(params),
                                workspaceTokenRequest(params, List.of(params.get("refusedGitRef")))));
                  }),
              ContractRow.of(
                  RECONCILE,
                  WORKSPACE_TOKEN_PUSHING_NOTHING,
                  "commissionToken",
                  basic(workspaceCommission(WORKSPACE_TOKEN_PUSHING_NOTHING)),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    IdpTokens.TokenResponse issued =
                        commissionerAgainst(url, params)
                            .tokens
                            .commission(
                                basicOf(params),
                                workspaceTokenRequest(params, List.of(params.get("gitRef")))
                                    .pushingNothing());
                    assertRecordedToken(
                        WORKSPACE_TOKEN_PUSHING_NOTHING, issued.tokenId(), issued.subject());
                  }),
              ContractRow.of(
                  Trigger.operation("dispatchAgent"),
                  AGENT_TOKEN_REFUSED,
                  "commissionToken",
                  basic(agentCommission(AGENT_TOKEN_REFUSED, "${refusedGitRef}")),
                  List.of(),
                  (url, params) -> {
                    IdpCredentialCommissioner commissioner = commissionerAgainst(url, params);
                    assertRefused(
                        () ->
                            commissioner.tokens.commission(
                                basicOf(params),
                                agentTokenRequest(params, List.of(params.get("refusedGitRef")))));
                  }),
              ContractRow.of(
                  Trigger.operation("dispatchAgent"),
                  AGENT_TOKEN_PUSHING_NOTHING,
                  "commissionToken",
                  basic(agentCommission(AGENT_TOKEN_PUSHING_NOTHING)),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    String gitRef = params.get("gitRef");
                    IdpTokens.TokenResponse issued =
                        commissionerAgainst(url, params)
                            .tokens
                            .commission(
                                basicOf(params),
                                agentTokenRequest(params, List.of(gitRef, gitRef + "-*"))
                                    .pushingNothing());
                    assertRecordedToken(
                        AGENT_TOKEN_PUSHING_NOTHING, issued.tokenId(), issued.subject());
                  })));

  private IdpContract() {}

  // --- request bodies ----------------------------------------------------------------------------

  /** {@code request} with the state's Basic pair. */
  private static Request basic(DslPart body) {
    return Request.json(body).header("Authorization", BASIC);
  }

  /** {@code state}'s pinned value of {@code name}: the example a provider-state expression carries. */
  private static String param(String state, String name) {
    String value = GoldenMasters.params(GoldenMasters.IDP, state).get(name);
    if (value == null) {
      throw new IllegalStateException("qits-idp's '" + state + "' records no param " + name);
    }
    return value;
  }

  /**
   * A workspace row's commission: its kind, its row id, its project claim, the refs it may push —
   * each a provider-state expression of {@code state}'s params ({@code ${gitRef}}, {@code
   * ${refusedGitRef}}); no ref at all is the {@code gitRefs: []} retry.
   */
  private static DslPart workspaceCommission(String state, String... gitRefs) {
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringValue("contextKind", "workspace")
            .valueFromProviderState("contextId", "${workspaceId}", param(state, "workspaceId"));
    body.object("claims")
        .valueFromProviderState("project", "${projectId}", param(state, "projectId"))
        .closeObject();
    return refs(body, state, gitRefs);
  }

  /** An agent's token: the workspace kind, {@code agent-<id>}, its project and agent claims. */
  private static DslPart agentCommission(String state, String... gitRefs) {
    String agent = param(state, "agentId");
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringValue("contextKind", "workspace")
            .valueFromProviderState(
                "contextId",
                CredentialCommissioner.AGENT_CONTEXT_PREFIX + "${agentId}",
                CredentialCommissioner.AGENT_CONTEXT_PREFIX + agent);
    body.object("claims")
        .valueFromProviderState("project", "${projectId}", param(state, "projectId"))
        .valueFromProviderState("agent", "${agentId}", agent)
        .closeObject();
    return refs(body, state, gitRefs);
  }

  /** {@code gitRefs}, each an expression whose example is that expression over the state's params. */
  private static DslPart refs(PactDslJsonBody body, String state, String... expressions) {
    var array = body.array("gitRefs");
    Map<String, String> params = GoldenMasters.params(GoldenMasters.IDP, state);
    for (String expression : expressions) {
      String example = expression;
      for (var param : params.entrySet()) {
        example = example.replace("${" + param.getKey() + "}", param.getValue());
      }
      array.valueFromProviderState(expression, example);
    }
    return array.closeArray();
  }

  /** The workspace row a state names: its {@code workspaceId} param. */
  private static Long workspaceId(Map<String, String> params) {
    return Long.valueOf(params.get("workspaceId"));
  }

  /** The request {@code commissionToken} builds for a workspace row, stating {@code gitRefs}. */
  private static IdpTokens.TokenRequest workspaceTokenRequest(
      Map<String, String> params, List<String> gitRefs) {
    return new IdpTokens.TokenRequest(
        "workspace",
        params.get("workspaceId"),
        Map.of(CredentialCommissioner.PROJECT_CLAIM, params.get("projectId")),
        gitRefs);
  }

  /** The request {@code commissionAgentToken} builds, stating {@code gitRefs}. */
  private static IdpTokens.TokenRequest agentTokenRequest(
      Map<String, String> params, List<String> gitRefs) {
    String agent = params.get("agentId");
    return new IdpTokens.TokenRequest(
        "workspace",
        CredentialCommissioner.AGENT_CONTEXT_PREFIX + agent,
        Map.of(
            CredentialCommissioner.PROJECT_CLAIM,
            params.get("projectId"),
            CredentialCommissioner.AGENT_CLAIM,
            agent),
        gitRefs);
  }

  /** A 400, read the way the commissioner reads it to retry with {@code gitRefs: []}. */
  private static void assertRefused(org.junit.jupiter.api.function.Executable call) {
    WebApplicationException refused = assertThrows(WebApplicationException.class, call);
    assertEquals(400, IdpCredentialCommissioner.status(refused));
  }

  private static String basicOf(Map<String, String> params) {
    String[] pair = pair(params);
    return IdpCredentialCommissioner.basic(pair[0], pair[1]);
  }

  // --- the clients -------------------------------------------------------------------------------

  /** The client id and secret the state's {@code authorization} param is the Basic header of. */
  private static String[] pair(Map<String, String> params) {
    String header = params.get("authorization");
    if (header == null || !header.startsWith("Basic ")) {
      return new String[] {"dev-qits-workspaces", "service-secret"};
    }
    String decoded =
        new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
    int colon = decoded.indexOf(':');
    return new String[] {decoded.substring(0, colon), decoded.substring(colon + 1)};
  }

  /** The bean wired to the mock server, as {@code IdpCredentialCommissionerTest} wires it. */
  static IdpCredentialCommissioner commissionerAgainst(String mockUrl, Map<String, String> params) {
    String[] pair = pair(params);
    IdpCredentialCommissioner commissioner = new IdpCredentialCommissioner();
    commissioner.enabled = true;
    commissioner.clientId = Optional.of(pair[0]);
    commissioner.clientSecret = Optional.of(pair[1]);
    commissioner.patience = Duration.ofSeconds(1);
    commissioner.clients = restClient(mockUrl, IdpClients.class);
    commissioner.tokens = restClient(mockUrl, IdpTokens.class);
    return commissioner;
  }

  private static <T> T restClient(String mockUrl, Class<T> type) {
    return QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(mockUrl + "/idp")).build(type);
  }

  /**
   * The shipped {@code qits} client's configuration with only its address and pair moved to the
   * mock server and the state: the grant, the audience, the token path and the client
   * authentication are what ships.
   */
  /** {@code trigger} handing a workspace's or an agent's token back: 204 for a token idp holds. */
  private static ContractRow tokenHandback(Trigger trigger) {
    return ContractRow.of(
        trigger,
        A_TOKEN,
        "deleteToken",
        basic(null),
        List.of(),
        (url, params) -> commissionerAgainst(url, params).deleteToken(params.get("tokenId")));
  }

  static String mintAgainst(String mockUrl, Map<String, String> params) throws Exception {
    String[] pair = pair(params);
    OidcClientConfig shipped =
        Arc.container().instance(OidcClientsConfig.class).get().namedClients().get("qits");
    OidcClientConfig config =
        OidcClientConfig.builder(shipped)
            .id("contract-qits")
            .clientEnabled(true)
            .authServerUrl(mockUrl + "/idp")
            .clientId(pair[0])
            .credentials(pair[1])
            .build();
    OidcClients clients = Arc.container().instance(OidcClients.class).get();
    OidcClient client = clients.newClient(config).await().atMost(Duration.ofSeconds(10));
    try {
      return client.getTokens().await().atMost(Duration.ofSeconds(10)).getAccessToken();
    } finally {
      client.close();
    }
  }

  private static void assertRecordedToken(String state, String tokenId, String subject) {
    JsonNode recorded = GoldenMasters.json(GoldenMasters.IDP, state, "commissionToken");
    assertEquals(recorded.path("tokenId").asText(), tokenId);
    assertEquals(recorded.path("subject").asText(), subject);
  }

  private static JsonNode getJson(String url) throws Exception {
    try (HttpClient http = HttpClient.newHttpClient()) {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), url);
      return new ObjectMapper().readTree(response.body());
    }
  }
}
