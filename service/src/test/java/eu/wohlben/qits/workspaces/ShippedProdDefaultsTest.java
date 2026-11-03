package eu.wohlben.qits.workspaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * What a DEPLOYED process resolves once the deployer stops handing it qits-configuration's extras
 * (qits-1391): only {@code QITS_ENVIRONMENT} and {@code QITS_DOMAIN} from the deployer, and
 * everything else from the shipped files. Every peer is addressed at its edge host, {@code
 * https://<label>.qits.<domain>} (qits-1412, feature qits-1406), and no peer address carries the
 * environment. Each assertion is a value the deployer used to state as an {@code env.*} extra on
 * dev-qits-workspaces, so a release that moved one of them would change a live value.
 *
 * <p>Assembled by hand from the shipped files at the ordinals a packaged jar gives them (domain's
 * {@code META-INF/microprofile-config.properties} at 100, the service's {@code
 * application.properties} at 250) plus an env source at 300, because the classpath copy is shadowed
 * by the test {@code application.properties} and a {@code @QuarkusTest} only ever runs {@code test}.
 */
class ShippedProdDefaultsTest {

  private static URL shipped(String module, String moduleRelative) {
    // Named through the module directory either way, because both modules carry a
    // META-INF/microprofile-config.properties and a bare relative path would find the wrong one.
    Path path = Path.of("..", module, moduleRelative);
    if (!Files.isRegularFile(path)) {
      path = Path.of(module, moduleRelative);
    }
    assertTrue(Files.isRegularFile(path), "cannot find shipped " + module + "/" + moduleRelative);
    try {
      return path.toUri().toURL();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SmallRyeConfig shipped(String profile, Map<String, String> env) {
    try {
      return new SmallRyeConfigBuilder()
          .addDefaultInterceptors()
          .withProfile(profile)
          .withSources(
              new PropertiesConfigSource(
                  shipped("domain", "src/main/resources/META-INF/microprofile-config.properties"),
                  100))
          .withSources(
              new PropertiesConfigSource(
                  shipped("service", "src/main/resources/application.properties"), 250))
          .withSources(new EnvConfigSource(env, 300))
          .build();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SmallRyeConfig deployed(String environment) {
    return shipped("prod", Map.of("QITS_ENVIRONMENT", environment, "QITS_DOMAIN", "wohlben.eu"));
  }

  @Test
  void aDeployedProcessResolvesEveryRetiredExtraToItsLiveValue() {
    SmallRyeConfig config = deployed("dev");
    assertEquals("true", config.getValue("qits.auth.machine.required", String.class));
    assertEquals("true", config.getValue("quarkus.oidc.tenant-enabled", String.class));
    assertEquals(
        "https://projects.qits.wohlben.eu", config.getValue("qits.projects.url", String.class));
    assertEquals(
        "https://projects.qits.wohlben.eu",
        config.getValue("quarkus.rest-client.qits-projects.url", String.class));
    assertEquals(
        "https://githost.qits.wohlben.eu", config.getValue("qits.githost.url", String.class));
    assertEquals(
        "https://githost.qits.wohlben.eu",
        config.getValue("qits.workspace.container-git-url", String.class));
    assertEquals(
        "https://mcp.qits.wohlben.eu",
        config.getValue("qits.workspaces.platform-mcp-url", String.class));
    assertEquals("dev-qits-workspaces", config.getValue("qits.workspace.git-host", String.class));
    // Telemetry moves in a later step of epic qits-428, not with the peers.
    assertEquals(
        "http://dev-qits-observability:8080",
        config.getValue("qits.observability.url", String.class));
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        config.getValue("quarkus.oidc.auth-server-url", String.class));
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        config.getValue("quarkus.oidc-client.qits.auth-server-url", String.class));
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        config.getValue("quarkus.oidc-client.auth-server-url", String.class));
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        config.getValue("quarkus.rest-client.qits-idp.url", String.class));
    assertEquals(
        "https://system.qits.wohlben.eu",
        config.getValue("quarkus.rest-client.qits-system.url", String.class));
    assertEquals(
        "https://ci.qits.wohlben.eu", config.getValue("quarkus.rest-client.qits-ci.url", String.class));
  }

  @Test
  void thePeerAddressesCarryNoEnvironment() {
    // The platform project has no environment label, so QITS_ENVIRONMENT leaves every peer address
    // (feature qits-1406); only the container's dial-home name for this very service still has it.
    SmallRyeConfig config = deployed("prod");
    assertEquals(
        "https://projects.qits.wohlben.eu", config.getValue("qits.projects.url", String.class));
    assertEquals(
        "https://githost.qits.wohlben.eu", config.getValue("qits.githost.url", String.class));
    assertEquals(
        "https://githost.qits.wohlben.eu",
        config.getValue("qits.workspace.container-git-url", String.class));
    assertEquals(
        "https://idp.qits.wohlben.eu/idp",
        config.getValue("quarkus.oidc.auth-server-url", String.class));
    assertEquals("prod-qits-workspaces", config.getValue("qits.workspace.git-host", String.class));
  }

  @Test
  void aDeployedProcessWithoutADomainRefusesToResolveItsPeers() {
    // No QITS_DOMAIN, no guessed domain: the expansion fails, and with it the boot.
    SmallRyeConfig config = shipped("prod", Map.of("QITS_ENVIRONMENT", "dev"));
    for (String key :
        List.of(
            "qits.githost.url",
            "qits.projects.url",
            "qits.workspace.container-git-url",
            "qits.workspaces.platform-mcp-url",
            "quarkus.oidc.auth-server-url",
            "quarkus.oidc-client.qits.auth-server-url",
            "quarkus.oidc-client.auth-server-url",
            "qits.system.url",
            "qits.ci.url")) {
      NoSuchElementException refused =
          assertThrows(NoSuchElementException.class, () -> config.getValue(key, String.class), key);
      assertTrue(refused.getMessage().contains("QITS_DOMAIN"), key + ": " + refused.getMessage());
    }
  }

  @Test
  void devAndTestKeepTheirBehaviour() {
    for (String profile : List.of("dev", "test")) {
      SmallRyeConfig config = shipped(profile, Map.of());
      assertEquals("false", config.getValue("qits.auth.machine.required", String.class), profile);
      assertFalse(
          config.getOptionalValue("qits.projects.url", String.class).isPresent(), profile);
      assertEquals("auto", config.getValue("qits.workspace.git-host", String.class), profile);
    }
  }
}
