package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.ConfigValue;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The two phantom named oidc clients, {@code githost} and {@code projects}, staying inert against
 * the environment a real deployment carries.
 *
 * <p><b>What the bug was.</b> Neither name is injected by any code here — {@code qits} carries every
 * outbound identity this service has — so both blocks were deleted as dead. They are not dead:
 * SmallRye Config discovers a {@code quarkus.oidc-client.<name>} map key from the ENVIRONMENT
 * source, so any single {@code QUARKUS_OIDC_CLIENT_GITHOST_*} variable mints the key, and with no
 * properties file saying otherwise {@code client-enabled} and {@code discovery-enabled} both default
 * to {@code true}. {@code OidcClientsImpl}'s constructor then awaits {@code createOidcClient(…)} for
 * every named key, serially, before the HTTP listener accepts, and a discovering client dials the
 * issuer there. An issuer that accepts and does not answer fails the boot on the connection timeout.
 *
 * <p><b>Why this test is not a {@code @QuarkusTest}.</b> The claim is about CONFIG RESOLUTION
 * against an environment this JVM cannot have — a test cannot set its own environment variables, and
 * a {@code QuarkusTestProfile}'s overrides are a source of their own at an ordinal that is not the
 * environment's, so they would answer a different question than the one that matters here. What
 * decides the outcome is which source wins, so the measurement is the real {@link EnvConfigSource}
 * at its real {@code ORDINAL} over the real shipped properties file at the 250 Quarkus loads it at.
 * It also costs no Quarkus application, which is the module's test-profile budget rule.
 *
 * <p><b>The environment below is the live one</b>, read from qits-configuration for application
 * {@code qits-workspaces} in {@code dev} on 2026-09-15. The {@code _CLIENT_ENABLED=true} entries are
 * the whole reason the shipped {@code client-enabled=false} is not the line that fixes this.
 */
class PhantomOidcClientsNeutralisedTest {

  /** The shipped file, at the path the deployable's classpath carries it from. */
  private static final String SHIPPED_PROPERTIES = "service/src/main/resources/application.properties";

  /**
   * The ordinal Quarkus loads a classpath {@code application.properties} at. The environment source
   * sits at {@link EnvConfigSource#ORDINAL} above it, which is the fact this whole test is about.
   */
  private static final int APPLICATION_PROPERTIES_ORDINAL = 250;

