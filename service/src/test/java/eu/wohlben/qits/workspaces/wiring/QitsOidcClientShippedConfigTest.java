package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * The one named oidc client, {@code qits}, as the shipped {@code application.properties} resolves
 * it with no {@code QITS_RESOURCE_IDP_*} or old extras env set — the "nothing configured" arm every
 * clone-alone build and every other test in this repo runs on (service-client-identity-plan.md, C4).
 *
 * <p>{@link QitsOidcClientOldExtrasFallbackTest} and {@link
 * QitsOidcClientResourceOverridesOldExtrasTest} hold the other two arms — the old extras keys alone,
 * and the new resource keys winning over them — each in its own {@code @QuarkusTest} because a
 * {@code @TestProfile}'s config overrides are fixed for the life of one boot.
 */
@QuarkusTest
class QitsOidcClientShippedConfigTest {

  private static String value(String key) {
    Config config = ConfigProvider.getConfig();
    return config.getValue(key, String.class);
  }

  @Test
  void theQitsClientResolvesItsOwnLiteralDefaults() {
    // QITS_ENVIRONMENT is unset here, so the shipped default's own fallback applies: dev.
    assertEquals(
        "http://dev-qits-platform-idp:8080/idp", value("quarkus.oidc-client.qits.auth-server-url"));
    assertEquals("qits-workspaces", value("quarkus.oidc-client.qits.client-id"));
    // Empty, not absent — SmallRye reads a configured-empty String as null (the trap AGENTS.md
    // documents), so an empty secret reads as an empty Optional rather than as "" itself.
    Optional<String> secret =
        ConfigProvider.getConfig()
            .getOptionalValue("quarkus.oidc-client.qits.credentials.secret", String.class);
    assertTrue(secret.isEmpty());
    // One audience for every outbound call now, never qits-containers, qits-githost or
    // qits-projects specifically.
    assertEquals("qits-platform", value("quarkus.oidc-client.qits.grant-options.client.audience"));
  }

  @Test
  void theClientStaysDisabledUnderTest() {
    // %test.quarkus.oidc-client.qits.client-enabled=false wins over the shipped expression
    // regardless of what QUARKUS_OIDC_CLIENT_CLIENT_ENABLED says — the arm every test in this repo
    // is on, so a suite never dials a real idp.
    assertEquals("false", value("quarkus.oidc-client.qits.client-enabled"));
  }

  @Test
  void theTwoPhantomNamedClientsAreNeutralisedInARealBoot() {
    // `githost` and `projects` are minted as map keys by the deployment's QUARKUS_OIDC_CLIENT_*
    // environment, which this arm does not have — so here they exist only because this file spells
    // them, and all three lines answer from it. That the environment OVERRIDES client-enabled, and
    // that discovery-enabled is therefore the line doing the work, is
    // PhantomOidcClientsNeutralisedTest's claim; this is the same keys resolving through a real
    // Quarkus config, which is the half a hand-built SmallRyeConfig cannot speak for.
    for (String client : new String[] {"githost", "projects"}) {
      assertEquals("false", value("quarkus.oidc-client." + client + ".client-enabled"));
      assertEquals("false", value("quarkus.oidc-client." + client + ".discovery-enabled"));
      assertEquals("token", value("quarkus.oidc-client." + client + ".token-path"));
    }
  }

  @Test
  void theContainersOwnerKeyFollowsTheQitsClientsId() {
    // qits.workspace.containers.owner reads quarkus.oidc-client.qits.client-id by default —
    // OwnerGuard compares this string to a machine token's `sub` once the gate is on.
    assertEquals("qits-workspaces", value("qits.workspace.containers.owner"));
  }
}
