package eu.wohlben.qits.workspaces.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.FakeRepositoryLookup;
import eu.wohlben.qits.workspaces.control.TestOrigin;
import eu.wohlben.qits.workspaces.control.WorkspaceIds;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import jakarta.inject.Inject;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the workspace-daemon proxy against a real loopback origin: a Vert.x server plays the
 * in-container {@code WorkspaceApi} — echoing the path it was called on, the {@code Authorization}
 * it was given and the {@code Host} it saw — and {@code FakeContainerRuntime} resolves the target to
 * {@code 127.0.0.1} + the configured daemon port, so that port <em>is</em> the port the proxy
 * targets. No docker involved; the sibling of {@link ServiceProxyRouteTest} and deliberately the
 * same kind of test.
 *
 * <p>The port has to be fixed before the application starts, because unlike a service's web-view
 * port it comes from configuration rather than from a staged config document — see {@link
 * #PORT_PROPERTY} for how it gets there and why it is latched.
 */
@QuarkusTest
@TestProfile(ContainerProxyRouteTest.TestProfile.class)
public class ContainerProxyRouteTest {

  private static final String TOKEN = "test-daemon-token";

  /**
   * The port the fake daemon binds, chosen by the profile and handed to the test through a system
   * property.
   *
   * <p>It has to travel that way, and the <em>first caller wins</em>. Unlike a service's web-view
   * port, the daemon's comes from configuration, so it must be fixed before the application boots —
   * and {@link QuarkusTestProfile} is instantiated in more than one classloader, so
   * {@code getConfigOverrides()} runs more than once. A plain static initializer picks a different
   * port each time; an unconditional {@code setProperty} lets the later call overwrite the value the
   * application was actually configured with. Either way the proxy targets a port nothing is
   * listening on, and the symptom is every proxying assertion failing with a bare, bodyless 502 that
   * says nothing about why. Latching the first value is what makes both halves agree.
   */
  private static final String PORT_PROPERTY = "qits.test.container-proxy.daemon-port";

  private static synchronized int latchedPort() {
    String existing = System.getProperty(PORT_PROPERTY);
    if (existing != null) {
      return Integer.parseInt(existing);
    }
    try (ServerSocket socket = new ServerSocket(0)) {
      int port = socket.getLocalPort();
      System.setProperty(PORT_PROPERTY, String.valueOf(port));
      return port;
    } catch (Exception e) {
      throw new IllegalStateException("no free port for the fake daemon", e);
    }
  }

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-container-proxy-test-repos");
        return Map.of(
            "qits.test.origins-dir", tempDir.toString(),
            "qits.workspace.daemon-api-port", String.valueOf(latchedPort()),
            "qits.workspace.daemon-api-token", TOKEN);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private int daemonPort() {
    return latchedPort();
  }

  @Inject FakeRepositoryLookup repositories;

  @Inject WorkspaceIds workspaceIds;

  @Inject WorkspaceService workspaceService;

  @ConfigProperty(name = "qits.test.origins-dir")
  String dataDir;

  private Vertx daemonVertx;
  private HttpServer daemonServer;
  private final AtomicInteger daemonHits = new AtomicInteger();
  private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
  private final AtomicReference<String> lastHost = new AtomicReference<>();

  /** The text frame that asks the fake daemon to write N 64 KiB binary frames as fast as it can. */
  private static final String FLOOD = "flood:";

  private static final int FLOOD_FRAME_BYTES = 64 * 1024;

  /**
   * Completed when the fake daemon has handed every flood frame to its socket. Under a working
   * pipe this cannot happen while the browser is not reading: the bytes have nowhere to go, so the
   * write queue stays full and the daemon stays parked on its drain handler.
   */
  private final AtomicReference<CompletableFuture<Void>> floodWritten =
      new AtomicReference<>(new CompletableFuture<>());

  /**
   * Hand {@code remaining} frames to the socket, stopping whenever its write queue is full and
   * resuming on drain. That is what a well-behaved producer does — and it is what makes this test
   * about the proxy rather than about the stub: a producer that ignored its own queue would balloon
   * the stub's heap instead of the one under test.
   */
  private void flood(io.vertx.core.http.ServerWebSocket ws, int remaining) {
    io.vertx.core.buffer.Buffer frame =
        io.vertx.core.buffer.Buffer.buffer(new byte[FLOOD_FRAME_BYTES]);
    int left = remaining;
    while (left > 0 && !ws.writeQueueFull()) {
      ws.writeBinaryMessage(frame);
      left--;
    }
    if (left == 0) {
      floodWritten.get().complete(null);
      return;
    }
    int rest = left;
    ws.drainHandler(
        drained -> {
          ws.drainHandler(null);
          flood(ws, rest);
        });
  }

  @BeforeEach
  void startFakeDaemon() throws Exception {
    floodWritten.set(new CompletableFuture<>());
    daemonVertx = Vertx.vertx();
    daemonServer =
        daemonVertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  daemonHits.incrementAndGet();
                  lastAuthorization.set(req.getHeader("Authorization"));
                  lastHost.set(req.getHeader("Host"));
                  req.response().end("daemon:" + req.uri());
                })
            // The real daemon authenticates the HANDSHAKE with the same bearer it checks on every
            // request, so this stub must too. It used to accept any upgrade, and that is precisely
            // what hid a live bug: vertx-http-proxy skips its interceptor chain on an upgrade, so
            // the bearer never reached the daemon and both interactive sockets were answered 401
            // in production while this test stayed green.
            .webSocketHandshakeHandler(
                handshake -> {
                  if (!("Bearer " + TOKEN).equals(handshake.headers().get("Authorization"))) {
                    handshake.reject(401);
                  } else if (handshake.path().endsWith("/gone")) {
                    // The real daemon refuses an upgrade for a command that is not running. The
                    // refusal is the answer, not a transport failure, so it has to reach the client.
                    handshake.reject(404);
                  } else {
                    handshake.accept();
                  }
                })
            .webSocketHandler(
                ws ->
                    ws.textMessageHandler(
                        msg -> {
                          if (msg.startsWith(FLOOD)) {
                            flood(ws, Integer.parseInt(msg.substring(FLOOD.length())));
                          } else {
                            ws.writeTextMessage("ws-daemon:" + ws.path() + ":" + msg);
                          }
                        }))
            .listen(daemonPort(), "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(10, TimeUnit.SECONDS);
    daemonHits.set(0);
  }

  /**
   * <b>Waits for the close, and that is the whole point.</b> {@code Vertx.close()} is asynchronous:
   * firing it and returning left the previous method's server still holding {@link #daemonPort()}
   * while the next {@code @BeforeEach} tried to bind it. With nine test methods cycling one latched
   * port, that is eight chances per run to lose the race — and losing it fails the whole class with
   * {@code java.net.BindException: Address in use} at {@code startFakeDaemon}, which reads like a
   * machine with something else on the port rather than like this fixture racing itself.
   *
   * <p>It cost two release cycles on 2026-09-06/07 before it was chased down. Note the asymmetry it
   * came from: the start already waits — {@code listen(...).get(10, SECONDS)} — so only the stop was
   * fire-and-forget. Keep the two symmetrical; an await here is what makes the port free by the time
   * the next bind asks for it, rather than usually free.
   */
  @AfterEach
  void stopFakeDaemon() throws Exception {
    if (daemonVertx != null) {
      daemonVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
      daemonVertx = null;
      daemonServer = null;
    }
  }

  /** A repository with one workspace whose (fake) container is provisioned and running. */
  private Long workspaceWithContainer() throws Exception {
    String repoId = TestOrigin.create(dataDir);
    repositories.register(repoId);
    workspaceService.createMainWorkspace(repoId, "master");
    workspaceService.createWorkspace(repoId, "work", "master", "work");
    Long id = workspaceIds.of(repoId, "work");
    workspaceService.ensureContainer(id);
    return id;
  }

  @Test
  public void forwardsTheDaemonsPathVerbatimWithQitsOwnBearer() throws Exception {
    Long id = workspaceWithContainer();
    String base = "/workspaces/container/" + id;

    given()
        .get(base + "/files/content?path=README")
        .then()
        .statusCode(200)
        // The daemon carries no {repoId}/{workspaceId} prefix and the proxy strips none of its own:
        // The WHOLE path reaches the daemon, prefix included — this route rewrites nothing, and the
        // daemon is configured with that prefix as its base (QITS_WORKSPACE_DAEMON_API_BASE_PATH,
        // injected by WorkspaceContainerFactory). If this assertion ever looks wrong, the fix is on
        // the daemon's side of the contract, not a substring here; ContainerProxyRoute's javadoc
        // says why. Everything after the workspace id is the daemon's path, untouched, query
        // included. (The
        // query value is slash-free on purpose — RestAssured percent-encodes a slash inside one, and
        // the resulting mismatch would be the client's encoding, not the proxy's forwarding.)
        .body(containsString("daemon:" + base + "/files/content?path=README"));

    assertEquals(
        "Bearer " + TOKEN,
        lastAuthorization.get(),
        "qits presents its own credential — the daemon's bearer is peer authentication");
  }

  @Test
  public void aCallerSuppliedAuthorizationIsReplacedRatherThanForwarded() throws Exception {
    Long id = workspaceWithContainer();

    given()
        .header("Authorization", "Bearer smuggled-in-by-the-caller")
        .get("/workspaces/container/" + id + "/detection")
        .then()
        .statusCode(200);

    // Forwarding it would be both meaningless (the daemon has no user identity to check) and a way
    // to move a credential into a container that runs an untrusted checkout.
    assertEquals("Bearer " + TOKEN, lastAuthorization.get());
  }

  @Test
  public void theDaemonSeesAPinnedAuthority() throws Exception {
    Long id = workspaceWithContainer();

    given().get("/workspaces/container/" + id + "/detection").then().statusCode(200);

    // Not qits' own Host and not the container's DNS name: a constant, so the daemon's view of who
    // called it does not change when the origin does.
    assertEquals("localhost:" + daemonPort(), lastHost.get());
  }

  @Test
  public void aWebSocketUpgradeRidesAlong() throws Exception {
    Long id = workspaceWithContainer();
    String path = "/workspaces/container/" + id + "/terminal/commands/abc123";

    CompletableFuture<String> reply = new CompletableFuture<>();
    WebSocket ws =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://127.0.0.1:" + RestAssured.port + path),
                new WebSocket.Listener() {
                  @Override
                  public CompletionStage<?> onText(
                      WebSocket webSocket, CharSequence data, boolean last) {
                    reply.complete(data.toString());
                    return null;
                  }
                })
            .get(10, TimeUnit.SECONDS);
    ws.sendText("{\"type\":\"data\",\"data\":\"x\"}", true);

    // This is what carries WS /terminal/commands/{id} and WS /chat/commands/{id}; neither end knows
    // it is proxied, and the daemon's path arrives unstripped.
    assertEquals(
        "ws-daemon:" + path + ":{\"type\":\"data\",\"data\":\"x\"}", reply.get(10, TimeUnit.SECONDS));
    ws.abort();
  }

  /**
   * The one test that is about the fix rather than about the feature.
   *
   * <p>{@code vertx-http-proxy} proxies an upgrade with three bare {@code a.handler(b::write)}
   * installs — no {@code writeQueueFull}, no {@code pause}, no {@code drainHandler} — so a producer
   * in the container writes as fast as it likes and the bytes a browser has not read yet accumulate
   * on <em>this</em> process's heap. A chatty dev server on a terminal socket is exactly that
   * producer.
   *
   * <p>So: park the browser (a paused socket stops reading, and its TCP window closes behind it) and
   * ask the daemon for 32 MiB. With a bounded pipe the daemon simply cannot get rid of it — the
   * proxy stops reading, its queue stays full, and the write is still unfinished a second and a half
   * later. Resume the browser and the whole thing arrives, byte for byte, which is the other half of
   * the claim: the bound must not lose or reorder anything.
   *
   * <p>Without the fix the flood completes while the browser is still parked, because the proxy
   * swallowed all 32 MiB. That is the assertion.
   */
  @Test
  public void aParkedBrowserStopsTheDaemonRatherThanFillingThisProcessesHeap() throws Exception {
    Long id = workspaceWithContainer();
    int frames = 512;
    long expectedBytes = (long) frames * FLOOD_FRAME_BYTES;

    io.vertx.core.http.WebSocketClient client = daemonVertx.createWebSocketClient();
    try {
      io.vertx.core.http.WebSocket browser =
          client
              .connect(
                  RestAssured.port,
                  "127.0.0.1",
                  "/workspaces/container/" + id + "/terminal/commands/flooded")
              .toCompletionStage()
              .toCompletableFuture()
              .get(10, TimeUnit.SECONDS);

      CompletableFuture<Void> allReceived = new CompletableFuture<>();
      java.util.concurrent.atomic.AtomicLong received = new java.util.concurrent.atomic.AtomicLong();
      browser.binaryMessageHandler(
          buffer -> {
            if (received.addAndGet(buffer.length()) >= expectedBytes) {
              allReceived.complete(null);
            }
          });

      // Stop reading before asking for anything, so the daemon's very first frames have nowhere to
      // go and the whole chain is under pressure from the start.
      browser.pause();
      browser.writeTextMessage(FLOOD + frames);

      try {
        floodWritten.get().get(1500, TimeUnit.MILLISECONDS);
        throw new AssertionError(
            "the daemon handed over all "
                + expectedBytes
                + " bytes while the browser was not reading — the proxy is buffering them");
      } catch (java.util.concurrent.TimeoutException expected) {
        // Good: the daemon is parked on its drain handler because this hop stopped reading.
      }

      browser.resume();
      floodWritten.get().get(30, TimeUnit.SECONDS);
      allReceived.get(30, TimeUnit.SECONDS);
      assertEquals(expectedBytes, received.get(), "every byte, in order, once the reader comes back");
    } finally {
      client.close();
    }
  }

  /**
   * A refusal is an answer. The daemon rejects an upgrade for a command that is no longer running,
   * and the client needs that status to say "this run is over" rather than "something broke" — so
   * the handshake response is forwarded rather than flattened into a 502.
   */
  @Test
  public void aRefusedHandshakeReachesTheClientWithTheDaemonsOwnStatus() throws Exception {
    Long id = workspaceWithContainer();

    io.vertx.core.http.WebSocketClient client = daemonVertx.createWebSocketClient();
    try {
      Exception refusal =
          org.junit.jupiter.api.Assertions.assertThrows(
              Exception.class,
              () ->
                  client
                      .connect(
                          RestAssured.port,
                          "127.0.0.1",
                          "/workspaces/container/" + id + "/terminal/commands/gone")
                      .toCompletionStage()
                      .toCompletableFuture()
                      .get(10, TimeUnit.SECONDS));

      // Not a hang and not a 502: the daemon's own 404 survives the hop.
      org.junit.jupiter.api.Assertions.assertTrue(
          String.valueOf(refusal.getCause()).contains("404"),
          "expected the daemon's 404 to reach the client, got: " + refusal.getCause());
    } finally {
      client.close();
    }
  }

  @Test
  public void unknownSoftDeletedAndNonNumericIdsAllAnswerTheSame404() throws Exception {
    int hitsBefore = daemonHits.get();

    // Never existed.
    given().get("/workspaces/container/999999/files").then().statusCode(404);
    // Not an id at all.
    given().get("/workspaces/container/not-a-number/files").then().statusCode(404);
    // No id.
    given().get("/workspaces/container/").then().statusCode(404);
    // The bare prefix: `route(PREFIX + "*")` matches it too, one character short of the prefix, and
    // the segment parse used to overflow into a 500 there.
    given().get("/workspaces/container").then().statusCode(404);

    // Soft-deleted: the row lingers, and it must answer exactly as an unknown one does.
    Long id = workspaceWithContainer();
    workspaceService.deleteContainer(id);
    workspaceService.discardWorkspace(id);
    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        .statusCode(404)
        .body(containsString("No workspace here."));

    assertEquals(hitsBefore, daemonHits.get(), "none of these may reach a container");
  }

  @Test
  public void aStoppedContainerIsADistinctAnswerFromAMissingWorkspace() throws Exception {
    Long id = workspaceWithContainer();
    workspaceService.stopContainer(id);

    int hitsBefore = daemonHits.get();
    given()
        .get("/workspaces/container/" + id + "/files")
        .then()
        .statusCode(502)
        // A naive proxy reports every one of these as one indistinguishable connection error, and
        // then every daemon problem looks like the same problem.
        .body(containsString("not running"));
    assertEquals(hitsBefore, daemonHits.get(), "a stopped container must not be forwarded to");
  }
}
