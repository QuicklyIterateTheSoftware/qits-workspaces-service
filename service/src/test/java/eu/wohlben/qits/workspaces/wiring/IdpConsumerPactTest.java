package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.WorkspaceCredential;
import eu.wohlben.qits.workspaces.control.WorkspaceToken;
import eu.wohlben.qits.workspaces.testing.contracts.ConsumerPacts;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer half of the qits-idp contract</b>: the commission doors for clients ({@code
 * /idp/api/clients}) and tokens ({@code /idp/api/tokens}), each made by a real {@link
 * IdpCredentialCommissioner} over the real generated clients, against a pact mock server.
 *
 * <p><b>Every row waits on qits-idp.</b> qits-idp publishes no golden masters yet, so no row can
 * build its answer, and each test is disabled naming the provider state it needs. The operationIds
 * are proposed here: qits-idp's controllers declare none. When the recordings exist, pin {@code
 * eu.wohlben.qits:qits-idp-golden-masters} (test scope), enable the rows, and add a pact-file test
 * and the {@code release.yml} {@code pacts:} entry the way {@code ProjectsPactFileTest} does.
 *
 * <p><b>The base url ends in {@code /idp}</b>, as {@code quarkus.oidc-client.qits.auth-server-url}
 * does in every deployment, so the recorded paths are expected under {@code /idp/api/...}.
 *
 * <p>Triggers: a client or a token is commissioned and its Git refs narrowed when a workspace is
 * created ({@code createWorkspace}; also {@code dispatchAgent}, which creates one, and the runner
 * doors, which commission runner credentials); the listings and the deletes are the hourly {@code
 * CommissionReconciler.reconcile}, and a workspace's resolve or delete also deletes its own.
 */
@QuarkusTest
@Disabled("needs qits-idp-service golden masters — each row names its provider state")
public class IdpConsumerPactTest {

  static final String MAY_COMMISSION = "the workspaces service may commission credentials";
  static final String HOLDS_CLIENTS = "the workspaces service holds client commissions";
  static final String A_CLIENT = "a client commission of the workspaces service exists";
  static final String HOLDS_TOKENS = "the workspaces service holds tokens";
  static final String A_TOKEN = "a token of the workspaces service exists";

  static final Trigger CREATE = Trigger.operation("createWorkspace");
  static final Trigger RECONCILE = Trigger.schedule("CommissionReconciler.reconcile");

  // --- clients -----------------------------------------------------------------------------------

  @Test
  @Disabled(
      "needs provider state 'the workspaces service may commission credentials' for"
          + " commissionClient in qits-idp-service")
  public void commissionClient() {
    String op = "commissionClient";
    run(
        MAY_COMMISSION,
        op,
        CREATE,
        commissionBody(),
        List.of("$.clientId", "$.secret"),
        url -> {
          Optional<WorkspaceCredential> issued =
              commissionerAgainst(url).commission(7L, "a-project", List.of("refs/heads/ws/7"), false);
          JsonNode recorded = GoldenMasters.json(GoldenMasters.IDP, MAY_COMMISSION, op);
          assertEquals(
              Optional.of(
                  new WorkspaceCredential(
                      recorded.path("clientId").asText(), recorded.path("secret").asText())),
              issued);
        });
  }

  @Test
  @Disabled(
      "needs provider state 'the workspaces service holds client commissions' for listClients in"
          + " qits-idp-service")
  public void listClients() {
    run(
        HOLDS_CLIENTS,
        "listClients",
        RECONCILE,
        null,
        List.of("$[*].clientId", "$[*].contextKind", "$[*].contextId"),
        url -> {
          List<CredentialCommissioner.Commission> held = commissionerAgainst(url).list();
          assertFalse(held.isEmpty(), "the recording holds commissions");
          held.forEach(c -> assertTrue(c.clientId() != null && !c.clientId().isBlank()));
        });
  }

  @Test
  @Disabled(
      "needs provider state 'a client commission of the workspaces service exists' for"
          + " decommissionClient in qits-idp-service")
  public void decommissionClient() {
    run(
        A_CLIENT,
        "decommissionClient",
        RECONCILE,
        null,
        List.of(),
        url ->
            commissionerAgainst(url)
                .decommission(GoldenMasters.params(GoldenMasters.IDP, A_CLIENT).get("clientId")));
  }

