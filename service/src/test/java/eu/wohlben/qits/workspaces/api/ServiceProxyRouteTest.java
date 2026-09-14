package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceConfigReader;
import eu.wohlben.qits.workspaces.control.FakeWorkspaceServiceDriver;
import eu.wohlben.qits.workspaces.control.QitsConfig;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.control.ServiceSupervisor;
import eu.wohlben.qits.workspaces.control.RestartPolicy;
import eu.wohlben.qits.workspaces.entity.ServiceStatus;
import io.quarkus.test.junit.QuarkusTest;
import eu.wohlben.qits.archrules.NecessaryTestProfileDuplication;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Exercises the service web-view proxy against a real loopback origin: a Vert.x echo server plays
 * the daemon's dev server (the {@code FakeContainerRuntime} resolves the target to {@code
 * 127.0.0.1} + the service's {@code webView.port}, so that port <em>is</em> the host port the proxy
 * targets). Verifies the base-path contract (paths forwarded verbatim, unstripped), the lifecycle
 * responses (splash/502/404), the trailing-slash redirect, the WebSocket round-trip (the HMR path),
 * and that unknown keys never reach the origin.
 *
 * <p>The host {@code ServiceSupervisor} is a pure projection, so a profile-scoped {@link
 * FakeWorkspaceServiceDriver} (enabled only for this test — the daemon ITs keep the real registry)
 * plays the daemon: after {@code start} the test feeds a READY (or STOPPED) event through the sink
 * the supervisor subscribed, which resolves the proxy origin.
 */
@QuarkusTest
@TestProfile(ServiceProxyRouteTest.TestProfile.class)
public class ServiceProxyRouteTest {

  /**
   * <b>The alternative IS the profile.</b> Once the throwaway {@code qits.test.origins-dir} went —
   * it bought nothing over {@code TestOrigin}'s per-origin UUID — there is no config left here at
   * all: what this profile does is swap {@link FakeWorkspaceServiceDriver} in for the real {@code
   * WorkspaceDaemonRegistry}, and a {@code getEnabledAlternatives} is not an overlay a co-tenant can
   * ignore. Any class that shared it would silently lose the real registry, which is the collaborator
   * the other service tests and the daemon ITs are about. That is why this stays its own application
   * even though its config map is empty.
   */
  public static class TestProfile implements QuarkusTestProfile, NecessaryTestProfileDuplication {
    @Override
    public java.util.Set<Class<?>> getEnabledAlternatives() {
      // Opt this test into the fake daemon driver without disturbing the real
      // WorkspaceDaemonRegistry that the other service tests and daemon ITs rely on.
      return java.util.Set.of(FakeWorkspaceServiceDriver.class);
    }
  }

  private static final long AWAIT_MILLIS = 15_000;

  @Inject FakeRepositoryLookup repositories;

  @Inject eu.wohlben.qits.workspaces.control.WorkspaceIds workspaceIds;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;
  @Inject WorkspaceService workspaceService;

  @Inject FakeWorkspaceConfigReader configReader;

  @Inject FakeWorkspaceServiceDriver driver;

  @Inject ServiceSupervisor supervisor;

  private Vertx echoVertx;
  private HttpServer echoServer;
  private final AtomicInteger echoHits = new AtomicInteger();
  // Static: JUnit instantiates the class per test method, so an instance counter would reset and
  // every test would stage the same id (the proxy keys instances by (workspaceId, serviceId)
  // alone).
  private static final AtomicInteger serviceSeq = new AtomicInteger();
  private final java.util.concurrent.atomic.AtomicReference<String> lastHostHeader =
      new java.util.concurrent.atomic.AtomicReference<>();

  /** The config-declared service name the daemon reports events under (the id varies per setup). */
  private static final String SERVICE_NAME = "echo-daemon";

  @BeforeEach
  void resetStagedConfig() {
    configReader.clear();
    driver.reset();
  }

