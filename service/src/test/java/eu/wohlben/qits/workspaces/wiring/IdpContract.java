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
import eu.wohlben.qits.workspaces.control.WorkspaceCredential;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * <b>What qits-workspaces asks qits-idp, and why</b> (qits-1149) — the table {@code
 * IdpConsumerPactTest} runs against pact mock servers and {@code ConsumerPactFilesTest} writes to
 * {@code pacts/qits-workspaces-service_qits-idp-service.json}.
 *
 * <p><b>Four callers, one provider.</b>
 *
 * <ul>
 *   <li>{@link IdpCredentialCommissioner} — the commission doors for a workspace's credential: a
 *       client pair ({@code /idp/api/clients}) or a token ({@code /idp/api/tokens}, a workspace's
 *       or an agent's), commissioned when the scheduler starts a slot or places an agent ({@code
 *       AgentService.reconcile}), listed, narrowed and handed back by {@code
 *       CommissionReconciler.reconcile}.
 *   <li>{@link IdpRunnerCommissioner} — a runner's registration token ({@code createRunner}, {@code
 *       rotateRunnerRegistrationToken}) and its own client, with the spent token deleted ({@code
 *       registerRunner}).
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

  // qits-idp's state names (golden masters 2026.1018.165736).
  static final String SYSTEM_CLIENT = "a service client with the system role";
  static final String A_CLIENT = "a commissioned client";
  static final String A_TOKEN = "a commissioned token";
  static final String SIGNING_KEY = "a published signing key";
  static final String NO_CLIENT = "no commissioned client with the given id";
  static final String NO_TOKEN = "no commissioned token with the given id";
  static final String PEER_CALLER = "a service client calling its peers";

  /** A commission whose stated Git refs qits-idp refuses with 400. */
  static final String REFUSES_GIT_REFS = "a service client whose stated Git refs are refused";

  static final Trigger RECONCILE = Trigger.schedule("AgentService.reconcile");
  static final Trigger COMMISSIONS = Trigger.schedule("CommissionReconciler.reconcile");
  static final Trigger CREATE_RUNNER = Trigger.operation("createRunner");
  static final Trigger ROTATE = Trigger.operation("rotateRunnerRegistrationToken");
  static final Trigger REGISTER = Trigger.operation("registerRunner");

  private static final String BASIC = "{authorization}";
  private static final UUID RUNNER = UUID.fromString("00000000-0000-4000-8000-000000000020");
  private static final String AGENT = "00000000-0000-4000-8000-0000000000a1";

  public static final ContractTable TABLE =
      new ContractTable(
          GoldenMasters.IDP,
          List.of(
              // --- a workspace's credential ---------------------------------------------------
              ContractRow.of(
                  RECONCILE,
                  SYSTEM_CLIENT,
                  "commissionClient",
                  basic(workspaceCommission("7")),
                  List.of("$.clientId", "$.secret"),
                  (url, params) -> {
                    Optional<WorkspaceCredential> issued =
                        commissionerAgainst(url, params)
                            .commission(7L, "a-project", List.of("refs/heads/ws/7"), false);
                    JsonNode recorded = GoldenMasters.json(GoldenMasters.IDP, SYSTEM_CLIENT, "commissionClient");
                    assertEquals(
                        Optional.of(
                            new WorkspaceCredential(
                                recorded.path("clientId").asText(), recorded.path("secret").asText())),
                        issued);
                  }),
              ContractRow.of(
                  RECONCILE,
                  SYSTEM_CLIENT,
                  "commissionToken",
                  basic(workspaceCommission("7")),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    WorkspaceToken issued =
                        commissionerAgainst(url, params)
                            .commissionToken(7L, "a-project", List.of("refs/heads/ws/7"), false)
                            .orElseThrow();
                    assertRecordedToken(issued.tokenId(), issued.subject());
                  }),
              // An agent's own token (qits-1152, qits-1213): the workspace kind, context agent-<id>,
              // minted when the dispatch door records the agent (AgentService.mintToken).
              ContractRow.of(
                  Trigger.operation("dispatchAgent"),
                  SYSTEM_CLIENT,
                  "commissionToken",
                  basic(agentCommission()),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    WorkspaceToken issued =
                        commissionerAgainst(url, params)
                            .commissionAgentToken(
                                AGENT,
                                "a-project",
                                List.of("refs/heads/ticket/a", "refs/heads/ticket/a-*"),
                                false)
                            .orElseThrow();
                    assertRecordedToken(issued.tokenId(), issued.subject());
                  }),
              ContractRow.of(
                  COMMISSIONS,
                  A_CLIENT,
                  "listClients",
                  basic(null),
                  List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
                  (url, params) -> {
                    List<CredentialCommissioner.Commission> held =
                        commissionerAgainst(url, params).list();
                    assertFalse(held.isEmpty(), "the recording holds a commission");
                    held.forEach(c -> assertEquals(params.get("clientId"), c.clientId()));
                  }),
              ContractRow.of(
                  COMMISSIONS,
                  A_CLIENT,
                  "decommissionClient",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params).decommission(params.get("clientId"))),
              ContractRow.of(
                  COMMISSIONS,
                  A_CLIENT,
                  "replaceClientGitRefs",
                  basic(gitRefsBody()),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params)
                          .updateGitRefs(params.get("clientId"), List.of("refs/heads/ws/7"))),
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
              ContractRow.of(
                  COMMISSIONS,
                  A_TOKEN,
                  "replaceTokenGitRefs",
                  basic(gitRefsBody()),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params)
                          .updateTokenGitRefs(params.get("tokenId"), List.of("refs/heads/ws/7"))),
              // --- a runner's credentials ------------------------------------------------------
              ContractRow.of(
                  CREATE_RUNNER,
                  SYSTEM_CLIENT,
                  "commissionToken",
                  basic(registrationCommission()),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    IdpRunnerCommissioner.IssuedToken issued =
                        runnerCommissionerAgainst(url, params).registrationToken(RUNNER);
                    assertRecordedToken(issued.tokenId(), issued.subject());
                  }),
              ContractRow.of(
                  ROTATE,
                  SYSTEM_CLIENT,
                  "commissionToken",
                  basic(registrationCommission()),
                  List.of("$.tokenId", "$.token", "$.subject"),
                  (url, params) -> {
                    IdpRunnerCommissioner.IssuedToken issued =
                        runnerCommissionerAgainst(url, params).registrationToken(RUNNER);
                    assertRecordedToken(issued.tokenId(), issued.subject());
                  }),
              ContractRow.of(
                  REGISTER,
                  SYSTEM_CLIENT,
                  "commissionClient",
                  basic(
                      new PactDslJsonBody()
                          .stringValue("contextKind", IdpRunnerCommissioner.RUNNER_KIND)
                          .stringValue("contextId", RUNNER.toString())
                          .nullValue("claims")),
                  List.of("$.clientId", "$.secret"),
                  (url, params) -> {
                    IdpRunnerCommissioner.RunnerClient client =
                        runnerCommissionerAgainst(url, params).runnerClient(RUNNER);
                    JsonNode recorded =
                        GoldenMasters.json(GoldenMasters.IDP, SYSTEM_CLIENT, "commissionClient");
                    assertEquals(recorded.path("clientId").asText(), client.clientId());
                  }),
              ContractRow.of(
                  REGISTER,
                  A_TOKEN,
                  "deleteToken",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      assertTrue(
                          runnerCommissionerAgainst(url, params).deleteToken(params.get("tokenId")))),
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
              // exactly as the shipped `qits` client sends it. A handback (decommission, delete) of
              // a credential qits-idp no longer holds answers 404, which the commissioners read as
              // done; a narrowing's 404 throws, and the caller keeps the narrowing pending.
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
                  NO_CLIENT,
                  "decommissionClient",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params).decommission(params.get("clientId"))),
              ContractRow.of(
                  COMMISSIONS,
                  NO_CLIENT,
                  "replaceClientGitRefs",
                  basic(gitRefsBody()),
                  List.of(),
                  (url, params) ->
                      assertThrows(
                          IllegalStateException.class,
                          () ->
                              commissionerAgainst(url, params)
                                  .updateGitRefs(params.get("clientId"), List.of("refs/heads/ws/7")))),
              ContractRow.of(
                  COMMISSIONS,
                  NO_TOKEN,
                  "deleteToken",
                  basic(null),
                  List.of(),
                  (url, params) ->
                      commissionerAgainst(url, params).deleteToken(params.get("tokenId"))),
              ContractRow.of(
                  COMMISSIONS,
                  NO_TOKEN,
                  "replaceTokenGitRefs",
                  basic(gitRefsBody()),
                  List.of(),
                  (url, params) ->
                      assertThrows(
                          IllegalStateException.class,
                          () ->
                              commissionerAgainst(url, params)
                                  .updateTokenGitRefs(
                                      params.get("tokenId"), List.of("refs/heads/ws/7")))),
              // --- recorded since qits-idp's golden masters 2026.1018.165736 ----------------------
              // A commission whose stated Git refs qits-idp refuses (400): the commissioner asks
              // again with gitRefs: [] and fails closed. Both requests are this row's, but only the
              // refused one is qits-idp's in this state: the pact binds it (the 400 is read by its
              // status alone), and the ask again is answered here and checked to push nothing.
              ContractRow.of(
                  RECONCILE,
                  REFUSES_GIT_REFS,
                  "commissionClient",
                  basic(refusedCommission("7")),
                  List.of(),
                  (url, params) -> {
                    IdpCredentialCommissioner commissioner = commissionerAgainst(url, params);
                    List<IdpClients.CommissionRequest> askedAgain = new ArrayList<>();
                    commissioner.clients = refusedOnce(commissioner.clients, askedAgain);
                    Optional<WorkspaceCredential> issued =
                        commissioner.commission(
                            7L, "a-project", List.of(params.get("refusedGitRef")), false);
                    assertEquals(
                        List.of(List.of()),
                        askedAgain.stream().map(IdpClients.CommissionRequest::gitRefs).toList(),
                        "asked again once, pushing nothing");
                    assertTrue(issued.isPresent(), "the workspace still launches");
                  })));

  private IdpContract() {}

  // --- request bodies ----------------------------------------------------------------------------

  /** {@code request} with the state's Basic pair. */
  private static Request basic(DslPart body) {
    return Request.json(body).header("Authorization", BASIC);
  }

  /** A workspace row's commission: its kind, its row id, its project claim, the refs it may push. */
  private static DslPart workspaceCommission(String rowId) {
    PactDslJsonBody body =
        new PactDslJsonBody().stringValue("contextKind", "workspace").stringValue("contextId", rowId);
    body.object("claims").stringType("project", "a-project").closeObject();
    return body.array("gitRefs").stringType("refs/heads/ws/7").closeArray();
  }

  /**
   * {@link #workspaceCommission} stating the ref qits-idp's state refuses: its {@code refusedGitRef}
   * param, so the verifier sends what the state returned.
   */
  private static DslPart refusedCommission(String rowId) {
    PactDslJsonBody body =
        new PactDslJsonBody().stringValue("contextKind", "workspace").stringValue("contextId", rowId);
    body.object("claims").stringType("project", "a-project").closeObject();
    return body.array("gitRefs")
        .valueFromProviderState(
            "${refusedGitRef}",
            GoldenMasters.params(GoldenMasters.IDP, REFUSES_GIT_REFS).get("refusedGitRef"))
        .closeArray();
  }

  /**
   * {@code mock} for the first commission, which qits-idp's state refuses; every commission after it
   * is collected in {@code askedAgain} and answered with a credential, never reaching the mock.
   */
  private static IdpClients refusedOnce(
      IdpClients mock, List<IdpClients.CommissionRequest> askedAgain) {
    boolean[] refused = {false};
    return (IdpClients)
        Proxy.newProxyInstance(
            IdpClients.class.getClassLoader(),
            new Class<?>[] {IdpClients.class},
            (proxy, method, args) -> {
              if (method.getName().equals("commission") && refused[0]) {
                askedAgain.add((IdpClients.CommissionRequest) args[1]);
                return new IdpClients.CommissionResponse("asked-again", "asked-again-secret");
              }
              if (method.getName().equals("commission")) {
                refused[0] = true;
              }
              try {
                return method.invoke(mock, args);
              } catch (InvocationTargetException e) {
                throw e.getCause();
              }
            });
  }

  /** An agent's token: the workspace kind, {@code agent-<id>}, its project and agent claims. */
  private static DslPart agentCommission() {
    PactDslJsonBody body =
        new PactDslJsonBody()
            .stringValue("contextKind", "workspace")
            .stringValue("contextId", CredentialCommissioner.AGENT_CONTEXT_PREFIX + AGENT);
    body.object("claims")
        .stringType("project", "a-project")
        .stringValue("agent", AGENT)
        .closeObject();
    return body.array("gitRefs")
        .stringType("refs/heads/ticket/a")
        .stringType("refs/heads/ticket/a-*")
        .closeArray();
  }

  /** A runner's registration token: its kind and the runner id, nothing else. */
  private static DslPart registrationCommission() {
    return new PactDslJsonBody()
        .stringValue("contextKind", IdpRunnerCommissioner.REGISTRATION_KIND)
        .stringValue("contextId", RUNNER.toString());
  }

  private static DslPart gitRefsBody() {
    return new PactDslJsonBody().array("gitRefs").stringType("refs/heads/ws/7").closeArray();
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

  static IdpRunnerCommissioner runnerCommissionerAgainst(String mockUrl, Map<String, String> params) {
    String[] pair = pair(params);
    IdpRunnerCommissioner commissioner = new IdpRunnerCommissioner();
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

  private static void assertRecordedToken(String tokenId, String subject) {
    JsonNode recorded = GoldenMasters.json(GoldenMasters.IDP, SYSTEM_CLIENT, "commissionToken");
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
