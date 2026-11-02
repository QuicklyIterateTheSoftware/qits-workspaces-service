package eu.wohlben.qits.workspaces.security;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * <b>Only the issuer qits-idp publishes is accepted</b> (qits-708) — on a REST door and on the
 * daemon control socket, because one quarkus-oidc tenant guards both. The shipped tenant has
 * discovery on and no issuer key at all, so the issuer every bearer's {@code iss} is checked
 * against is the {@code issuer} of the discovery document it reads at startup. Before qits-708
 * discovery was off and no issuer was configured, and quarkus-oidc then checks no {@code iss} at
 * all: a token signed by a published key and addressed to {@code qits-platform} was accepted
 * whoever claimed to have issued it.
 *
 * <p>The far side is qits-service-mock's {@link MockIdp}: it serves a discovery document whose
 * {@code issuer} is the {@code iss} it mints, and a JWKS at the {@code jwks_uri} that document
 * names. This profile moves only the gate, the dev user and {@code auth-server-url}, so the tenant
 * under test is the shipped one: discovery, the published issuer, the shipped audience, keys by
 * {@code kid}.
 *
 * <p><b>Why this profile cannot be {@code DaemonControlSocketMachineAuthTest.GateOn}</b>, the
 * module's other gate-on profile: GateOn swaps the tenant for a local {@code public-key} and so
 * never discovers anything — the issuer it checks is a test-only {@code token.issuer} — and the ten
 * classes on it mint with {@code DaemonMachineTokens}' own key, which MockIdp does not publish. The
 * subject here is the discovering tenant itself, which GateOn by construction does not have.
 * {@code TokenValidationBootstrapIT} proves the same block on the packaged artifact; this class is
 * the in-JVM proof of the refusal, which no other test here makes.
 */
@QuarkusTest
@TestProfile(BearerIssuerTest.DiscoveringTheMockIdp.class)
class BearerIssuerTest {

  /** A plain read of this context's own store, guarded by role: it dials no peer. */
  private static final String GUARDED_ROUTE = "/workspaces/api/history?repositoryId=no-such-repo";

  /** The one platform audience, which the shipped tenant enforces. */
  private static final String AUDIENCE = "qits-platform";

  /** The idp's address before qits-730: an issuer this service trusts under no name. */
  private static final String FOREIGN_ISSUER = "http://qits-platform-idp:8080/idp";

  @Inject Vertx vertx;

  @TestHTTPResource("/workspaces/daemon/1")
  URI controlSocket;

  /**
   * The gate on, the dev user off, the tenant pointed at the mock idp and nothing else moved. See
   * the class javadoc for why it cannot be GateOn.
   */
  public static class DiscoveringTheMockIdp
      implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      return Map.of(
          "qits.auth.machine.required", "true",
          "qits.auth.forward.dev-user", "",
          "quarkus.oidc.auth-server-url", idp.baseUrl());
    }
  }

  @Test
  void theTenantReadsTheDiscoveryDocument() {
    // The tenant resolves at startup (connection-delay): the document is read before any bearer.
    given().when().get("/workspaces/q/health/ready").then().statusCode(200);
    assertTrue(
        MockIdp.attach().recordedRequests().stream()
            .anyMatch(r -> "/idp/.well-known/openid-configuration".equals(r.path())),
        "the tenant never read the idp's discovery document");
  }

  @Test
  void aBearerFromThePublishedIssuerIsAccepted() {
    String token = MockIdp.attach().token().audience(AUDIENCE).groups("qits:admin").mint();
    given().header("Authorization", "Bearer " + token).when().get(GUARDED_ROUTE).then().statusCode(200);
  }

  @Test
  void aBearerFromAnotherIssuerIsRefused() {
    // Correctly signed with the published key, the right audience, not expired: only `iss` is wrong.
    String token =
        MockIdp.attach()
            .token()
            .audience(AUDIENCE)
            .groups("qits:admin")
            .claim("iss", FOREIGN_ISSUER)
            .mint();
    given().header("Authorization", "Bearer " + token).when().get(GUARDED_ROUTE).then().statusCode(401);
  }

  @Test
  void theControlSocketOpensForThePublishedIssuer() {
    String token = MockIdp.attach().token().audience(AUDIENCE).groups("qits:system").mint();
    assertDoesNotThrow(() -> dial(token));
  }

  @Test
  void theControlSocketRefusesAnotherIssuer() {
    String token =
        MockIdp.attach()
            .token()
            .audience(AUDIENCE)
            .groups("qits:system")
            .claim("iss", FOREIGN_ISSUER)
            .mint();
    assertThrows(Exception.class, () -> dial(token));
  }

  private void dial(String token) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(controlSocket.getHost())
              .setPort(controlSocket.getPort())
              .setURI(controlSocket.getPath())
              .addHeader("Authorization", "Bearer " + token);
      client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } finally {
      client.close();
    }
  }
}
