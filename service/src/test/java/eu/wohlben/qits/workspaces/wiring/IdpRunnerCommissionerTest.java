package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.wiring.IdpStub.Answer;
import io.quarkus.test.junit.QuarkusTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link IdpRunnerCommissioner} and {@link IdpTokens} against a stub qits-idp over real HTTP: what a
 * runner's registration token and client are asked for, what is held through, and that giving back
 * and listing never throw. A {@code @QuarkusTest} only because the generated REST client refuses to
 * build outside a running application; the bean itself is built by hand, as {@code
 * IdpCredentialCommissionerTest} builds its own.
 */
@QuarkusTest
public class IdpRunnerCommissionerTest {

  private static final UUID RUNNER = UUID.fromString("6c1f0d0e-1111-4222-8333-444455556666");

  private IdpStub idp;

  @BeforeEach
  void startIdp() throws Exception {
    idp = new IdpStub();
  }

  @AfterEach
  void stopIdp() {
    idp.close();
  }

  @Test
  public void aRegistrationTokenIsATokenOfItsKindNamedByTheRunner() {
    idp.on(
        "POST /api/tokens",
        new Answer(
            201,
            "{\"tokenId\":\"t-1\",\"token\":\"qits_tok_abc\",\"subject\":\"tok-sub-1\","
                + "\"owner\":\"dev-qits-workspaces\"}"));

    IdpRunnerCommissioner.IssuedToken issued =
        idp.runnerCommissioner().registrationToken(RUNNER);

    assertEquals(new IdpRunnerCommissioner.IssuedToken("t-1", "qits_tok_abc", "tok-sub-1"), issued);
    assertFalse(issued.toString().contains("qits_tok_abc"), "the value is never printed");
    IdpStub.Request request = idp.requests().get(0);
    assertEquals("POST /api/tokens", request.line());
    assertEquals(
        IdpCredentialCommissioner.basic("dev-qits-workspaces", "service-secret"),
        request.authorization());
    assertTrue(
        request.body().contains("\"contextKind\":\"workspaces-runner-registration\""),
        request.body());
    assertTrue(request.body().contains("\"contextId\":\"" + RUNNER + "\""), request.body());
    assertFalse(request.body().contains("claims"), "it states nothing: " + request.body());
    assertFalse(request.body().contains("gitRefs"), "it states nothing: " + request.body());
  }

  @Test
  public void aRunnerClientStatesNoProjectAndNoGitRefs() {
    idp.on("POST /api/clients", new Answer(201, "{\"clientId\":\"wr-1\",\"secret\":\"s3cr3t\"}"));

    IdpRunnerCommissioner.RunnerClient client = idp.runnerCommissioner().runnerClient(RUNNER);

    assertEquals(new IdpRunnerCommissioner.RunnerClient("wr-1", "s3cr3t"), client);
    assertFalse(client.toString().contains("s3cr3t"));
    String body = idp.requests().get(0).body();
    assertTrue(body.contains("\"contextKind\":\"workspaces-runner\""), body);
    assertTrue(body.contains("\"contextId\":\"" + RUNNER + "\""), body);
    assertFalse(body.contains("project"), body);
    assertFalse(body.contains("gitRefs"), body);
  }

  @Test
  public void anIdpCutoverIsHeldThroughWithTheSharedLoop() {
    idp.on(
        "POST /api/tokens",
        new Answer(503, null),
        new Answer(201, "{\"tokenId\":\"t-2\",\"token\":\"qits_tok_x\",\"subject\":\"s-2\"}"));

    assertEquals("t-2", idp.runnerCommissioner().registrationToken(RUNNER).tokenId());
    assertEquals(List.of("POST /api/tokens", "POST /api/tokens"), idp.lines());
  }

  @Test
  public void anAnswerAboutTheRequestFailsAtOnce() {
    idp.on("POST /api/clients", new Answer(400, "{\"error\":\"invalid_request\"}"));

    IdpRunnerCommissioner.CommissionFailedException failed =
        assertThrows(
            IdpRunnerCommissioner.CommissionFailedException.class,
            () -> idp.runnerCommissioner().runnerClient(RUNNER));
    assertTrue(failed.getMessage().contains(RUNNER.toString()), failed.getMessage());
    assertEquals(1, idp.requests().size(), "a 400 is not held through");
  }

  @Test
  public void unwiredItCommissionsNothingAndDialsNothing() {
    IdpRunnerCommissioner commissioner = idp.runnerCommissioner();
    commissioner.enabled = false;

    assertFalse(commissioner.enabled());
    assertThrows(
        IdpRunnerCommissioner.CommissionFailedException.class,
        () -> commissioner.registrationToken(RUNNER));
    commissioner.decommissionClient("wr-1");
    assertFalse(commissioner.deleteToken("t-1"));
    assertEquals(Optional.empty(), commissioner.liveTokens());
    assertEquals(List.of(), idp.requests());
  }

  @Test
  public void givingBackIsOneAttemptAndAGoneCredentialIsSuccess() {
    idp.on("DELETE /api/clients/wr-1", new Answer(404, null));
    idp.on("DELETE /api/tokens/t-1", new Answer(204, null));
    idp.on("DELETE /api/tokens/t-2", new Answer(500, null));
    IdpRunnerCommissioner commissioner = idp.runnerCommissioner();

    commissioner.decommissionClient("wr-1");
    assertTrue(commissioner.deleteToken("t-1"));
    assertFalse(commissioner.deleteToken("t-2"), "left for the reconcile, never thrown");
    assertEquals(
        List.of("DELETE /api/clients/wr-1", "DELETE /api/tokens/t-1", "DELETE /api/tokens/t-2"),
        idp.lines());
  }

  @Test
  public void theTokenListingIsReadAndAFailedOneIsNoAnswer() {
    idp.on(
        "GET /api/tokens",
        new Answer(
            200,
            "[{\"tokenId\":\"t-1\",\"subject\":\"s\",\"owner\":\"o\","
                + "\"contextKind\":\"workspaces-runner-registration\",\"contextId\":\"r\","
                + "\"createdAt\":\"2026-10-05T08:00:00Z\"}]"),
        new Answer(500, null));
    IdpRunnerCommissioner commissioner = idp.runnerCommissioner();

    assertEquals(
        Optional.of(
            List.of(
                new IdpRunnerCommissioner.LiveToken(
                    "t-1",
                    "workspaces-runner-registration",
                    "r",
                    Instant.parse("2026-10-05T08:00:00Z")))),
        commissioner.liveTokens());
    assertEquals(Optional.empty(), commissioner.liveTokens(), "a 500 is not an empty listing");
  }
}
