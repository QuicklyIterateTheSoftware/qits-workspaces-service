package eu.wohlben.qits.workspaces.wiring;

import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersClient;
import eu.wohlben.qits.containers.client.contracts.ClientUsage;
import eu.wohlben.qits.workspaces.containershost.ContainersContract;
import eu.wohlben.qits.workspaces.control.AgentDaemon;
import eu.wohlben.qits.workspaces.daemonhost.DaemonAgentClient;
import eu.wohlben.qits.workspaces.daemonhost.WorkspaceDaemonContract;
import eu.wohlben.qits.workspaces.testing.contracts.ContractRow;
import eu.wohlben.qits.workspaces.testing.contracts.ContractTable;
import eu.wohlben.qits.workspaces.testing.contracts.GoldenMasters;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import jakarta.ws.rs.HttpMethod;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.junit.jupiter.api.Test;

/**
 * <b>Every call this service makes to a qits service has a contract row</b> (qits-1149). The calls
 * are read off the code, never listed by hand; a new one without a row fails here naming it.
 *
 * <ul>
 *   <li><b>MicroProfile REST clients</b>: every {@code @RegisterRestClient} interface among this
 *       module's compiled classes, every method carrying an HTTP method — its {@code configKey}
 *       names the provider, its {@code @Path}s the route. It needs a row (live or pending) whose
 *       operation the provider records under that method and route, in {@link ProjectsContract},
 *       {@link IdpContract}, {@code SystemContract} or {@link CiContract} (epic qits-1681).
 *   <li><b>{@code qits-containers-client}</b>: every {@link ContainersClient} method this module's
 *       compiled classes reference — read by the client kit's own {@link ClientUsage#scan}
 *       (qits-1163) — needs a {@link ContainersContract} row for its operation, and one the kit
 *       cannot name fails outright.
 *   <li><b>{@link DaemonAgentClient}</b>: every {@link AgentDaemon} method it implements needs a
 *       {@link WorkspaceDaemonContract} row for its operation.
 *   <li><b>JDK and Vert.x HTTP clients</b>: every class here that holds one is one of the two above
 *       or a named exclusion.
 *   <li><b>quarkus-oidc</b>: a named {@code quarkus-oidc-client} client needs the token mint row, and
 *       the tenant its signing-key row, in {@link IdpContract}.
 * </ul>
 *
 * <p><b>Named exclusions</b>, each with its reason: {@code ContainerProxyRoute} (the proxy hands a
 * caller's own request to a daemon verbatim, so the caller's pact against qits-workspace-daemon is
 * that call's contract); {@code WorkspaceTunnels} (the byte tunnel under every daemon call, and no
 * call of its own); {@link AgentDaemon#reachable} (asks the tunnel registry, never the daemon); and
 * {@code ContainersClient.touch} (WorkspaceContainers keeps the verb, but nothing has called it since
 * the editor keepalive left, qits-1152). Not seen by this guard, because the call is made inside a
 * library rather than by a class of this module: the qits-eventstream publisher and catch-up reader
 * (qits-events, qits-1163's library-made pacts) and the OpenTelemetry exporter.
 */
class OutboundCallsGuardTest {

  /** The REST clients' {@code configKey}s: the provider, and the base path its url ends in. */
  private static final Map<String, Base> REST_CLIENT_KEYS =
      Map.of(
          "qits-idp", new Base(GoldenMasters.IDP, "/idp"),
          "qits-projects", new Base(GoldenMasters.PROJECTS, ""),
          "qits-system", new Base(GoldenMasters.SYSTEM, ""),
          "qits-ci", new Base(GoldenMasters.CI, ""),
          "qits-events", new Base(EventsLogPactTest.EVENTS, ""));

  /**
   * The route of each {@code SystemContract} operation, for while its rows wait (epic qits-1481):
   * the pinned qits-system golden masters do not record them yet, so their method and path cannot
   * be read off the recording. Once they are, the recording answers and this map is not asked.
   */
  private static final Map<String, String> PENDING_ROUTES =
      Map.of(
          "createLease", "POST /system/api/leases",
          "listLeases", "GET /system/api/leases",
          "getLease", "GET /system/api/leases/{}",
          "endLease", "DELETE /system/api/leases/{}",
          "purgeWorkVolume", "DELETE /system/api/work-volumes/{}",
          "listTargets", "GET /system/api/targets");

  private record Base(GoldenMasters.Provider provider, String prefix) {}

  /** The provider operation each {@link ContainersClient} method calls. */
  private static final Map<String, String> CONTAINERS_OPERATIONS =
      Map.of(
          "ensure", "ensureContainer",
          "status", "getContainer",
          "list", "listWorkloadContainers",
          "stop", "stopContainer",
          "delete", "deleteContainer",
          "ensureVolume", "ensureVolume",
          "deleteVolume", "deleteVolume");

  private static final Set<String> CONTAINERS_EXCLUDED = Set.of("touch");

