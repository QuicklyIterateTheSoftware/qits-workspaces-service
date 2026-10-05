package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.runner.protocol.Hello;
import eu.wohlben.qits.runner.protocol.RunnerCapabilities;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A scripted workspace runner on a real WebSocket: it dials {@link
 * WorkspacesRunnerProtocol#SOCKET_PATH} with a bearer, says what a test tells it to, and hands back
 * every frame the host sends, decoded by the same codecs the runner itself uses. qits-ci's {@code
 * FakeCiRunner} is the shape.
 */
final class FakeWorkspacesRunner implements AutoCloseable {

  static final Duration SOON = Duration.ofSeconds(10);

  private static final ObjectMapper JSON = new ObjectMapper();

  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<RunnerMessage> received = new LinkedBlockingQueue<>();
  private final CompletableFuture<String> closed = new CompletableFuture<>();

  private FakeWorkspacesRunner(WebSocketClient client, WebSocket socket) {
    this.client = client;
    this.socket = socket;
    socket.textMessageHandler(
        text -> {
          try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(text, Map.class);
            received.add(WorkspaceRunnerMessageCodec.CODEC.decode(map));
          } catch (Exception e) {
            throw new IllegalStateException("undecodable host frame: " + text, e);
          }
        });
    socket.closeHandler(
        ignored ->
            closed.complete(socket.closeStatusCode() + " " + nullToEmpty(socket.closeReason())));
  }

  /** Dial {@code endpoint} with {@code bearer}; throws when the upgrade is refused. */
  static FakeWorkspacesRunner connect(Vertx vertx, URI endpoint, String bearer) throws Exception {
    WebSocketClient client = vertx.createWebSocketClient();
    WebSocketConnectOptions options =
        new WebSocketConnectOptions()
            .setHost(endpoint.getHost())
            .setPort(endpoint.getPort())
            .setURI(endpoint.getPath());
    if (bearer != null) {
      options.addHeader("Authorization", "Bearer " + bearer);
    }
    try {
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      return new FakeWorkspacesRunner(client, socket);
    } catch (Exception refused) {
      client.close();
      throw refused;
    }
  }

  /** A {@code hello} in {@code version}, holding {@code held}. */
  static Hello hello(String version, List<String> held) {
    return new Hello(
        version,
        WorkspacesRunnerProtocol.CAPABILITY_VERSION,
        1,
        new RunnerCapabilities(true, "amd64", "linux", Map.of()),
        held,
        Map.of());
  }

  void send(RunnerMessage message) throws Exception {
    socket
        .writeTextMessage(JSON.writeValueAsString(WorkspaceRunnerMessageCodec.CODEC.encode(message)))
        .toCompletionStage()
        .toCompletableFuture()
        .get(5, TimeUnit.SECONDS);
  }

  /** The next frame, which must be a {@code type}. */
  <T extends RunnerMessage> T expect(Class<T> type) throws InterruptedException {
    RunnerMessage next = received.poll(SOON.toMillis(), TimeUnit.MILLISECONDS);
    assertNotNull(next, "no frame arrived; expected " + type.getSimpleName());
    if (!type.isInstance(next)) {
      fail("expected " + type.getSimpleName() + " and the host sent " + next);
    }
    return type.cast(next);
  }

  /** The first frame of {@code type}, skipping any other. */
  <T extends RunnerMessage> T await(Class<T> type) throws InterruptedException {
    long deadline = System.nanoTime() + SOON.toNanos();
    while (System.nanoTime() < deadline) {
      RunnerMessage next = received.poll(100, TimeUnit.MILLISECONDS);
      if (type.isInstance(next)) {
        return type.cast(next);
      }
    }
    return fail("no " + type.getSimpleName() + " arrived");
  }

  /** Whatever arrives within {@code quiet}, or null: an absence, asserted by waiting. */
  RunnerMessage poll(Duration quiet) throws InterruptedException {
    return received.poll(quiet.toMillis(), TimeUnit.MILLISECONDS);
  }

  void drain() {
    received.clear();
  }

  /** {@code "<status> <reason>"} once the host closed the socket. */
  String awaitClose() throws Exception {
    return closed.get(SOON.toMillis(), TimeUnit.MILLISECONDS);
  }

  boolean isClosed() {
    return closed.isDone();
  }

  @Override
  public void close() {
    try {
      if (!socket.isClosed()) {
        socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
      }
    } catch (Exception ignored) {
      // The host may already have closed it.
    } finally {
      client.close();
    }
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
