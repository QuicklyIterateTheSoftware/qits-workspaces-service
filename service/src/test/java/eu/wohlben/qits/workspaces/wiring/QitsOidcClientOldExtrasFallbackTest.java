package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has not declared {@code idp:client} yet — the OLD extras keys are set, the new
 * {@code QITS_RESOURCE_IDP_*} ones are not — resolves the {@code qits} client's id, secret and url
 * from them, byte for byte (service-client-identity-plan.md, C4). This is what keeps a deployment
 * running unchanged the moment this commit ships, before qits-deployments injects anything new.
 *
 * <p>The three old clients' env names carry the same id and secret (verified against {@code
 * ComposeTemplate.java}'s workspaces block, 2026-09-13), so one fallback pair is enough; this
 * profile sets only the unnamed default client's names, which is what the {@code qits} client's own
 * keys read.
 */
@QuarkusTest
@TestProfile(QitsOidcClientOldExtrasFallbackTest.OldExtrasOnly.class)
class QitsOidcClientOldExtrasFallbackTest {

  /**
   * <b>Its subject is an ABSENCE</b>, which is why it cannot be folded into {@link
   * QitsOidcClientResourceOverridesOldExtrasTest.BothSet} even though that map is a literal superset
   * of this one. The claim here is that the {@code qits} client falls back when {@code
   * QITS_RESOURCE_IDP_*} is not set; add the triple and the fallback is never reached and the test
   * proves nothing at all. Neither profile can join the module's shared one either: both rewrite the
   * id, secret and url of a client every application in this module wires, so a co-tenant would be
   * asserting against credentials that came from a test it has never heard of.
   */
  public static class OldExtrasOnly implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Raw env names, not the dotted keys — a QuarkusTestProfile override is a config source in its
      // own right, so a property expression that names one of these resolves it exactly as a real
      // environment variable would.
      return Map.of(
          "QUARKUS_OIDC_CLIENT_CLIENT_ID", "old-extras-qits-workspaces",
          "QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "old-extras-secret",
          "QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL", "http://old-extras-idp:8080/idp");
    }
  }

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientFallsBackToTheOldExtrasEnvNames() {
    assertEquals("old-extras-qits-workspaces", value("quarkus.oidc-client.qits.client-id"));
    assertEquals("old-extras-secret", value("quarkus.oidc-client.qits.credentials.secret"));
    assertEquals(
        "http://old-extras-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
  }

  @Test
  void theContainersOwnerKeyFollowsTheFallenBackId() {
    assertEquals("old-extras-qits-workspaces", value("qits.workspace.containers.owner"));
  }
}
