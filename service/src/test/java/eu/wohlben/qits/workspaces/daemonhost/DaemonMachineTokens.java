package eu.wohlben.qits.workspaces.daemonhost;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Set;

/** Test issuer for the machine bearer the workspace daemon presents to its control socket. */
final class DaemonMachineTokens {

  static final String SIGNING_KEY = "/machine-token-signing-key.pem";
  static final String VERIFICATION_KEY = "/machine-token-verification-key.pem";
  static final String ISSUER = "http://qits-platform-idp:8080/idp";

  static String token(String clientId, String... audiences) {
    return tokenWithRoles(clientId, Set.of("qits:system"), audiences);
  }

  /** A bearer for {@code clientId} carrying exactly {@code roles} — an agent's, for instance. */
  static String tokenWithRoles(String clientId, Set<String> roles, String... audiences) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject(clientId)
        .groups(roles)
        .audience(Set.of(audiences))
        .expiresIn(Duration.ofMinutes(5))
        .jws()
        .sign(privateKey());
  }

  static String pem(String resource) {
    try (var in = DaemonMachineTokens.class.getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Missing test key " + resource, e);
    }
  }

  private static PrivateKey privateKey() {
    try {
      return KeyUtils.decodePrivateKey(pem(SIGNING_KEY));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the test signing key", e);
    }
  }

  private DaemonMachineTokens() {}
}