  /** The daemon operation each {@link AgentDaemon} method calls. */
  private static final Map<String, String> DAEMON_OPERATIONS =
      Map.of(
          "start", "startAgentWorktree",
          "turn", "deliverAgentTurn",
          "entity", "setAgentEntity",
          "cleanupCheck", "agentCleanupCheck",
          "remove", "removeAgentWorktree");

  private static final Set<String> DAEMON_EXCLUDED = Set.of("reachable");

  /** The classes allowed to hold an HTTP client, and why. */
  private static final Map<String, String> HTTP_CLIENT_HOLDERS =
      Map.of(
          "eu/wohlben/qits/workspaces/containers/ContainersClientProducer",
              "builds ContainersClient: ContainersContract",
          "eu/wohlben/qits/workspaces/daemonhost/DaemonAgentClient", "WorkspaceDaemonContract",
          "eu/wohlben/qits/workspaces/api/ContainerProxyRoute",
              "excluded: a caller's own daemon request, proxied verbatim",
          "eu/wohlben/qits/workspaces/daemonhost/WorkspaceTunnels",
              "excluded: the tunnel transport, no call of its own");

  private static final Set<String> HTTP_CLIENT_TYPES =
      Set.of(
          "java/net/http/HttpClient",
          "io/vertx/core/http/HttpClient",
          "io/vertx/ext/web/client/WebClient",
          "eu/wohlben/qits/integrations/http/QitsHttpClients");

  @Test
  void everyRestClientMethodHasARow() throws Exception {
    List<String> missing = new ArrayList<>();
    int seen = 0;
    for (ClassModel model : mainClasses()) {
      if (!annotated(model, RegisterRestClient.class)) {
        continue;
      }
      Class<?> type = load(model);
      RegisterRestClient registration = type.getAnnotation(RegisterRestClient.class);
      Base base = REST_CLIENT_KEYS.get(registration.configKey());
      if (base == null) {
        missing.add(
            type.getSimpleName()
                + ": configKey '"
                + registration.configKey()
                + "' names no provider this guard knows — add it, and its contract");
        continue;
      }
      String classPath = path(type.getAnnotation(jakarta.ws.rs.Path.class));
      for (Method method : type.getDeclaredMethods()) {
        String verb = httpMethod(method);
        if (verb == null || method.isDefault() || Modifier.isStatic(method.getModifiers())) {
          continue;
        }
        seen++;
        String route =
            normalise(base.prefix() + classPath + path(method.getAnnotation(jakarta.ws.rs.Path.class)));
        if (!covered(base.provider(), rowsOf(base.provider()), verb, route)) {
          missing.add(
              type.getSimpleName() + "." + method.getName() + ": " + verb + " " + route
                  + " has no " + base.provider().repository() + " row");
        }
      }
    }
    assertTrue(seen > 0, "no REST client method found: is the scan reading the main classes?");
    assertTrue(missing.isEmpty(), "calls without a contract row:\n  " + String.join("\n  ", missing));
  }

