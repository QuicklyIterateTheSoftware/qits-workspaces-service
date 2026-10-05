package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.workspaces.control.WorkspaceContainerFactory;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.runnerhost.RunnerRows;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddresses;
import eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerAddressesFixture;
import eu.wohlben.qits.workspaces.wiring.IdpRunnerCommissioner;
import eu.wohlben.qits.workspaces.wiring.IdpStub;
import eu.wohlben.qits.workspaces.wiring.IdpStub.Answer;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /workspaces/api/runners} (qits-848, qits-859): who may press which door, what create and
 * rotate hand out once, the register door's four refusals and its answer, the install script, the
 * delete refusal and the login command.
 *
 * <p><b>Under the machine gate</b> ({@link DaemonControlSocketMachineAuthTest.GateOn}, reused: one
 * profile is one Quarkus start), because the register door reads the {@code sub} off a validated
 * bearer and with the gate off there is none. The tokens are real RS256 JWTs the profile's key
 * verifies — the shape of the edge's JWT for a registration token. A person's and a peer service's
 * roles arrive as the edge asserts them, in {@code X-Qits-Roles}.
 *
 * <p>qits-idp is a stub over real HTTP ({@link IdpStub}) and the public domain is {@link
 * WorkspaceRunnerAddressesFixture#DOMAIN}, both installed per test with {@link QuarkusMock}.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class WorkspaceRunnerControllerTest {

  private static final String RUNNERS = "/workspaces/api/runners";

  private static final String DOMAIN = WorkspaceRunnerAddressesFixture.DOMAIN;

  private static final String TOKEN = "qits_tok_registration-value";

  @Inject WorkspaceRunners runners;

  @Inject WorkspaceContainerFactory containerFactory;

  private IdpStub idp;

  private RunnerRows rows;

  @BeforeEach
  void stubTheIdpAndTheDomain() throws Exception {
    idp = new IdpStub();
    idp.on(
        "POST /api/tokens",
        new Answer(201, "{\"tokenId\":\"t-1\",\"token\":\"" + TOKEN + "\",\"subject\":\"sub-1\"}"),
        new Answer(201, "{\"tokenId\":\"t-2\",\"token\":\"qits_tok_second\",\"subject\":\"sub-2\"}"));
    idp.on("POST /api/clients", new Answer(201, "{\"clientId\":\"wr-1\",\"secret\":\"s3cr3t\"}"));
    QuarkusMock.installMockForType(idp.runnerCommissioner(), IdpRunnerCommissioner.class);
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(DOMAIN), WorkspaceRunnerAddresses.class);
    rows = new RunnerRows();
  }

  @AfterEach
  void stopTheIdp() {
    rows.clear();
    idp.close();
  }

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", role);
  }

  private static RequestSpecification bearer(String sub, String role) {
    return given()
        .header(
            "Authorization",
            "Bearer " + DaemonMachineTokens.tokenWithRoles(sub, Set.of(role), "qits-platform"));
  }

  private JsonPath create(String role, String name, int slots) {
    return as(role)
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "slots", slots))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath();
  }

  // --- create, rotate, patch, delete --------------------------------------------------------------

  /**
   * The machine role creates — the cold bootstrap does, with its own token — and the answer carries
   * the token and the install line once, rendered from the toolkit's template with this service's
   * names.
   */
  @Test
  void theSystemRoleCreatesAndIsAnsweredTheInstallLineOnce() {
    JsonPath created = create("qits:system", "attic", 2);

    String id = created.getString("runner.id");
    rows.track(UUID.fromString(id));
    assertEquals(TOKEN, created.getString("registrationToken"));
    assertEquals(
        "curl -fsSL -H 'Authorization: Bearer "
            + TOKEN
            + "' https://workspaces.qits."
            + DOMAIN
            + "/workspaces/api/runners/install.sh | sudo env QITS_WORKSPACES_RUNNER_URL='https://workspaces.qits."
            + DOMAIN
            + "' QITS_WORKSPACES_RUNNER_ID='"
            + id
            + "' QITS_WORKSPACES_RUNNER_REGISTRATION_TOKEN='"
            + TOKEN
            + "' QITS_WORKSPACES_RUNNER_SLOTS='2' sh",
        created.getString("installLine"));
    assertEquals("attic", created.getString("runner.name"));
    assertFalse(created.getBoolean("runner.registered"));
    assertFalse(created.getBoolean("runner.connected"));
    assertEquals(WorkspacesRunnerBinary.VERSION, created.getString("runner.pinnedVersion"));
    assertEquals(0, created.getInt("runner.owned"));
    assertNull(created.get("runner.loginCommand"));
    IdpStub.Request minted = idp.requests().get(0);
    assertEquals("POST /api/tokens", minted.line());
    assertTrue(minted.body().contains("\"workspaces-runner-registration\""), minted.body());

    // Never readable again: the reads carry neither the token nor the line.
    String read = as("qits:admin").when().get(RUNNERS + "/" + id).then().statusCode(200)
        .extract().asString();
    assertFalse(read.contains(TOKEN), read);
    assertFalse(read.contains("installLine"), read);
  }

  @Test
  void theSystemRolePatchesRotatesAndDeletesAndCannotGreenlight() {
    String id = create("qits:system", "cellar", 1).getString("runner.id");
    rows.track(UUID.fromString(id));

    as("qits:system")
        .contentType(ContentType.JSON)
        .body(Map.of("slots", 3, "description", "under the stairs"))
        .when()
        .patch(RUNNERS + "/" + id)
        .then()
        .statusCode(200)
        .body("slots", is(3))
        .body("description", is("under the stairs"));

    JsonPath rotated =
        as("qits:system")
            .when()
            .post(RUNNERS + "/" + id + "/registration-token")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("qits_tok_second", rotated.getString("registrationToken"));
    assertTrue(rotated.getString("installLine").contains("'qits_tok_second'"));
    assertTrue(idp.lines("DELETE").contains("DELETE /api/tokens/t-1"), idp.lines().toString());

    as("qits:system").when().post(RUNNERS + "/" + id + "/greenlight").then().statusCode(403);
    // The health check is open to the system role (qits-850): past the gate, 409 for no socket.
    as("qits:system")
        .when()
        .post(RUNNERS + "/" + id + "/healthcheck")
        .then()
        .statusCode(409)
        .body("code", is("RUNNER_UNAVAILABLE"));
    as("qits:system").when().post(RUNNERS + "/" + id + "/login-check").then().statusCode(403);

    as("qits:system").when().delete(RUNNERS + "/" + id).then().statusCode(204);
    assertTrue(idp.lines("DELETE").contains("DELETE /api/tokens/t-2"), idp.lines().toString());
    as("qits:admin").when().get(RUNNERS + "/" + id).then().statusCode(404);
  }

  /**
   * {@code qits:agent} reads every runner, its health report and the script, and writes nothing —
   * the health check excepted (qits-850), which {@link
   * eu.wohlben.qits.workspaces.runnerhost.WorkspaceRunnerHealthTest} presses on a connected runner.
   */
  @Test
  void anAgentReadsAndCannotWrite() {
    WorkspaceRunner runner = rows.registered("wr-agent-reads", 1);

    as("qits:agent").when().get(RUNNERS).then().statusCode(200);
    as("qits:agent").when().get(RUNNERS + "/" + runner.id).then().statusCode(200);
    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + runner.id + "/health")
        .then()
        .statusCode(204);
    as("qits:agent").when().get(RUNNERS + "/install.sh").then().statusCode(200);

    as("qits:agent")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "nope", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    as("qits:agent")
        .contentType(ContentType.JSON)
        .body(Map.of("slots", 0))
        .when()
        .patch(RUNNERS + "/" + runner.id)
        .then()
        .statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + runner.id + "/registration-token").then()
        .statusCode(403);
    as("qits:agent").when().delete(RUNNERS + "/" + runner.id).then().statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + runner.id + "/greenlight").then().statusCode(403);
    as("qits:agent").when().post(RUNNERS + "/" + runner.id + "/login-check").then().statusCode(403);
  }

  @Test
  void anAdminGreenlightsAQuarantinedRunner() {
    WorkspaceRunner runner = rows.registered("wr-greenlit", 1);
    assertTrue(runner.quarantined());

    as("qits:admin")
        .when()
        .post(RUNNERS + "/" + runner.id + "/greenlight")
        .then()
        .statusCode(200)
        .body("quarantined", is(false))
        .body("eligible", is(true));
  }

  /**
   * Health check and login check need a socket: 409 RUNNER_UNAVAILABLE with none. An agent is past
   * the health check's gate (qits-850) and not the login check's.
   */
  @Test
  void theChecksOfARunnerThatIsNotConnectedAre409() {
    WorkspaceRunner runner = rows.registered("wr-offline", 1);

    for (String check : List.of("healthcheck", "login-check")) {
      as("qits:admin")
          .when()
          .post(RUNNERS + "/" + runner.id + "/" + check)
          .then()
          .statusCode(409)
          .body("code", is("RUNNER_UNAVAILABLE"));
    }
    as("qits:agent")
        .when()
        .post(RUNNERS + "/" + runner.id + "/healthcheck")
        .then()
        .statusCode(409)
        .body("code", is("RUNNER_UNAVAILABLE"));
    as("qits:agent").when().post(RUNNERS + "/" + runner.id + "/login-check").then().statusCode(403);
    as("qits:admin").when().post(RUNNERS + "/" + UUID.randomUUID() + "/healthcheck").then()
        .statusCode(404);
  }

  /** The 409 names the rows, and links them: repository and branch beside each id. */
  @Test
  void aRunnerThatOwnsAnActiveWorkspaceIsNotDeleted() {
    WorkspaceRunner runner = rows.eligible("wr-owner", 1);
    Long owned = rows.placedOn(runner.id, WorkspaceRuntimeStatus.STOPPED);
    String repository = rows.read(owned).repositoryId;

    JsonPath refused =
        as("qits:admin")
            .when()
            .delete(RUNNERS + "/" + runner.id)
            .then()
            .statusCode(409)
            .body("code", is("RUNNER_OWNS_WORKSPACES"))
            .extract()
            .jsonPath();
    assertEquals(List.of(owned.intValue()), refused.getList("workspaceIds"));
    assertEquals(owned.intValue(), refused.getInt("workspaces[0].id"));
    assertEquals(repository, refused.getString("workspaces[0].repositoryId"));
    assertEquals(rows.read(owned).branch, refused.getString("workspaces[0].branch"));
    assertNotNull(rows.runner(runner.id), "the row stays");
  }

  // --- install.sh ---------------------------------------------------------------------------------

  /**
   * The script names this service's runner and its pinned image on the public registry, has no
   * placeholder left and carries no secret. The template's own contract is qits-771's
   * InstallScriptContractTest; this is the rendering with the workspaces names.
   */
  @Test
  void theInstallScriptRendersWithTheWorkspacesNamesAndThePinnedImage() {
    String script =
        as("qits:admin")
            .when()
            .get(RUNNERS + "/install.sh")
            .then()
            .statusCode(200)
            .contentType(ContentType.TEXT)
            .extract()
            .asString();

    assertTrue(
        script.contains(
            "registry.qits." + DOMAIN + "/qits/qits-workspaces-runner:" + WorkspacesRunnerBinary.VERSION),
        script);
    assertTrue(script.contains("QITS_WORKSPACES_RUNNER_"), "the env prefix");
    assertTrue(script.contains("qits-workspaces-runner"), "the name prefix");
    assertTrue(script.contains("qits.workspaces.runner"), "the label root");
    assertTrue(script.contains("the Workspaces UI's Runners page"), "the page");
    assertFalse(script.contains("{{"), "no placeholder left");
    assertFalse(script.contains("qits_tok"), "no token");
    assertFalse(script.contains("qits-ci-runner"), "no CI name");
  }

  /** An undotted domain names nothing a runner elsewhere could reach: nothing is minted. */
  @Test
  void withNoPublicDomainCreateAndTheScriptAre503AndNothingIsMinted() {
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain("localhost"), WorkspaceRunnerAddresses.class);

    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("name", "nowhere", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(503)
        .body("code", is("RUNNER_PLANE_UNCONFIGURED"));
    as("qits:admin")
        .when()
        .get(RUNNERS + "/install.sh")
        .then()
        .statusCode(503)
        .body("code", is("RUNNER_PLANE_UNCONFIGURED"));
    assertTrue(idp.requests().isEmpty(), "nothing was minted: " + idp.lines());
  }

  // --- the register door --------------------------------------------------------------------------

  @Test
  void theRightRegistrationTokenRegistersOnceAndIsAnsweredItsClient() {
    WorkspaceRunner runner = rows.declared("reg-sub-1", 2);

    JsonPath registered =
        bearer("reg-sub-1", "qits:workspaces-runner-registration")
            .contentType(ContentType.JSON)
            .body(Map.of("capabilities", Map.of("docker", true, "arch", "amd64")))
            .when()
            .post(RUNNERS + "/" + runner.id + "/register")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath();
    assertEquals("wr-1", registered.getString("clientId"));
    assertEquals("s3cr3t", registered.getString("secret"));
    assertEquals("https://idp.qits." + DOMAIN + "/idp/token", registered.getString("tokenUrl"));
    assertEquals("qits-platform", registered.getString("audience"));
    assertEquals(
        "wss://workspaces.qits." + DOMAIN + "/workspaces/runners/socket",
        registered.getString("socketUrl"));
    String commission = idp.requests().stream()
        .filter(r -> r.line().equals("POST /api/clients")).findFirst().orElseThrow().body();
    assertTrue(commission.contains("\"workspaces-runner\""), commission);
    assertTrue(idp.lines("DELETE").contains("DELETE /api/tokens/token-" + runner.id));

    WorkspaceRunner row = rows.runner(runner.id);
    assertEquals("wr-1", row.clientId);
    assertNull(row.registrationTokenId, "the spent token is off the row");
    assertTrue(row.quarantined(), "quarantined until its first health check");
    assertEquals(WorkspaceRunners.AWAITING_FIRST_HEALTH_CHECK, row.quarantineReason);

    // A replay of the same token: the runner has spent its registration.
    bearer("reg-sub-1", "qits:workspaces-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of("capabilities", Map.of()))
        .when()
        .post(RUNNERS + "/" + runner.id + "/register")
        .then()
        .statusCode(409);
  }

  @Test
  void aRegistrationTokenOfAnotherRunnerIs403AndAnUnknownRunner404() {
    WorkspaceRunner runner = rows.declared("reg-sub-mine", 1);

    bearer("reg-sub-somebody-else", "qits:workspaces-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of("capabilities", Map.of()))
        .when()
        .post(RUNNERS + "/" + runner.id + "/register")
        .then()
        .statusCode(403);
    bearer("reg-sub-mine", "qits:workspaces-runner-registration")
        .contentType(ContentType.JSON)
        .body(Map.of("capabilities", Map.of()))
        .when()
        .post(RUNNERS + "/" + UUID.randomUUID() + "/register")
        .then()
        .statusCode(404);
    assertNull(rows.runner(runner.id).clientId, "nothing was registered");
    assertTrue(idp.lines("POST").isEmpty(), "nothing was commissioned: " + idp.lines());
  }

  /** The registration role opens the register door and install.sh, and no other route here. */
  @Test
  void aRegistrationTokenOpensOnlyTheRegisterDoorAndTheScript() {
    WorkspaceRunner runner = rows.declared("reg-sub-narrow", 1);
    String role = "qits:workspaces-runner-registration";

    bearer("reg-sub-narrow", role).when().get(RUNNERS + "/install.sh").then().statusCode(200);
    bearer("reg-sub-narrow", role).when().get(RUNNERS).then().statusCode(403);
    bearer("reg-sub-narrow", role).when().get(RUNNERS + "/" + runner.id).then().statusCode(403);
    bearer("reg-sub-narrow", role)
        .contentType(ContentType.JSON)
        .body(Map.of("name", "sneaky", "slots", 1))
        .when()
        .post(RUNNERS)
        .then()
        .statusCode(403);
    bearer("reg-sub-narrow", role)
        .when()
        .post(RUNNERS + "/" + runner.id + "/registration-token")
        .then()
        .statusCode(403);
    // And a person's or a peer's role does not register a runner: the door is the token's.
    as("qits:admin")
        .contentType(ContentType.JSON)
        .body(Map.of("capabilities", Map.of()))
        .when()
        .post(RUNNERS + "/" + runner.id + "/register")
        .then()
        .statusCode(403);
  }

  // --- the login command (qits-859) ---------------------------------------------------------------

  /**
   * Null until the volume is known; still null, but PENDING, once it is known and the runner has
   * not yet proven the current image is on its node; then exactly the feature's shape, both CLIs,
   * once a login checked at or after registration answered at least one harness.
   */
  @Test
  void theLoginCommandAppearsOnlyOnceTheRunnerHasProvenTheImageIsThere() throws Exception {
    WorkspaceRunner runner = rows.registered("wr-login", 1);
    as("qits:admin")
        .when()
        .get(RUNNERS + "/" + runner.id)
        .then()
        .statusCode(200)
        .body("loginCommand", nullValue())
        .body("kimiLoginCommand", nullValue())
        .body("loginCommandPending", is(false));

    // The volume is known, but the login has not yet been checked: pending, not merely absent.
    runners.recordCapabilities(
        runner.id,
        new ObjectMapper()
            .readTree("{\"dotClaudeVolume\":\"qits-workspaces-runner-dot-claude-1234abcd\"}"));
    as("qits:admin")
        .when()
        .get(RUNNERS + "/" + runner.id)
        .then()
        .statusCode(200)
        .body("loginCommand", nullValue())
        .body("kimiLoginCommand", nullValue())
        .body("loginCommandPending", is(true));

    // A login probe that could not run (both UNKNOWN) is still no proof: still pending.
    runners.recordCapabilities(
        runner.id,
        new ObjectMapper()
            .readTree(
                "{\"login\":{\"claude\":\"UNKNOWN\",\"kimi\":\"UNKNOWN\","
                    + "\"checkedAt\":\"" + Instant.now().plusSeconds(60) + "\"}}"));
    as("qits:admin")
        .when()
        .get(RUNNERS + "/" + runner.id)
        .then()
        .statusCode(200)
        .body("loginCommand", nullValue())
        .body("loginCommandPending", is(true));

    // A login checked at or after registration, answering at least one harness, is proof.
    runners.recordCapabilities(
        runner.id,
        new ObjectMapper()
            .readTree(
                "{\"login\":{\"claude\":\"ABSENT\",\"kimi\":\"UNKNOWN\","
                    + "\"checkedAt\":\"" + Instant.now().plusSeconds(120) + "\"}}"));

    String image =
        "registry.qits."
            + DOMAIN
            + "/qits/workspace:"
            + containerFactory.imageVersion();
    String expectedLoginCommand =
        "docker run --rm -it --user 1000 --entrypoint claude -v"
            + " qits-workspaces-runner-dot-claude-1234abcd:/claude-home"
            + " -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home "
            + image;
    String expectedKimiLoginCommand =
        "docker run --rm -it --user 1000 --entrypoint kimi -v"
            + " qits-workspaces-runner-dot-claude-1234abcd:/claude-home"
            + " -e HOME=/claude-home -e KIMI_CODE_HOME=/claude-home/.kimi-code "
            + image
            + " login";
    as("qits:agent")
        .when()
        .get(RUNNERS)
        .then()
        .statusCode(200)
        .body(
            "find { it.id == '" + runner.id + "' }.loginCommand", equalTo(expectedLoginCommand))
        .body(
            "find { it.id == '" + runner.id + "' }.kimiLoginCommand",
            equalTo(expectedKimiLoginCommand))
        .body("find { it.id == '" + runner.id + "' }.login.claude", is("ABSENT"))
        .body("find { it.id == '" + runner.id + "' }.dotClaudeVolume", notNullValue())
        .body("find { it.id == '" + runner.id + "' }.loginCommandPending", is(false));
  }
}
