package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.quarkus.test.common.http.TestHTTPResource;
import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The dial-home control socket with machine authentication enabled. The tokens are real RS256
 * bearers and Quarkus validates them against the profile's public key, so this proves the daemon's
 * Authorization header reaches the protected WebSocket rather than only testing an annotation.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class DaemonControlSocketMachineAuthTest {

  /** The one audience this service accepts, and the one every token this platform mints carries. */
  private static final String PLATFORM_AUDIENCE = "qits-platform";

  /**
   * An audience no token of this platform's carries. It is deliberately not a sibling service's
   * name: qits-idp stamps {@code qits-platform} onto every token it mints, whichever client asked
   * for whatever, so a peer's bearer is addressed here too and its roles decide what it may do.
   * What the door refuses is a token cut somewhere else entirely.
   */
  private static final String OUTSIDE_AUDIENCE = "somebody-elses-platform";

  @Inject Vertx vertx;

  @TestHTTPResource("/workspaces/daemon/1")
  URI endpoint;

  @Test
  void aCommissionedDaemonBearerReachesTheControlSocket() {
    assertDoesNotThrow(() -> connect(DaemonMachineTokens.token("workspace-1", PLATFORM_AUDIENCE)));
  }

  @Test
  void aMissingBearerIsRejectedBeforeTheControlSocketOpens() {
    assertThrows(Exception.class, () -> connect(null));
  }

  @Test
  void aBearerMintedForAnotherPlatformIsRejectedBeforeTheControlSocketOpens() {
    assertThrows(
        Exception.class,
        () -> connect(DaemonMachineTokens.token("workspace-1", OUTSIDE_AUDIENCE)));
  }

  private void connect(String token) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    try {
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      if (token != null) {
        options.addHeader("Authorization", "Bearer " + token);
      }
      client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } finally {
      client.close();
    }
  }

  /**
   * Gate-on production posture, with a local verification key instead of a live IdP.
   *
   * <p><b>The only in-JVM profile in this module that turns the machine gate on</b>, and that is
   * what makes it un-mergeable rather than its map. {@code qits.auth.machine.required} gates {@code
   * quarkus.oidc.tenant-enabled}, so this application validates bearers where every other surefire
   * application does not — a class that sends no bearer, which is most of them, would stop being
   * able to reach the socket at all. It also blanks the dev user for the same reason {@code
   * NoDevUserProfile} does, and swaps the live IdP for a local public key so the gate can be proved
   * without a network. The one packaged run that also has the gate on ({@code StoryProfile})
   * deliberately keeps the shipped {@code auth-server-url} + {@code jwks-path} pair instead, which
   * is the other half of the same coverage and cannot be this half.
   */
  public static class GateOn implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.auth.machine.required", "true",
          "qits.auth.forward.dev-user", "",
          "quarkus.oidc.auth-server-url", "",
          "quarkus.oidc.token.issuer", DaemonMachineTokens.ISSUER,
          "quarkus.oidc.public-key", base64Key());
    }

    private static String base64Key() {
      return DaemonMachineTokens.pem(DaemonMachineTokens.VERIFICATION_KEY)
          .replace("-----BEGIN PUBLIC KEY-----", "")
          .replace("-----END PUBLIC KEY-----", "")
          .replaceAll("\\s", "");
    }
  }
}
