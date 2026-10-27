package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.WorkspaceToken;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link IdpCredentialCommissioner} against a real HTTP server and a real generated client, for the
 * reason {@code HttpRepositoryLookupTest} gives at length: what is worth pinning is which failures
 * are an answer and which are a moment, and that mapping depends on the exception types the client
 * actually throws. A mocked client would assert my assumptions about those types instead.
 *
 * <p>A {@code com.sun.net.httpserver} stub on an ephemeral port — already in the JDK, no container —
 * and the bean constructed by hand so each case can set the switch and the credential it is about,
 * including the unwired one no injected configuration could express.
 */
@QuarkusTest
public class IdpCredentialCommissionerTest {

  /** A project id in the shape the platform mints them — the value that reaches the claim. */
  private static final String A_PROJECT = "b03b84b1-1875-4071-9dbf-854550156258";

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> authorizations = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** One canned answer per request, in order; the last one repeats once the list runs out. */
  private String serve(List<Answer> answers) throws Exception {
    AtomicInteger seen = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          String authorization = exchange.getRequestHeaders().getFirst("Authorization");
          authorizations.add(authorization == null ? "" : authorization);
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          Answer answer =
              answers.get(Math.min(seen.getAndIncrement(), answers.size() - 1));
          byte[] out =
              answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
          try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(out);
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private record Answer(int status, String body) {}

  /** The bean wired to {@code baseUrl}, with a real generated client pointed at the same place. */
  private IdpCredentialCommissioner commissionerAgainst(String baseUrl) {
    IdpCredentialCommissioner commissioner = new IdpCredentialCommissioner();
    commissioner.enabled = true;
    commissioner.clientId = Optional.of("dev-qits-workspaces");
    commissioner.clientSecret = Optional.of("service-secret");
    // Short, so a case about holding through a window costs a second rather than thirty.
    commissioner.patience = Duration.ofSeconds(1);
    commissioner.clients =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(baseUrl))
            .build(IdpClients.class);
    commissioner.tokens =
        QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(baseUrl)).build(IdpTokens.class);
    return commissioner;
  }

  /** A minted token, as qits-idp answers one. */
  private static Answer minted(String tokenId) {
    return new Answer(
        201,
        "{\"tokenId\":\""
            + tokenId
            + "\",\"token\":\"qits_tok_"
            + tokenId
            + "\",\"subject\":\"tok-workspace-"
            + tokenId
            + "\"}");
  }

  @Test
  public void anUnresolvedProjectCostsTheScopeAndNotTheLaunch() throws Exception {
    // The registry could not name the project. That is a moment, not a verdict, and a workspace that
    // does not start is a worse answer than a token scoped as every one of them was before.
    // No member at all — never a blank value and never "*", both of which qits-idp refuses.
    String url = serve(List.of(minted("t-7")));

    assertTrue(commissionerAgainst(url).commissionToken(7L, null, List.of()).isPresent());
    assertFalse(bodies.get(0).contains("project"), bodies.get(0));
    assertFalse(bodies.get(0).contains("*"), bodies.get(0));

    // Blank and whitespace are the same fact as absent, and travel the same way.
    commissionerAgainst(url).commissionToken(8L, "   ", List.of());
    assertFalse(bodies.get(1).contains("project"), bodies.get(1));
  }

  @Test
  public void withTheSwitchOffNothingIsWiredAndNobodyIsCalled() throws Exception {
    String url = serve(List.of(new Answer(500, null)));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    commissioner.enabled = false;

    // The shipped posture. Not wired is a configuration, not a failure — the same standing as no
    // implementation at all — so a workspace launches with no credential and no error.
    assertFalse(commissioner.isWired());
    assertEquals(Optional.empty(), commissioner.commissionToken(7L, A_PROJECT, null));
    commissioner.decommission("ws-7-a");
    commissioner.deleteToken("t-1");
    assertEquals(List.of(), commissioner.list());
    assertEquals(List.of(), commissioner.listTokens());
    assertEquals(List.of(), requests, "an unwired commissioner dials nothing");
  }

  @Test
  public void aSecretlessDeploymentIsUnwiredToo() throws Exception {
    String url = serve(List.of(new Answer(500, null)));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    commissioner.clientSecret = Optional.empty();

    // The switch on but no secret in the process is the same state: there is nothing to
    // authenticate with, so there is nothing to mint against.
    assertFalse(commissioner.isWired());
    assertEquals(Optional.empty(), commissioner.commissionToken(7L, A_PROJECT, List.of()));
    assertEquals(List.of(), requests);
  }

  /**
   * Wired is a question about the configuration alone (qits-876): a DIRECT provision with no edge
   * plane asks it to decide whether to refuse, and the answer costs no call.
   */
  @Test
  public void aWiredDeploymentSaysSoWithoutCallingAnybody() throws Exception {
    String url = serve(List.of(new Answer(500, null)));

    assertTrue(commissionerAgainst(url).isWired());
    assertEquals(List.of(), requests);
  }

  @Test
  public void aWindowIsHeldThroughAndTheAttemptAfterItLands() throws Exception {
    // The measured failure mode: a redeploy replaces qits-platform-idp and the next calls answer
    // 401 while the later ones pass. A verdict-shaped status that is really about the moment.
    String url = serve(List.of(new Answer(401, "{\"error\":\"invalid_client\"}"), minted("t-8")));

    Optional<WorkspaceToken> issued =
        commissionerAgainst(url).commissionToken(7L, A_PROJECT, List.of());

    assertEquals("t-8", issued.orElseThrow().tokenId());
    assertEquals(2, requests.size(), "the first answer was held through, not taken as a verdict");
  }

  @Test
  public void anAnswerAboutTheRequestIsTakenAtItsWordAndFailsTheProvision() throws Exception {
    String url = serve(List.of(new Answer(400, "{\"error\":\"invalid_request\"}")));

    // Not a moment, so not retried — and it throws rather than answering empty, because a wired
    // issuer that refused is not the same fact as no issuer at all. The launch fails loudly.
    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> commissionerAgainst(url).commissionToken(7L, A_PROJECT, null));
    assertTrue(failure.getMessage().contains("workspace 7"), failure.getMessage());
    assertEquals(1, requests.size(), "a refusal is one attempt");
  }

  @Test
  public void anAlreadyGoneClientIsADecommissionThatSucceeded() throws Exception {
    String url = serve(List.of(new Answer(404, "{\"error\":\"not_found\"}")));

    // 404 is the state the call was asking for. A give-back must never throw — the reconcile and
    // the retired runner's give-back run it as housekeeping.
    commissionerAgainst(url).decommission("ws-7-a");
    assertEquals(List.of("DELETE /api/clients/ws-7-a"), requests);
  }

  @Test
  public void anUnreadableListingReapsNothingRatherThanEverything() throws Exception {
    String url = serve(List.of(new Answer(500, "{\"error\":\"server_error\"}")));

    // Empty is the safe shape of "nothing was learned": the reconcile decommissions what this
    // answers, so a blip must not read as "the issuer holds nothing".
    assertEquals(List.of(), commissionerAgainst(url).list());
  }

  @Test
  public void aListingCarriesTheContextEachCommissionWasMadeFor() throws Exception {
    String url =
        serve(
            List.of(
                new Answer(
                    200,
                    """
                    [{"clientId":"ws-7-a","owner":"dev-qits-workspaces",\
                    "contextKind":"workspace","contextId":"7","createdAt":"2026-08-14T07:00:00Z"},\
                    {"clientId":"run-3","owner":"dev-qits-workspaces",\
                    "contextKind":"run","contextId":"3","createdAt":"2026-08-14T07:00:00Z"}]""")));

    List<CredentialCommissioner.Commission> held = commissionerAgainst(url).list();

    assertEquals(
        List.of(
            new CredentialCommissioner.Commission("ws-7-a", "workspace", "7"),
            new CredentialCommissioner.Commission("run-3", "run", "3")),
        held);
    // The kind travels because the reconcile filters on it: a client commissioned for something
    // that is not a workspace is not the workspace rule's to sweep.
    assertFalse(held.get(1).contextKind().equals(CredentialCommissioner.CONTEXT_KIND));
  }

  @Test
  public void aMintThatStatesNoGitRefsSendsNoMember() throws Exception {
    String url = serve(List.of(minted("t-7")));

    commissionerAgainst(url).commissionToken(7L, A_PROJECT, null);

    // Not stated means no git_refs claim at all — not an explicit null for the idp to read.
    assertFalse(bodies.get(0).contains("gitRefs"), bodies.get(0));
  }

  @Test
  public void aRefusedGitRefListIsMintedAgainPushingNothingAndSaysSo() throws Exception {
    // An idp without C2 ignores the member, so a 400 is a C2 idp refusing the list. Fail closed:
    // the workspace launches with [] and never without the member.
    String url =
        serve(
            List.of(
                new Answer(
                    400,
                    "{\"error\":\"invalid_request\",\"error_description\":\"gitRefs has 501"
                        + " entries\"}"),
                minted("t-9")));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    List<LogRecord> errors = new CopyOnWriteArrayList<>();
    Handler capture =
        new Handler() {
          @Override
          public void publish(LogRecord logged) {
            if (logged.getLevel().intValue() >= Level.SEVERE.intValue()) {
              errors.add(logged);
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(IdpCredentialCommissioner.class.getName());
    logger.addHandler(capture);
    Optional<WorkspaceToken> issued;
    try {
      issued = commissioner.commissionToken(7L, A_PROJECT, List.of("refs/heads/ticket/x"));
    } finally {
      logger.removeHandler(capture);
    }

    assertEquals("t-9", issued.orElseThrow().tokenId());
    assertEquals(List.of("POST /api/tokens", "POST /api/tokens"), requests);
    assertTrue(bodies.get(0).contains("\"gitRefs\":[\"refs/heads/ticket/x\"]"), bodies.get(0));
    assertTrue(bodies.get(1).contains("\"gitRefs\":[]"), "may push nothing: " + bodies.get(1));
    assertTrue(
        bodies.get(1).contains("\"claims\":{\"project\":\"" + A_PROJECT + "\"}"),
        "the fallback keeps everything but the Git refs: " + bodies.get(1));
    assertEquals(1, errors.size(), "one ERROR per refused list");
    String logged = errors.get(0).getMessage();
    assertTrue(logged.contains("workspace 7"), logged);
    assertTrue(logged.contains("gitRefs has 501 entries"), "names the idp's reason: " + logged);
  }

  @Test
  public void aRefusedEmptyListIsAFailedLaunch() throws Exception {
    String url = serve(List.of(new Answer(400, "{\"error\":\"invalid_request\"}")));

    // There is nothing narrower to ask for, so the refusal is an answer about the request.
    assertThrows(
        RuntimeException.class,
        () -> commissionerAgainst(url).commissionToken(7L, A_PROJECT, List.of()));
    assertEquals(1, requests.size());
  }

  // --- the workspace token (qits-625, qits-802) -------------------------------------------------

  @Test
  public void aTokenIsMintedForTheWorkspaceContextWithItsProjectAndGitRefs() throws Exception {
    String url =
        serve(
            List.of(
                new Answer(
                    201,
                    "{\"tokenId\":\"t-1\",\"token\":\"qits_tok_abc\","
                        + "\"subject\":\"tok-workspace-t-1\",\"contextKind\":\"workspace\"}")));

    Optional<WorkspaceToken> issued =
        commissionerAgainst(url).commissionToken(7L, A_PROJECT, List.of("refs/heads/task/a"));

    assertEquals(Optional.of(new WorkspaceToken("t-1", "qits_tok_abc", "tok-workspace-t-1")), issued);
    assertEquals(List.of("POST /api/tokens"), requests);
    assertEquals(
        List.of(IdpCredentialCommissioner.basic("dev-qits-workspaces", "service-secret")),
        authorizations);
    assertTrue(bodies.get(0).contains("\"contextKind\":\"workspace\""), bodies.get(0));
    assertTrue(bodies.get(0).contains("\"contextId\":\"7\""), bodies.get(0));
    assertTrue(
        bodies.get(0).contains("\"claims\":{\"project\":\"" + A_PROJECT + "\"}"), bodies.get(0));
    assertTrue(bodies.get(0).contains("\"gitRefs\":[\"refs/heads/task/a\"]"), bodies.get(0));
    assertFalse(issued.orElseThrow().toString().contains("qits_tok_abc"), "never logged");
  }

  /**
   * An admin row's token is of the admin kind (qits-1084), picked by {@code contextKindFor}; an
   * unscoped one states no claims at all.
   */
  @Test
  public void anAdminRowsTokenIsMintedOfTheAdminKindAndTheEditorsUnscoped() throws Exception {
    String url =
        serve(
            List.of(
                new Answer(
                    201,
                    "{\"tokenId\":\"t-2\",\"token\":\"qits_tok_adm\","
                        + "\"subject\":\"tok-workspace-admin-t-2\"}"),
                new Answer(
                    201,
                    "{\"tokenId\":\"t-3\",\"token\":\"qits_tok_ed\","
                        + "\"subject\":\"tok-workspace-t-3\"}")));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);

    commissioner.commissionToken(8L, A_PROJECT, List.of("refs/heads/admin/a"), true);
    commissioner.commissionToken(9L, null, List.of(), false);

    assertTrue(bodies.get(0).contains("\"contextKind\":\"workspace-admin\""), bodies.get(0));
    assertTrue(bodies.get(0).contains("\"contextId\":\"8\""), bodies.get(0));
    assertTrue(bodies.get(1).contains("\"contextKind\":\"workspace\""), bodies.get(1));
    assertFalse(bodies.get(1).contains("\"claims\""), bodies.get(1));
  }

  /**
   * An agent's token states {@code agent} = the agent id beside {@code project} (qits-1213); a
   * slot's token, minted through the workspace door, states no {@code agent}.
   */
  @Test
  public void anAgentsTokenStatesTheAgentClaimAndASlotsDoesNot() throws Exception {
    String agentId = "07a79d74-b3ff-40a3-9b66-bceda7435a37";
    String url =
        serve(
            List.of(
                new Answer(
                    201,
                    "{\"tokenId\":\"t-4\",\"token\":\"qits_tok_agt\","
                        + "\"subject\":\"tok-workspace-t-4\"}"),
                new Answer(
                    201,
                    "{\"tokenId\":\"t-5\",\"token\":\"qits_tok_slot\","
                        + "\"subject\":\"tok-workspace-t-5\"}")));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);

    commissioner.commissionAgentToken(
        agentId, A_PROJECT, List.of("refs/heads/ticket/a", "refs/heads/ticket/a-*"), false);
    commissioner.commissionToken(10L, A_PROJECT, List.of(), false);

    assertTrue(bodies.get(0).contains("\"contextId\":\"agent-" + agentId + "\""), bodies.get(0));
    assertTrue(
        bodies
            .get(0)
            .contains(
                "\"claims\":{\"project\":\"" + A_PROJECT + "\",\"agent\":\"" + agentId + "\"}"),
        bodies.get(0));
    assertTrue(
        bodies.get(1).contains("\"claims\":{\"project\":\"" + A_PROJECT + "\"}"), bodies.get(1));
    assertFalse(bodies.get(1).contains("\"agent\""), bodies.get(1));
  }

  /** An agent whose project the registry cannot name still states who it is. */
  @Test
  public void anUnscopedAgentsTokenStatesOnlyTheAgentClaim() throws Exception {
    String url =
        serve(
            List.of(
                new Answer(
                    201,
                    "{\"tokenId\":\"t-6\",\"token\":\"qits_tok_u\","
                        + "\"subject\":\"tok-workspace-t-6\"}")));

    commissionerAgainst(url).commissionAgentToken("a-1", null, List.of(), false);

    assertTrue(bodies.get(0).contains("\"claims\":{\"agent\":\"a-1\"}"), bodies.get(0));
  }

  @Test
  public void aTokenIsDeletedByIdAndAnAlreadyGoneOneIsSuccess() throws Exception {
    String url = serve(List.of(new Answer(204, null), new Answer(404, "{}"), new Answer(500, "{}")));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);

    commissioner.deleteToken("t-1");
    commissioner.deleteToken("t-2");
    commissioner.deleteToken("t-3"); // a 500 is logged for the reconcile, never thrown

    assertEquals(
        List.of("DELETE /api/tokens/t-1", "DELETE /api/tokens/t-2", "DELETE /api/tokens/t-3"),
        requests);
  }

  @Test
  public void theTokenListingCarriesKindContextAndCreationAndAFailureIsEmpty() throws Exception {
    String url =
        serve(
            List.of(
                new Answer(
                    200,
                    "[{\"tokenId\":\"t-1\",\"subject\":\"tok-workspace-t-1\","
                        + "\"contextKind\":\"workspace\",\"contextId\":\"7\","
                        + "\"createdAt\":\"2026-10-05T10:00:00Z\"}]"),
                new Answer(500, "{}")));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);

    assertEquals(
        List.of(
            new CredentialCommissioner.TokenCommission(
                "t-1", "workspace", "7", java.time.Instant.parse("2026-10-05T10:00:00Z"))),
        commissioner.listTokens());
    assertEquals(List.of(), commissioner.listTokens());
  }
}
