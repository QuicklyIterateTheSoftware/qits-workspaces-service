package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Once a deployment declares {@code idp:client} (service-client-identity-plan.md, C5) qits-deployments
 * injects {@code QITS_RESOURCE_IDP_*} beside whatever old extras keys are still there from before the
 * cutover — deleting them is a later, unhurried step (C9). The {@code qits} client must read the new
 * triple first, every time, or a deployment mid-cutover would keep presenting its old secret.
 */
@QuarkusTest
@TestProfile(QitsOidcClientResourceOverridesOldExtrasTest.BothSet.class)
class QitsOidcClientResourceOverridesOldExtrasTest {

  public static class BothSet implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "QITS_RESOURCE_IDP_CLIENT_ID", "resource-qits-workspaces",
          "QITS_RESOURCE_IDP_CLIENT_SECRET", "resource-secret",
          "QITS_RESOURCE_IDP_URL", "http://resource-idp:8080/idp",
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
  void theResourceEnvWinsOverTheOldExtrasEnv() {
    assertEquals("resource-qits-workspaces", value("quarkus.oidc-client.qits.client-id"));
    assertEquals("resource-secret", value("quarkus.oidc-client.qits.credentials.secret"));
    assertEquals("http://resource-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
  }

  @Test
  void theContainersOwnerKeyFollowsTheResourceProvidedId() {
    assertEquals("resource-qits-workspaces", value("qits.workspace.containers.owner"));
  }
}
