package eu.wohlben.qits.workspaces.daemonhost;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.build.JwtClaimsBuilder;
import io.smallrye.jwt.util.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Test issuer for the machine bearer the workspace daemon presents to its control socket. */
public final class DaemonMachineTokens {

  public static final String SIGNING_KEY = "/machine-token-signing-key.pem";
  public static final String VERIFICATION_KEY = "/machine-token-verification-key.pem";
  public static final String ISSUER = "http://qits-platform-idp:8080/idp";

  public static String token(String clientId, String... audiences) {
    return tokenWithRoles(clientId, Set.of("qits:system"), audiences);
  }

  /** A bearer for {@code clientId} carrying exactly {@code roles} — an agent's, for instance. */
  public static String tokenWithRoles(String clientId, Set<String> roles, String... audiences) {
    return tokenWithRoles(clientId, roles, Duration.ofMinutes(5), audiences);
  }

  /** {@link #tokenWithRoles(String, Set, String...)} that expires {@code lifetime} from now. */
  public static String tokenWithRoles(
      String clientId, Set<String> roles, Duration lifetime, String... audiences) {
    return tokenWithClaims(clientId, roles, Map.of(), lifetime, audiences);
  }

  /**
   * {@link #tokenWithRoles(String, Set, String...)} with extra top-level claims — a desk's {@code
   * project} (qits-1493), for instance, the structured claim {@link
   * eu.wohlben.qits.auth.QitsClaims} names and this platform's doors bind a caller to.
   */
  public static String tokenWithClaims(
      String clientId, Set<String> roles, Map<String, String> claims, String... audiences) {
    return tokenWithClaims(clientId, roles, claims, Duration.ofMinutes(5), audiences);
  }

  /** {@link #tokenWithClaims(String, Set, Map, String...)} that expires {@code lifetime} from now. */
  public static String tokenWithClaims(
      String clientId,
      Set<String> roles,
      Map<String, String> claims,
      Duration lifetime,
      String... audiences) {
    JwtClaimsBuilder builder =
        Jwt.claims()
            .issuer(ISSUER)
            .subject(clientId)
            .groups(roles)
            .audience(Set.of(audiences))
            .expiresIn(lifetime);
    claims.forEach(builder::claim);
    return builder.jws().sign(privateKey());
  }

  public static String pem(String resource) {
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
