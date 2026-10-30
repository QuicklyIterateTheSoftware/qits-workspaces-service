package eu.wohlben.qits.workspaces.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The upgrade handoff with a late browser (qits-1762), over real loopback sockets and no Quarkus.
 *
 * <p>The race this guards only opens under CPU contention, so a test that merely races sockets
 * passes against the bug. Here the browser's take-over is completed 500 ms late on purpose, which
 * puts every event the daemon sends in the window between its 101 and the pipe — every time, on an
 * idle machine. The daemon socket is the real thing ({@code HttpClientResponse.netSocket()} after a
 * real 101 from a raw fake daemon), because its close bypassing {@code pause()} is the subject; the
 * browser is a plain loopback {@code NetSocket} pair, since the pipe is raw bytes either way.
 */
class UpgradePipeTest {

  private static final long TIMEOUT_SECONDS = 5;

  private static final String SWITCHING =
      "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: raw\r\n\r\n";

  private Vertx vertx;

  @BeforeEach
  void aVertx() {
    vertx = Vertx.vertx();
  }

  @AfterEach
  void closeIt() throws Exception {
    vertx.close().toCompletionStage().toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  @Test
  void aDaemonThatClosesBeforeThePipeClosesTheBrowserAfterItsBytes() throws Exception {
    CompletableFuture<Void> daemonClosed = new CompletableFuture<>();
    int daemonPort =
        fakeDaemon(
            daemon ->
                // Apart from the 101, so the bytes ride the stream rather than the head's read.
                vertx.setTimer(
                    100,
                    t ->
                        daemon
                            .write("before-close")
                            .compose(v -> daemon.close())
                            .onComplete(v -> daemonClosed.complete(null))));
    Browser browser = browser();
    Promise<NetSocket> takeOver = Promise.promise();

    upgrade(daemonPort, browser.context, takeOver);
    daemonClosed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    // Long past the close on an idle loop: the pipe is late every time, not only under load.
    long completed = completeLate(browser, takeOver);

    String seen = browser.closed.get(2_000, TimeUnit.MILLISECONDS);
    long closedAfter = System.currentTimeMillis() - completed;
    assertEquals("before-close", seen, "the daemon's bytes reach the browser before its close");
    assertTrue(closedAfter < 2_000, "the browser closed " + closedAfter + " ms after the pipe");
  }

  @Test
  void aLateBrowserStillPipesBothWaysWhileTheDaemonStaysOpen() throws Exception {
    CompletableFuture<Void> daemonSawTheClose = new CompletableFuture<>();
    int daemonPort =
        fakeDaemon(
            daemon -> {
              daemon.handler(buffer -> daemon.write("echo:" + buffer));
              daemon.closeHandler(v -> daemonSawTheClose.complete(null));
              vertx.setTimer(100, t -> daemon.write("early;"));
            });
    Browser browser = browser();
    Promise<NetSocket> takeOver = Promise.promise();

    upgrade(daemonPort, browser.context, takeOver);
    Thread.sleep(300);
    completeLate(browser, takeOver);

    browser.awaitReceived("early;");
    browser.peer.write("ping");
    browser.awaitReceived("early;echo:ping");
    browser.peer.close();
    daemonSawTheClose.get(2, TimeUnit.SECONDS);
  }

  /** A raw daemon: answers any request head with a 101, then hands the socket to {@code then}. */
  private int fakeDaemon(Consumer<NetSocket> then) throws Exception {
    NetServer server =
        vertx.createNetServer()
            .connectHandler(
                socket -> {
                  StringBuilder head = new StringBuilder();
                  socket.handler(
                      buffer -> {
                        head.append(buffer.toString(StandardCharsets.ISO_8859_1));
                        if (head.indexOf("\r\n\r\n") >= 0) {
                          socket.handler(null);
                          socket.write(SWITCHING).onSuccess(v -> then.accept(socket));
                        }
                      });
                });
    return server
        .listen(0, "127.0.0.1")
        .toCompletionStage()
        .toCompletableFuture()
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .actualPort();
  }

  /** Open the upgrade the way the route does and hand the daemon's socket to the handoff. */
  private void upgrade(int daemonPort, Context browserContext, Promise<NetSocket> takeOver)
      throws Exception {
    HttpClient client = vertx.createHttpClient();
    CompletableFuture<Void> married = new CompletableFuture<>();
    client
        .request(
            new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setHost("127.0.0.1")
                .setPort(daemonPort)
                .setURI("/terminal")
                .putHeader("Connection", "Upgrade")
                .putHeader("Upgrade", "raw"))
        .compose(request -> request.connect())
        .onSuccess(
            (HttpClientResponse response) -> {
              assertEquals(101, response.statusCode());
              UpgradePipe.marry(response.netSocket(), browserContext, takeOver::future);
              married.complete(null);
            })
        .onFailure(married::completeExceptionally);
    married.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** Complete the take-over on the browser socket's own loop, as {@code toNetSocket()} does. */
  private static long completeLate(Browser browser, Promise<NetSocket> takeOver) throws Exception {
    Thread.sleep(500);
    long now = System.currentTimeMillis();
    browser.context.runOnContext(v -> takeOver.complete(browser.socket));
    return now;
  }

  /**
   * The browser: a loopback pair whose server end stands in for the taken-over inbound socket.
   * The peer records everything it receives, and what it had received when it saw the close.
   */
  private Browser browser() throws Exception {
    CompletableFuture<NetSocket> serverEnd = new CompletableFuture<>();
    CompletableFuture<Context> serverContext = new CompletableFuture<>();
    int port =
        vertx.createNetServer()
            .connectHandler(
                socket -> {
                  serverContext.complete(Vertx.currentContext());
                  serverEnd.complete(socket);
                })
            .listen(0, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .actualPort();
    NetSocket peer =
        vertx.createNetClient()
            .connect(port, "127.0.0.1")
            .toCompletionStage()
            .toCompletableFuture()
            .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    Browser browser =
        new Browser(
            serverEnd.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            serverContext.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            peer);
    peer.handler(
        buffer -> browser.received.getAndUpdate(seen -> seen.copy().appendBuffer(buffer)));
    peer.closeHandler(v -> browser.closed.complete(browser.received.get().toString()));
    return browser;
  }

  private static final class Browser {
    final NetSocket socket;
    final Context context;
    final NetSocket peer;
    final AtomicReference<Buffer> received = new AtomicReference<>(Buffer.buffer());
    final CompletableFuture<String> closed = new CompletableFuture<>();

    Browser(NetSocket socket, Context context, NetSocket peer) {
      this.socket = socket;
      this.context = context;
      this.peer = peer;
    }

    void awaitReceived(String expected) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 2_000;
      while (!expected.equals(received.get().toString())
          && System.currentTimeMillis() < deadline) {
        Thread.sleep(10);
      }
      assertEquals(expected, received.get().toString());
    }
  }
}
