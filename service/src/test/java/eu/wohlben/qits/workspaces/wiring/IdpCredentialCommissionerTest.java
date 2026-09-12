package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.workspaces.control.CredentialCommissioner;
import eu.wohlben.qits.workspaces.control.WorkspaceCredential;
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
    return commissioner;
  }

  @Test
  public void aCommissionNamesTheWorkspaceContextAndAuthenticatesAsThisService() throws Exception {
    String url =
        serve(List.of(new Answer(201, "{\"clientId\":\"ws-7-a\",\"secret\":\"s3cr3t\"}")));

    Optional<WorkspaceCredential> issued = commissionerAgainst(url).commission(7L, A_PROJECT);

    assertEquals(Optional.of(new WorkspaceCredential("ws-7-a", "s3cr3t")), issued);
    assertEquals(List.of("POST /api/clients"), requests);
    // client_secret_basic — the same spelling qits-idp's token endpoint already accepts, and the
    // whole reason this API needs no audience of its own.
    assertEquals(
        List.of(IdpCredentialCommissioner.basic("dev-qits-workspaces", "service-secret")),
        authorizations);
    // WHICH context this credential belongs to — the kind and the workspace row id, which is what
    // the reconcile compares against live workspaces.
    assertTrue(bodies.get(0).contains("\"contextKind\":\"workspace\""), bodies.get(0));
    assertTrue(bodies.get(0).contains("\"contextId\":\"7\""), bodies.get(0));
    // And what that context is ABOUT, which is what every resource service judges it on.
    assertTrue(
        bodies.get(0).contains("\"claims\":{\"project\":\"" + A_PROJECT + "\"}"), bodies.get(0));
  }

  @Test
  public void anUnresolvedProjectCostsTheScopeAndNotTheLaunch() throws Exception {
    // The registry could not name the project. That is a moment, not a verdict, and a workspace that
    // does not start is a worse answer than a credential scoped as every one of them was before.
    // No member at all — never a blank value and never "*", both of which qits-idp refuses.
    String url =
        serve(List.of(new Answer(201, "{\"clientId\":\"ws-7-a\",\"secret\":\"s3cr3t\"}")));

    assertEquals(
        Optional.of(new WorkspaceCredential("ws-7-a", "s3cr3t")),
        commissionerAgainst(url).commission(7L, null));
    assertFalse(bodies.get(0).contains("project"), bodies.get(0));
    assertFalse(bodies.get(0).contains("*"), bodies.get(0));

    // Blank and whitespace are the same fact as absent, and travel the same way.
    commissionerAgainst(url).commission(8L, "   ");
    assertFalse(bodies.get(1).contains("project"), bodies.get(1));
  }

  @Test
  public void anIdpThatPredatesTheClaimStillCommissions() throws Exception {
    // The deploy order this change ships in: this service moves before the issuer does. The
    // commission API ignores members it does not know, so the call lands and the credential is
    // simply unscoped until the issuer catches up — measured against the live idp, which answered
    // the access-denied of an unentitled caller rather than a 400 about the body.
    String url =
        serve(List.of(new Answer(201, "{\"clientId\":\"ws-9-a\",\"secret\":\"s3cr3t\"}")));

    assertEquals(
        Optional.of(new WorkspaceCredential("ws-9-a", "s3cr3t")),
        commissionerAgainst(url).commission(9L, A_PROJECT),
        "the answer is read for the pair and nothing else");
  }

  @Test
  public void withTheSwitchOffNothingIsCommissionedAndNobodyIsCalled() throws Exception {
    String url = serve(List.of(new Answer(500, null)));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    commissioner.enabled = false;

    // The shipped posture. Empty is a configuration, not a failure — the same standing as no
    // implementation at all — so a workspace launches with no credential and no error.
    assertEquals(Optional.empty(), commissioner.commission(7L, A_PROJECT));
    commissioner.decommission("ws-7-a");
    assertEquals(List.of(), commissioner.list());
    assertEquals(List.of(), requests, "an unwired commissioner dials nothing");
  }

  @Test
  public void aSecretlessDeploymentIsUnwiredToo() throws Exception {
    String url = serve(List.of(new Answer(500, null)));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    commissioner.clientSecret = Optional.empty();

    // The switch on but no secret in the process is the same state: there is nothing to
    // authenticate with, so there is nothing to commission against.
    assertEquals(Optional.empty(), commissioner.commission(7L, A_PROJECT));
    assertEquals(List.of(), requests);
  }

  @Test
  public void aWindowIsHeldThroughAndTheAttemptAfterItLands() throws Exception {
    // The measured failure mode: a redeploy replaces qits-platform-idp and the next calls answer
    // 401 while the later ones pass. A verdict-shaped status that is really about the moment.
    String url =
        serve(
            List.of(
                new Answer(401, "{\"error\":\"invalid_client\"}"),
                new Answer(201, "{\"clientId\":\"ws-7-b\",\"secret\":\"s3cr3t\"}")));

    Optional<WorkspaceCredential> issued = commissionerAgainst(url).commission(7L, A_PROJECT);

    assertEquals(Optional.of(new WorkspaceCredential("ws-7-b", "s3cr3t")), issued);
    assertEquals(2, requests.size(), "the first answer was held through, not taken as a verdict");
  }

  @Test
  public void anAnswerAboutTheRequestIsTakenAtItsWordAndFailsTheProvision() throws Exception {
    String url = serve(List.of(new Answer(400, "{\"error\":\"invalid_request\"}")));

    // Not a moment, so not retried — and it throws rather than answering empty, because a wired
    // issuer that refused is not the same fact as no issuer at all. The launch fails loudly.
    RuntimeException failure =
        assertThrows(RuntimeException.class, () -> commissionerAgainst(url).commission(7L, A_PROJECT));
    assertTrue(failure.getMessage().contains("workspace 7"), failure.getMessage());
    assertEquals(1, requests.size(), "a refusal is one attempt");
  }

  @Test
  public void anAlreadyGoneCredentialIsADecommissionThatSucceeded() throws Exception {
    String url = serve(List.of(new Answer(404, "{\"error\":\"not_found\"}")));

    // 404 is the state the call was asking for. A teardown must never throw — everything it runs
    // after has already happened.
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
    // The kind travels because the reconcile filters on it: a credential commissioned for something
    // that is not a workspace is not the workspace rule's to sweep.
    assertFalse(held.get(1).contextKind().equals(CredentialCommissioner.CONTEXT_KIND));
  }

  @Test
  public void aCommissionStatesTheGitRefsItWasGiven() throws Exception {
    String url =
        serve(List.of(new Answer(201, "{\"clientId\":\"ws-7-a\",\"secret\":\"s3cr3t\"}")));

    commissionerAgainst(url)
        .commission(7L, A_PROJECT, List.of("refs/heads/epic/e", "refs/heads/task/e/*"));

    // Contract C2: `gitRefs` beside `contextKind`, `contextId` and the claims.
    assertTrue(
        bodies.get(0).contains("\"gitRefs\":[\"refs/heads/epic/e\",\"refs/heads/task/e/*\"]"),
        bodies.get(0));
    assertTrue(bodies.get(0).contains("\"contextKind\":\"workspace\""), bodies.get(0));
  }

  @Test
  public void aCommissionThatStatesNoGitRefsSendsNoMember() throws Exception {
    String url =
        serve(List.of(new Answer(201, "{\"clientId\":\"ws-7-a\",\"secret\":\"s3cr3t\"}")));

    commissionerAgainst(url).commission(7L, A_PROJECT);

    // Not stated means no git_refs claim at all — not an explicit null for the idp to read.
    assertFalse(bodies.get(0).contains("gitRefs"), bodies.get(0));
  }

  @Test
  public void aRefusedGitRefListIsCommissionedAgainPushingNothing() throws Exception {
    // An idp without C2 ignores the member, so a 400 is a C2 idp refusing the list. Fail closed:
    // the workspace launches with [] and never without the member.
    String url =
        serve(
            List.of(
                new Answer(
                    400,
                    "{\"error\":\"invalid_request\",\"error_description\":\"gitRefs has 501"
                        + " entries\"}"),
                new Answer(201, "{\"clientId\":\"ws-7-b\",\"secret\":\"s3cr3t\"}")));
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
    Optional<WorkspaceCredential> issued;
    try {
      issued = commissioner.commission(7L, A_PROJECT, List.of("refs/heads/ticket/x"));
    } finally {
      logger.removeHandler(capture);
    }

    assertEquals(Optional.of(new WorkspaceCredential("ws-7-b", "s3cr3t")), issued);
    assertEquals(List.of("POST /api/clients", "POST /api/clients"), requests);
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
        () -> commissionerAgainst(url).commission(7L, A_PROJECT, List.of()));
    assertEquals(1, requests.size());
  }

  @Test
  public void aNarrowingIsAPutOfTheWholeListAsThisService() throws Exception {
    String url = serve(List.of(new Answer(204, null)));

    commissionerAgainst(url).updateGitRefs("ws-7-a", List.of("refs/heads/epic/e"));

    assertEquals(List.of("PUT /api/clients/ws-7-a/git-refs"), requests);
    assertEquals(
        List.of(IdpCredentialCommissioner.basic("dev-qits-workspaces", "service-secret")),
        authorizations);
    assertEquals("{\"gitRefs\":[\"refs/heads/epic/e\"]}", bodies.get(0));
  }

  @Test
  public void aRefusedNarrowingThrowsSoTheCallerKeepsItPending() throws Exception {
    String url = serve(List.of(new Answer(404, "{\"error\":\"not_found\"}")));

    RuntimeException failure =
        assertThrows(
            RuntimeException.class,
            () -> commissionerAgainst(url).updateGitRefs("ws-7-a", List.of()));
    assertTrue(failure.getMessage().contains("404"), failure.getMessage());
    assertEquals(1, requests.size(), "one attempt; the reconcile is the retry");
  }

  @Test
  public void withTheSwitchOffANarrowingDialsNothing() throws Exception {
    String url = serve(List.of(new Answer(500, null)));
    IdpCredentialCommissioner commissioner = commissionerAgainst(url);
    commissioner.enabled = false;

    commissioner.updateGitRefs("ws-7-a", List.of("refs/heads/epic/e"));

    assertEquals(List.of(), requests);
  }
}