  @Test
  @Disabled(
      "needs provider state 'a client commission of the workspaces service exists' for"
          + " replaceClientGitRefs in qits-idp-service")
  public void replaceClientGitRefs() {
    run(
        A_CLIENT,
        "replaceClientGitRefs",
        CREATE,
        gitRefsBody(),
        List.of(),
        url ->
            commissionerAgainst(url)
                .updateGitRefs(
                    GoldenMasters.params(GoldenMasters.IDP, A_CLIENT).get("clientId"),
                    List.of("refs/heads/ws/7")));
  }

  // --- tokens ------------------------------------------------------------------------------------

  @Test
  @Disabled(
      "needs provider state 'the workspaces service may commission credentials' for"
          + " commissionToken in qits-idp-service")
  public void commissionToken() {
    String op = "commissionToken";
    run(
        MAY_COMMISSION,
        op,
        CREATE,
        commissionBody(),
        List.of("$.tokenId", "$.token", "$.subject"),
        url -> {
          Optional<WorkspaceToken> issued =
              commissionerAgainst(url)
                  .commissionToken(7L, "a-project", List.of("refs/heads/ws/7"), false);
          JsonNode recorded = GoldenMasters.json(GoldenMasters.IDP, MAY_COMMISSION, op);
          assertEquals(recorded.path("tokenId").asText(), issued.orElseThrow().tokenId());
          assertEquals(recorded.path("subject").asText(), issued.orElseThrow().subject());
        });
  }

  @Test
  @Disabled(
      "needs provider state 'the workspaces service holds tokens' for listTokens in"
          + " qits-idp-service")
  public void listTokens() {
    run(
        HOLDS_TOKENS,
        "listTokens",
        RECONCILE,
        null,
        List.of("$[*].tokenId", "$[*].contextKind", "$[*].contextId", "$[*].createdAt"),
        url -> assertFalse(commissionerAgainst(url).listTokens().isEmpty()));
  }

  @Test
  @Disabled(
      "needs provider state 'a token of the workspaces service exists' for deleteToken in"
          + " qits-idp-service")
  public void deleteToken() {
    run(
        A_TOKEN,
        "deleteToken",
        RECONCILE,
        null,
        List.of(),
        url ->
            commissionerAgainst(url)
                .deleteToken(GoldenMasters.params(GoldenMasters.IDP, A_TOKEN).get("tokenId")));
  }

  @Test
  @Disabled(
      "needs provider state 'a token of the workspaces service exists' for replaceTokenGitRefs in"
          + " qits-idp-service")
  public void replaceTokenGitRefs() {
    run(
        A_TOKEN,
        "replaceTokenGitRefs",
        CREATE,
        gitRefsBody(),
        List.of(),
        url ->
            commissionerAgainst(url)
                .updateTokenGitRefs(
                    GoldenMasters.params(GoldenMasters.IDP, A_TOKEN).get("tokenId"),
                    List.of("refs/heads/ws/7")));
  }

  // --- support -----------------------------------------------------------------------------------

  private static void run(
      String state,
      String operationId,
      Trigger trigger,
      DslPart requestBody,
      List<String> consumes,
      Consumer<String> call) {
    ConsumerPacts.verify(
        ConsumerPacts.pact(
            GoldenMasters.IDP,
            builder ->
                GoldenMasters.interaction(
                    builder, GoldenMasters.IDP, state, operationId, trigger, requestBody, consumes)),
        call);
  }

  /** What a workspace commission sends: its context, its project claim, the refs it may push. */
  private static DslPart commissionBody() {
    PactDslJsonBody body =
        new PactDslJsonBody().stringType("contextKind", "workspace").stringType("contextId", "7");
    body.object("claims").stringType("project", "a-project").closeObject();
    return body.array("gitRefs").stringType("refs/heads/ws/7").closeArray();
  }

  private static DslPart gitRefsBody() {
    return new PactDslJsonBody().array("gitRefs").stringType("refs/heads/ws/7").closeArray();
  }

  /** The bean wired to the mock server, as {@code IdpCredentialCommissionerTest} wires it. */
  private static IdpCredentialCommissioner commissionerAgainst(String mockUrl) {
    String base = mockUrl + "/idp";
    IdpCredentialCommissioner commissioner = new IdpCredentialCommissioner();
    commissioner.enabled = true;
    commissioner.clientId = Optional.of("dev-qits-workspaces");
    commissioner.clientSecret = Optional.of("service-secret");
    commissioner.patience = Duration.ofSeconds(1);
    commissioner.clients =
        QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(base)).build(IdpClients.class);
    commissioner.tokens =
        QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(base)).build(IdpTokens.class);
    return commissioner;
  }
}