  @BeforeEach
  void startEchoServer() throws Exception {
    echoVertx = Vertx.vertx();
    echoServer =
        echoVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  echoHits.incrementAndGet();
                  lastHostHeader.set(req.getHeader("Host"));
                  req.response().end("echo:" + req.uri());
                })
            .webSocketHandler(
                ws ->
                    ws.textMessageHandler(
                        msg -> ws.writeTextMessage("ws-echo:" + ws.path() + ":" + msg)))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    echoHits.set(0);
  }

  @AfterEach
  void stopEchoServer() {
    if (echoVertx != null) {
      echoVertx.close();
    }
  }

  /**
   * Stage a web-viewable service, provision its (fake) container, and register the projection by
   * starting it (leaving it STARTING — the caller decides whether to drive it READY). Config is
   * staged before the container is provisioned, so the supervisor resolves the definition from the
   * in-container config when the start registers it.
   */
  private Setup startService(String basePath) throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    // Unique per setup: the proxy/supervisor key instances by (workspaceId, serviceId) alone, so a
    // fixed id would collide with a previous test's stopped instance ("work" repeats across repos).
    String serviceId = SERVICE_NAME + "-" + serviceSeq.incrementAndGet();
    // The workspace exists before its config is staged, because the config is keyed by the
    // workspace's id and there is no id until the row is written. Creation writes only the row (the
    // container is provisioned below), so nothing reads the config in between.
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    configReader.setConfig(
        workspaceIds.of(repoId, "work"),
        new QitsConfig(
            null,
            null,
            null,
            List.of(
                new QitsConfig.ServiceDecl(
                    serviceId,
                    SERVICE_NAME,
                    null,
                    "sleep 300",
                    null,
                    Boolean.FALSE, // autoStart off: the test starts manually
                    RestartPolicy.NEVER,
                    0,
                    "TERM",
                    null, // environment
                    new QitsConfig.WebViewDecl(echoServer.actualPort(), null, basePath),
                    null)), // healthChecks
            null));
    // The proxy origin resolves against a real (fake) container; provision it before READY.
    workspaceService.ensureContainer(workspaceIds.of(repoId, "work"));
    supervisor.start(workspaceIds.of(repoId, "work"), serviceId);
    return new Setup(repoId, serviceId);
  }

  /** Bring a started service READY by playing the service event the supervisor projects. */
  private Setup setUpReadyService(String basePath) throws Exception {
    Setup setup = startService(basePath);
    driver.sink().onState(
        setup.repoId(), "work", workspaceIds.of(setup.repoId(), "work"), SERVICE_NAME, "READY", null);
    awaitStatus(setup, ServiceStatus.READY);
    return setup;
  }

  private record Setup(String repoId, String serviceId) {}

  private void awaitStatus(Setup setup, ServiceStatus expected) throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    ServiceStatus last = null;
    while (System.currentTimeMillis() < deadline) {
      last =
          supervisor.effectiveServices(workspaceIds.of(setup.repoId(), "work")).stream()
              .filter(i -> i.definition().id().equals(setup.serviceId()))
              .findFirst()
              .map(i -> i.status())
              .orElse(null);
      if (last == expected) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for " + expected + "; last: " + last);
  }

  private void stopQuietly(Setup setup) {
    try {
      supervisor.stop(workspaceIds.of(setup.repoId(), "work"), setup.serviceId());
      // The daemon owns the process — it reports STOPPED, which the projection settles.
      driver.sink().onState(
        setup.repoId(), "work", workspaceIds.of(setup.repoId(), "work"), SERVICE_NAME, "STOPPED", 0);
      awaitStatus(setup, ServiceStatus.STOPPED);
    } catch (Exception ignored) {
      // already stopped or never live
    }
  }

  @Test
  public void forwardsVerbatimRedirectsBareKeyAndRefusesAfterStop() throws Exception {
    Setup setup = setUpReadyService(null);
    try {
      String base = "/workspaces/service/" + workspaceIds.of(setup.repoId(), "work") + "/" + setup.serviceId();

      // Verbatim passthrough: the origin sees the unstripped path and query.
      given()
          .get(base + "/some/nested/path?q=1")
          .then()
          .statusCode(200)
          .body(containsString("echo:" + base + "/some/nested/path?q=1"));

      // Bare key 302s to the trailing-slash form so relative URLs resolve inside the frame.
      given()
          .redirects()
          .follow(false)
          .get(base)
          .then()
          .statusCode(302)
          .header("Location", base + "/");

      // WebSocket upgrade (the HMR path) round-trips through the proxy, path unstripped.
      CompletableFuture<String> reply = new CompletableFuture<>();
      WebSocket ws =
          HttpClient.newHttpClient()
              .newWebSocketBuilder()
              .buildAsync(
                  URI.create("ws://127.0.0.1:" + RestAssured.port + base + "/hmr"),
                  new WebSocket.Listener() {
                    @Override
                    public CompletionStage<?> onText(
                        WebSocket webSocket, CharSequence data, boolean last) {
                      reply.complete(data.toString());
                      return null;
                    }
                  })
              .get(10, TimeUnit.SECONDS);
      ws.sendText("ping", true);
      assertEquals("ws-echo:" + base + "/hmr:ping", reply.get(10, TimeUnit.SECONDS));
      ws.abort();
    } finally {
      stopQuietly(setup);
    }

    // Stopped: the instance still resolves, but the proxy answers 502 instead of forwarding.
    int hitsBefore = echoHits.get();
    given()
        .get("/workspaces/service/" + workspaceIds.of(setup.repoId(), "work") + "/" + setup.serviceId() + "/")
        .then()
        .statusCode(502)
        .body(containsString("not running"));
    assertEquals(hitsBefore, echoHits.get(), "a stopped daemon must not be forwarded to");
  }

  @Test
  public void rewritesHostHeaderToLocalhostSoTheDevServerAllowsIt() throws Exception {
    // Regression for the devcontainer move: qits now reaches containers by DNS name, so the proxy
    // must present the origin's Host as `localhost` (always allow-listed by Angular's dev server)
    // instead of the container's DNS name (rejected with "This host is not allowed"). TCP still
    // targets the fixed origin; only the Host/:authority header is rewritten.
    Setup setup = setUpReadyService(null);
    try {
      given()
          .get("/workspaces/service/" + workspaceIds.of(setup.repoId(), "work") + "/" + setup.serviceId() + "/index.html")
          .then()
          .statusCode(200)
          .body(containsString("echo:"));
      assertEquals(
          "localhost:" + echoServer.actualPort(),
          lastHostHeader.get(),
          "the origin must see a localhost Host, not qits' own or the container's DNS name");
    } finally {
      stopQuietly(setup);
    }
  }

  @Test
  public void basePathPrefixedRequestsForwardVerbatim() throws Exception {
    // A service with a webView.basePath serves under /service/{w}/{d}/app/ — the proxy stays a dumb
    // passthrough; the extra sub-path is part of the verbatim-forwarded path, never stripped.
    Setup setup = setUpReadyService("app");
    try {
      String servedBase = "/workspaces/service/" + workspaceIds.of(setup.repoId(), "work") + "/" + setup.serviceId() + "/app";
      given()
          .get(servedBase + "/main.js")
          .then()
          .statusCode(200)
          .body(containsString("echo:" + servedBase + "/main.js"));
    } finally {
      stopQuietly(setup);
    }
  }

  @Test
  public void unknownKeysAnswer404WithoutTouchingAnyOrigin() {
    int hitsBefore = echoHits.get();
    given().get("/workspaces/service/no-such-workspace/no-such-daemon/index.html").then().statusCode(404);
    given().get("/workspaces/service/onlyonesegment").then().statusCode(404);
    given().get("/workspaces/service/").then().statusCode(404);
    // The bare prefix: `route(PREFIX + "*")` matches it too, one character short of the prefix, and
    // the segment parse used to overflow into a 500 there.
    given().get("/workspaces/service").then().statusCode(404);
    assertEquals(hitsBefore, echoHits.get(), "unknown keys must never reach an origin");
  }

  @Test
  public void startingServiceGetsTheAutoRefreshingSplash() throws Exception {
    // A service that never reports READY stays in STARTING (the daemon isn't played to READY here).
    Setup setup = startService(null);
    try {
      String body =
          given()
              .get("/workspaces/service/" + workspaceIds.of(setup.repoId(), "work") + "/" + setup.serviceId() + "/")
              .then()
              .statusCode(200)
              .extract()
              .asString();
      assertTrue(body.contains("http-equiv=\"refresh\""), "splash must auto-refresh: " + body);
      assertTrue(body.contains("starting"), "splash names the state: " + body);
    } finally {
      stopQuietly(setup);
    }
  }
}
