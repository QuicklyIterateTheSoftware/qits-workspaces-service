package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.quarkus.test.junit.QuarkusTest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The service module's half of the native-image contract — the parts of it a JVM run can still
 * check. See {@code eu.wohlben.qits.workspaces.control.NativeImageContractTest} in {@code domain}
 * for the same idea over the shipped datasource url and {@code QitsConfig}.
 *
 * <p>Both defects pinned here were found by probing the binary and are invisible from a passing
 * suite: on the JVM, reflection needs no registration and {@code application.properties} is an
 * ordinary runtime config source.
 */
@QuarkusTest
public class NativeImageContractTest {

  /**
   * No {@code static} field anywhere in {@code daemonhost} may hold a {@link java.util.Random} — a
   * class initializer runs during the image build, so a {@code static final SecureRandom} is
   * constructed by the builder, lands in the image heap, and native-image aborts the whole build
   * with "Detected an instance of Random/SplittableRandom class in the image heap".
   *
   * <p>{@code WorkspaceTunnels} shipped exactly that: the nonce generator for the reverse tunnel.
   * The suite was green, a fast-jar ran the full browser-to-daemon chain, and the service simply
   * could not be compiled — which nothing here would have said, because the only symptom is a build
   * that never ran. Making the field an instance field of a CDI bean moves its construction to
   * application startup.
   *
   * <p>This one is worth pinning rather than trusting to memory because the failure is loud but
   * <em>late</em>: it costs a native build to discover, and the obvious "tidy this up" edit —
   * hoisting a helper's dependency into a constant — reintroduces it.
   */
  @Test
  public void noStaticRandomInTheDaemonHostPackage() {
    for (Class<?> type :
        List.of(
            eu.wohlben.qits.workspaces.daemonhost.WorkspaceTunnels.class,
            eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonRegistry.class,
            eu.wohlben.qits.workspaces.daemonhost.DaemonStreamRoute.class)) {
      for (java.lang.reflect.Field field : type.getDeclaredFields()) {
        boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());
        assertTrue(
            !isStatic || !java.util.Random.class.isAssignableFrom(field.getType()),
            type.getSimpleName()
                + "."
                + field.getName()
                + " is a static Random — it will be constructed into the image heap and the native"
                + " build will fail. Make it an instance field of the bean.");
      }
    }
  }

  /**
   * {@code quarkus.rest.path} is a build-time config item and is therefore absent from a native
   * image's <em>runtime</em> config. {@link CaptureCorsRoute} is a raw Vert.x route that has to
   * build its own path, so reading the Quarkus key gave it the {@code defaultValue} in the binary:
   * the preflight registered on {@code /capture} and the real endpoint answered browsers with
   * RESTEasy's bare 200 and no CORS headers. The application-owned key it reads instead is
   * runtime-phase in both runtimes; this test is what keeps someone from "simplifying" it back.
   */
  @Test
  public void captureCorsRouteReadsTheApplicationOwnedRestPath() throws Exception {
    ConfigProperty configured =
        CaptureCorsRoute.class.getDeclaredField("restPath").getAnnotation(ConfigProperty.class);
    assertEquals(
        "qits.rest.path",
        configured.name(),
        "a raw router route must not read a build-time quarkus.* key: it resolves to the"
            + " defaultValue in a native image");
  }

  @ConfigProperty(name = "qits.rest.path")
  String ownedRestPath;

  @ConfigProperty(name = "quarkus.rest.path")
  String quarkusRestPath;

  /** And the Quarkus key still has to end up at the same place, or the preflight misses the POST. */
  @Test
  public void quarkusRestPathIsDerivedFromTheApplicationOwnedOne() {
    assertEquals(
        ownedRestPath, quarkusRestPath, "quarkus.rest.path must stay derived from qits.rest.path");
  }

  /**
   * The capture payload never appears on a signature Quarkus can index — the request is taken as
   * {@code byte[]} and the reply hidden inside a {@code jakarta.ws.rs.core.Response} — so every one
   * of its records has to be registered by hand, and {@code @RegisterForReflection} does not descend
   * into nested types. Unregistered, the binary answered 400 "Malformed capture payload" to
   * everything.
   */
  @Test
  public void everyCapturePayloadRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(CaptureResource.class.getAnnotation(RegisterForReflection.class).targets());
    List<String> missing =
        Stream.concat(
                Stream.of(
                    CaptureResource.CaptureRequest.class, CaptureResource.CaptureResponse.class),
                Stream.of(
                        CaptureResource.CaptureRequest.class.getDeclaredClasses(),
                        CaptureResource.CaptureResponse.class.getDeclaredClasses())
                    .flatMap(Arrays::stream))
            .filter(Class::isRecord)
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .toList();
    assertTrue(
        missing.isEmpty(),
        "capture records not in CaptureResource's @RegisterForReflection(targets): " + missing);
  }

  /**
   * The editor door's answer rides a bare {@code jakarta.ws.rs.core.Response} too — {@code fresh()}
   * decides 201 vs 200, so the record never appears on a signature Quarkus can index. Unregistered,
   * the deployed binary answered every {@code /editor/ensure} with a 500 "No serializer found"
   * while the whole JVM suite stayed green — measured on dev, 2026-08-31, the day it shipped.
   */
  @Test
  public void everyEditorDoorRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(EditorController.class.getAnnotation(RegisterForReflection.class).targets());
    List<String> missing =
        Stream.concat(
                Stream.of(EditorController.EditorSessionResponse.class),
                Arrays.stream(EditorController.EditorSessionResponse.class.getDeclaredClasses()))
            .filter(Class::isRecord)
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .toList();
    assertTrue(
        missing.isEmpty(),
        "editor records not in EditorController's @RegisterForReflection(targets): " + missing);
  }

  /**
   * The runner create answers through a bare {@code Response}, so its record and the runner view it
   * carries are on no signature the native build indexes — the editor door's defect, one door over.
   */
  @Test
  public void everyRunnerRegistrationRecordIsRegisteredForReflection() {
    Set<Class<?>> registered =
        Set.of(
            WorkspaceRunnerController.class.getAnnotation(RegisterForReflection.class).targets());
    List<String> missing =
        Stream.of(
                WorkspaceRunnerController.RunnerRegistrationDto.class,
                eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto.class,
                eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto.Login.class)
            .filter(c -> !registered.contains(c))
            .map(Class::getSimpleName)
            .toList();
    assertTrue(
        missing.isEmpty(),
        "runner records not in WorkspaceRunnerController's @RegisterForReflection: " + missing);
  }

  /**
   * The two resources the workspace runners load by a computed name are listed for the native image
   * and are really on the classpath under that name — the install script template, and the runner
   * pin's version file.
   */
  @Test
  public void theRunnerResourcesAreIncludedInTheNativeImage() {
    String includes =
        org.eclipse.microprofile.config.ConfigProvider.getConfig()
            .getOptionalValue("quarkus.native.resources.includes", String.class)
            .orElse("");
    for (String resource :
        List.of(
            "eu/wohlben/qits/runner/toolkit/install/runner-install.sh.tmpl",
            "eu/wohlben/qits/workspacesrunner/protocol/workspaces-runner-binary.properties")) {
      assertTrue(
          Arrays.asList(includes.split(",")).contains(resource),
          resource + " is not in quarkus.native.resources.includes: " + includes);
      assertTrue(
          Thread.currentThread().getContextClassLoader().getResource(resource) != null,
          resource + " is not on the classpath; the pinned jar moved it");
    }
  }
}
