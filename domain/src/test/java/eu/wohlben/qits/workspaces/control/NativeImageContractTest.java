package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The two things this module ships that only a GraalVM binary can get wrong, pinned where the JVM
 * suite can still see them.
 *
 * <p>Neither assertion can prove a native image works — that needs the binary, and both defects
 * below were found by running one. What they prevent is the silent re-introduction: the suite runs
 * against a postgres it spawns itself and needs no reflection registration, so the shipped
 * datasource url and the {@code @RegisterForReflection} lists are exactly the code no other test
 * looks at.
 */
public class NativeImageContractTest {

  /**
   * The shipped datasource must be nothing but the platform's generic resource contract: {@code
   * db-kind=postgresql} and the three {@code QITS_RESOURCE_DB_*} expressions, unresolved, with no
   * default behind them.
   *
   * <p>The intent is unchanged from what this assertion pinned when the store was H2 — <b>the
   * shipped config must never regress to something the native image cannot boot</b> — and both
   * spellings of that regression are what the deployed binary taught this repo. {@code AUTO_SERVER}
   * loaded {@code org.h2.server.TcpServer} by name, which is not in the image, and the process died
   * on its first connection. {@code ${user.home}} resolved to {@code ?} for a uid with no
   * {@code /etc/passwd} entry, because a native image reads it through {@code getpwuid(2)}, and the
   * process died at Flyway. Both were green through every JVM test, because the suite overrides this
   * url and never opens the shipped one — which is still true and is why this is read from the
   * resource rather than from the config.
   *
   * <p>An unresolved expression is not a weaker default than a file path: it is the refuse-to-boot
   * stance. A container with no {@code QITS_RESOURCE_DB_URL} dies naming what is missing, instead of
   * opening a store nobody meant.
   */
  @Test
  public void shippedDatasourceIsTheGenericResourceContractAndNothingElse() throws Exception {
    Properties shipped = new Properties();
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("META-INF/microprofile-config.properties")) {
      shipped.load(in);
    }
    assertEquals(
        "postgresql",
        shipped.getProperty("quarkus.datasource.workspaces.db-kind"),
        "the shipped datasource must be postgres — the store the platform provisions");
    assertEquals(
        "${QITS_RESOURCE_DB_URL}",
        shipped.getProperty("quarkus.datasource.workspaces.jdbc.url"),
        "the shipped url must be the injected resource variable, with no default behind it");
    assertEquals(
        "${QITS_RESOURCE_DB_USERNAME}",
        shipped.getProperty("quarkus.datasource.workspaces.username"),
        "the shipped username must be the injected resource variable");
    assertEquals(
        "${QITS_RESOURCE_DB_PASSWORD}",
        shipped.getProperty("quarkus.datasource.workspaces.password"),
        "the shipped password must be the injected resource variable");

    // No datasource key may root anything at ${user.home} again. The one remaining ${user.home}
    // default in this file — qits.workspaces.data-dir — is deliberate and docker/Dockerfile names it
    // explicitly for exactly this reason; a database is the case that has no such answer.
    for (String key : shipped.stringPropertyNames()) {
      if (!key.startsWith("quarkus.datasource.")) {
        continue;
      }
      String value = shipped.getProperty(key);
      assertFalse(
          value.contains("${user.home}"),
          key
              + " roots a shipped datasource at ${user.home}, which a native image resolves through"
              + " getpwuid(2) — `?` for uid 1001, and a boot failure only the packaged artifact ever"
              + " sees: "
              + value);
    }
  }

  /**
   * {@code WorkspaceDaemonRegistry} deserializes {@link QitsConfig} with a bare {@code
   * ObjectMapper}, so nothing on a JAX-RS signature names it and native-image keeps no members
   * unless it is registered. {@code @RegisterForReflection} does not descend into nested types, so
   * the risk is not the annotation going missing — it is a new nested record being added above it
   * and quietly not registered, which turns into an empty config and a warning that reads like a
   * daemon bug.
   */
  @Test
  public void everyNestedQitsConfigRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(QitsConfig.class.getAnnotation(RegisterForReflection.class).targets());
    List<Class<?>> nested =
        Arrays.stream(QitsConfig.class.getDeclaredClasses()).filter(Class::isRecord).toList();

    assertTrue(registered.contains(QitsConfig.class), "QitsConfig itself is not registered");
    List<String> missing =
        nested.stream()
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "nested records not in QitsConfig's @RegisterForReflection(targets): " + missing);
  }

  /**
   * The enums the record tree binds are targets too. Jackson resolves an enum's constants
   * reflectively, so an unregistered enum makes the binary's {@code QitsConfig} read throw — caught
   * and degraded to the EMPTY config. Measured live (D1) with the two enums the workspace services'
   * declarations bound, before qits-947 removed them; the JVM suite could not see it, which is why
   * the registration is pinned here. No enum is bound today, so this passes vacuously; it walks the
   * record components rather than naming enums, so the next enum-typed field joins the assertion by
   * existing.
   */
  @Test
  public void everyEnumBoundByTheQitsConfigTreeIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(QitsConfig.class.getAnnotation(RegisterForReflection.class).targets());
    Set<Class<?>> enums =
        registered.stream()
            .filter(Class::isRecord)
            .flatMap(record -> Arrays.stream(record.getRecordComponents()))
            .map(java.lang.reflect.RecordComponent::getType)
            .filter(Class::isEnum)
            .collect(Collectors.toSet());

    List<String> missing =
        enums.stream()
            .filter(e -> !registered.contains(e))
            .map(Class::getSimpleName)
            .collect(Collectors.toList());
    assertTrue(
        missing.isEmpty(),
        "enums bound by QitsConfig's record tree but not in @RegisterForReflection(targets): "
            + missing);
  }
}