  /** The repository root, found by walking up from wherever surefire started this module. */
  private static Path root() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      if (Files.isRegularFile(at.resolve(SHIPPED_PROPERTIES))) {
        return at;
      }
    }
    throw new AssertionError("no " + SHIPPED_PROPERTIES + " above " + Path.of("").toAbsolutePath());
  }

  private static Map<String, String> shippedProperties() throws IOException {
    Properties parsed = new Properties();
    try (InputStream in = Files.newInputStream(root().resolve(SHIPPED_PROPERTIES))) {
      parsed.load(in);
    }
    Map<String, String> flat = new HashMap<>();
    for (String name : parsed.stringPropertyNames()) {
      flat.put(name, parsed.getProperty(name));
    }
    return flat;
  }

  /**
   * What qits-configuration holds for this application in {@code dev}. The two named families are
   * `orphaned` there and still injected, which is exactly the state this test exists for.
   */
  private static Map<String, String> deployedEnvironment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_CLIENT_ID", "dev-qits-workspaces");
    env.put("QUARKUS_OIDC_CLIENT_CREDENTIALS_SECRET", "deployed-secret");
    env.put("QUARKUS_OIDC_CLIENT_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CLIENT_ID", "dev-qits-workspaces");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_CREDENTIALS_SECRET", "deployed-secret");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_GITHOST_GRANT_OPTIONS_CLIENT_AUDIENCE", "dev-qits-githost");
    env.put("QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ENABLED", "true");
    env.put("QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ID", "dev-qits-workspaces");
    env.put("QUARKUS_OIDC_CLIENT_PROJECTS_CREDENTIALS_SECRET", "deployed-secret");
    env.put("QUARKUS_OIDC_CLIENT_PROJECTS_AUTH_SERVER_URL", "http://qits-platform-idp:8080/idp");
    env.put("QUARKUS_OIDC_CLIENT_PROJECTS_GRANT_OPTIONS_CLIENT_AUDIENCE", "dev-qits-projects");
    return env;
  }

  /** The shipped file plus that environment, at the two ordinals a deployed process has them at. */
  private static SmallRyeConfig deployedConfig() throws IOException {
    return new SmallRyeConfigBuilder()
        .addDefaultInterceptors()
        // The deployable runs under no named profile; %dev and %test lines in the file are inert.
        .withProfile("prod")
        .withSources(
            new PropertiesConfigSource(
                shippedProperties(), SHIPPED_PROPERTIES, APPLICATION_PROPERTIES_ORDINAL))
        .withSources(new EnvConfigSource(deployedEnvironment(), EnvConfigSource.ORDINAL))
        .build();
  }

  @Test
  void theEnvironmentOutranksTheShippedFile() {
    // The premise everything below rests on, stated as an assertion so a SmallRye upgrade that
    // moved either number fails here rather than silently changing which line is load-bearing.
    assertTrue(
        EnvConfigSource.ORDINAL > APPLICATION_PROPERTIES_ORDINAL,
        "the environment must outrank application.properties, else client-enabled would suffice");
  }

  @Test
  void neitherPhantomClientDiscoversItsIssuer() throws IOException {
    SmallRyeConfig config = deployedConfig();

    // THE LINES THAT FIX THE BUG. Nothing in the deployment spells ..._DISCOVERY_ENABLED or
    // ..._TOKEN_PATH, so the shipped file is the highest source that answers and the recorder builds
    // the token endpoint locally rather than fetching metadata over the wire at runtime init.
    for (String client : new String[] {"githost", "projects"}) {
      assertEquals(
          "false",
          config.getValue("quarkus.oidc-client." + client + ".discovery-enabled", String.class),
          client + " would dial its issuer before the HTTP listener accepts");
      assertEquals(
          "token",
          config.getValue("quarkus.oidc-client." + client + ".token-path", String.class),
          client
              + " needs a token path beside discovery-enabled=false, or the recorder throws"
              + " ConfigurationException on a null token endpoint");
    }
  }

  @Test
  void theShippedClientEnabledIsOverriddenByTheDeploymentAndIsStatedAnyway() throws IOException {
    SmallRyeConfig config = deployedConfig();

    // This is the measurement, not a wish: `client-enabled=false` loses to the deployment's `true`.
    // It is asserted so nobody reads the shipped `false` as the fix and deletes discovery-enabled.
    for (String client : new String[] {"githost", "projects"}) {
      ConfigValue enabled =
          config.getConfigValue("quarkus.oidc-client." + client + ".client-enabled");
      assertEquals("true", enabled.getValue(), client + ": the environment is expected to win here");
      assertEquals(EnvConfigSource.NAME, enabled.getConfigSourceName());
    }

    // And it is still in the file, because it is the intent and it is what takes effect the moment
    // the QUARKUS_OIDC_CLIENT_{GITHOST,PROJECTS}_* entries are deleted from the deployment.
    Map<String, String> shipped = shippedProperties();
    assertEquals("false", shipped.get("quarkus.oidc-client.githost.client-enabled"));
    assertEquals("false", shipped.get("quarkus.oidc-client.projects.client-enabled"));
  }

  @Test
  void theRealQitsClientStillReadsTheEnvironmentAndStaysSwitchedOn() throws IOException {
    SmallRyeConfig config = deployedConfig();

    // THE ONE THING THAT WOULD BE WORSE THAN THE BUG. `qits` is the client that carries every
    // outbound identity this service has, and its keys are expressions over raw ENV NAMES —
    // ${QUARKUS_OIDC_CLIENT_CLIENT_ENABLED:false} and friends. Declaring named `githost`/`projects`
    // keys in this file must not make any of them resolve a properties-file `false` instead.
    assertEquals("true", config.getValue("quarkus.oidc-client.qits.client-enabled", String.class));
    assertEquals(
        "dev-qits-workspaces", config.getValue("quarkus.oidc-client.qits.client-id", String.class));
    assertEquals(
        "deployed-secret",
        config.getValue("quarkus.oidc-client.qits.credentials.secret", String.class));
    assertEquals(
        "http://qits-platform-idp:8080/idp",
        config.getValue("quarkus.oidc-client.qits.auth-server-url", String.class));
    // Its own two lines are literals and are unaffected by the phantom pair carrying the same names.
    assertEquals("false", config.getValue("quarkus.oidc-client.qits.discovery-enabled", String.class));
    assertEquals("token", config.getValue("quarkus.oidc-client.qits.token-path", String.class));
  }

  @Test
  void theUnnamedDefaultClientIsUnchangedToo() throws IOException {
    SmallRyeConfig config = deployedConfig();

    // It is enabled by the same deployment entry that enables `qits` (that is the whole reason it is
    // spelled at all), and its own discovery/token lines keep that harmless. Same claim, same file,
    // and it would break the same way if a named block's keys bled across.
    assertEquals("false", config.getValue("quarkus.oidc-client.discovery-enabled", String.class));
    assertEquals("token", config.getValue("quarkus.oidc-client.token-path", String.class));
    assertEquals(
        "false", config.getValue("quarkus.oidc-client.early-tokens-acquisition", String.class));
  }
}