  @Test
  void everyContainersClientCallHasARow() {
    // The client kit's own scan of this module's compiled classes: every ContainersClient method
    // referenced from the main code, keyed name + descriptor, and the ones it cannot name.
    ClientUsage usage = ClientUsage.scan(List.of(Path.of("target/classes")));
    Set<String> invoked = new TreeSet<>();
    usage.calls().keySet().forEach(call -> invoked.add(call.substring(0, call.indexOf('('))));
    invoked.remove("<init>");
    assertTrue(!invoked.isEmpty(), "no ContainersClient call found: is the scan reading the main classes?");
    List<String> missing = new ArrayList<>();
    for (String call : usage.unknown()) {
      if (!call.startsWith("<init>(")) {
        missing.add("ContainersClient." + call + " is called and the client kit names no feature for it");
      }
    }
    for (String method : invoked) {
      if (CONTAINERS_EXCLUDED.contains(method)) {
        continue;
      }
      String operationId = CONTAINERS_OPERATIONS.get(method);
      if (operationId == null) {
        missing.add("ContainersClient." + method + " is called and maps to no qits-containers operation");
      } else if (!hasRow(ContainersContract.TABLE, operationId)) {
        missing.add("ContainersClient." + method + " (" + operationId + ") has no row");
      }
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  @Test
  void everyDaemonCallHasARow() {
    List<String> missing = new ArrayList<>();
    for (Method method : AgentDaemon.class.getMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || DAEMON_EXCLUDED.contains(method.getName())) {
        continue;
      }
      try {
        DaemonAgentClient.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
      } catch (NoSuchMethodException inherited) {
        continue; // a default the client does not override makes no call of its own
      }
      String operationId = DAEMON_OPERATIONS.get(method.getName());
      if (operationId == null) {
        missing.add("AgentDaemon." + method.getName() + " maps to no daemon operation");
      } else if (!hasRow(WorkspaceDaemonContract.TABLE, operationId)) {
        missing.add("AgentDaemon." + method.getName() + " (" + operationId + ") has no row");
      }
    }
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  @Test
  void everyClassHoldingAnHttpClientIsKnown() throws Exception {
    List<String> unknown = new ArrayList<>();
    for (ClassModel model : mainClasses()) {
      String name = model.thisClass().asInternalName();
      String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
      if (HTTP_CLIENT_HOLDERS.containsKey(outer)) {
        continue;
      }
      for (PoolEntry entry : model.constantPool()) {
        if (entry instanceof ClassEntry type && HTTP_CLIENT_TYPES.contains(type.asInternalName())) {
          unknown.add(name + " holds " + type.asInternalName());
          break;
        }
      }
    }
    assertTrue(
        unknown.isEmpty(),
        "an HTTP client no contract covers — add a table and a row, or name it here with its reason:\n  "
            + String.join("\n  ", unknown));
  }

  @Test
  void theOidcCallsHaveRows() throws IOException {
    Properties shipped = new Properties();
    try (InputStream in = Files.newInputStream(mainClassesDir().resolve("application.properties"))) {
      shipped.load(in);
    }
    List<String> missing = new ArrayList<>();
    boolean namedClient =
        shipped.stringPropertyNames().stream()
            .anyMatch(k -> k.matches("quarkus\\.oidc-client\\.[^.]+\\.auth-server-url"));
    if (namedClient && !hasRow(IdpContract.TABLE, "issueToken")) {
      missing.add("a quarkus-oidc-client client is configured, and no row mints its token");
    }
    if (shipped.containsKey("quarkus.oidc.auth-server-url") && !hasRow(IdpContract.TABLE, "getJwks")) {
      missing.add("the quarkus-oidc tenant is configured, and no row reads its signing keys");
    }
    assertTrue(namedClient, "the shipped properties configure no oidc client: is the scan reading them?");
    assertTrue(missing.isEmpty(), String.join("\n", missing));
  }

  // --- support -----------------------------------------------------------------------------------

  /** A (method, route) pair a provider records, for every row of its table. */
  private static boolean covered(
      GoldenMasters.Provider provider, List<String> operationIds, String verb, String route) {
    for (String operationId : operationIds) {
      var op = GoldenMasters.anyOperation(provider, operationId);
      if (op.isEmpty()
          && provider.equals(GoldenMasters.SYSTEM)
          && (verb.toUpperCase() + " " + route).equals(PENDING_ROUTES.get(operationId))) {
        return true;
      }
      if (op.isPresent()
          && op.get().method().equalsIgnoreCase(verb)
          && normalise(op.get().path().split("\\?", 2)[0]).equals(route)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> rowsOf(GoldenMasters.Provider provider) {
    if (provider.equals(GoldenMasters.PROJECTS)) {
      return ProjectsContract.CASES.stream().map(ProjectsContract.Case::operationId).toList();
    }
    if (provider.equals(EventsLogPactTest.EVENTS)) {
      return List.of(EventsLogPactTest.OPERATION);
    }
    if (provider.equals(GoldenMasters.CI)) {
      return CiContract.TABLE.rows().stream().map(ContractRow::operationId).toList();
    }
    if (provider.equals(GoldenMasters.SYSTEM)) {
      return eu.wohlben.qits.workspaces.targethost.SystemContract.TABLE.rows().stream()
          .map(ContractRow::operationId)
          .toList();
    }
    return IdpContract.TABLE.rows().stream().map(ContractRow::operationId).toList();
  }

  private static boolean hasRow(ContractTable table, String operationId) {
    return table.rows().stream().anyMatch(r -> r.operationId().equals(operationId));
  }

  /** Every path param as {@code {}}, so a template is compared by shape. */
  private static String normalise(String route) {
    return route.replaceAll("\\{[^}]*}", "{}").replaceAll("/+$", "");
  }

  private static String path(jakarta.ws.rs.Path path) {
    if (path == null) {
      return "";
    }
    String value = path.value();
    return value.startsWith("/") || value.isEmpty() ? value : "/" + value;
  }

  private static String httpMethod(Method method) {
    for (var annotation : method.getAnnotations()) {
      HttpMethod verb = annotation.annotationType().getAnnotation(HttpMethod.class);
      if (verb != null) {
        return verb.value();
      }
    }
    return null;
  }

  private static boolean annotated(ClassModel model, Class<?> annotation) {
    String descriptor = "L" + annotation.getName().replace('.', '/') + ";";
    return model
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(
            a ->
                a.annotations().stream()
                    .anyMatch(x -> x.className().stringValue().equals(descriptor)))
        .orElse(false);
  }

  private static Class<?> load(ClassModel model) throws ClassNotFoundException {
    return Class.forName(
        model.thisClass().asInternalName().replace('/', '.'),
        false,
        OutboundCallsGuardTest.class.getClassLoader());
  }

  /** This module's compiled main classes: where {@link IdpClients} was loaded from. */
  private static Path mainClassesDir() {
    try {
      return Path.of(IdpClients.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<ClassModel> mainClasses() throws IOException {
    Path root = mainClassesDir();
    List<ClassModel> models = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
        models.add(ClassFile.of().parse(Files.readAllBytes(file)));
      }
    }
    assertTrue(models.size() > 50, "only " + models.size() + " classes under " + root);
    return models;
  }
}
