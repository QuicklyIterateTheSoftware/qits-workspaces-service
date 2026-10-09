package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import eu.wohlben.qits.workspaces.control.FakeContainerRuntime;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * A {@link WorkspaceTunnels} whose API tunnel for a row is a stub daemon on loopback, for the tests
 * that drive a daemon's HTTP API without a daemon dialling back.
 *
 * <p>The tunnel is the only way to a daemon since qits-780 deleted the direct {@code
 * container:13338} fallback, so a test that used to point that fallback at a stub HTTP server now
 * points the tunnel there instead: {@link #originFor} answers the stub's port for a row {@code
 * connected} admits, exactly the shape {@code WorkspaceTunnels} answers for a row whose daemon dialled
 * back. Every caller then dials {@code 127.0.0.1:<port>} with this client, as it would a real
 * tunnel's. Only the API target is answered; the editor target stays unreachable.
 *
 * <p>Install it per test ({@link QuarkusMock} mocks are reset after each), and {@link #close} it in
 * the matching {@code @AfterEach}. It is not a bean: nothing is injected into it, and only the
 * {@code originFor} overloads are meant to be called on it.
 */
public class StubDaemonTunnels extends WorkspaceTunnels {

  private final HttpClient client;

  private final int port;

  private final LongPredicate connected;

  private StubDaemonTunnels(Vertx vertx, int port, LongPredicate connected) {
    this.client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    this.port = port;
    this.connected = connected;
  }

  /**
   * Installs a stub whose tunnel for a row is {@code 127.0.0.1:port} while {@code connected} says
   * the row's daemon is there.
   */
  public static StubDaemonTunnels install(Vertx vertx, int port, LongPredicate connected) {
    StubDaemonTunnels tunnels = new StubDaemonTunnels(vertx, port, connected);
    QuarkusMock.installMockForType(tunnels, WorkspaceTunnels.class);
    return tunnels;
  }

  /**
   * "The daemon is there while its container runs": a row is connected exactly while {@code
   * containers} has its (fake) container running, which is when a real daemon would have dialled
   * back.
   */
  public static LongPredicate whileItsContainerRuns(
      FakeContainerRuntime containers, WorkspaceRepository rows) {
    return id ->
        QuarkusTransaction.requiringNew()
            .call(() -> rows.findActiveById(id))
            .map(w -> containers.isRunning(containers.containerName(w.workspaceId, w.repositoryId)))
            .orElse(false);
  }

  @Override
  public Optional<TunnelOrigin> originFor(Long workspaceRowId, StreamTarget target) {
    if (workspaceRowId == null
        || (target != null && target != StreamTarget.API)
        || !connected.test(workspaceRowId)) {
      return Optional.empty();
    }
    return Optional.of(new TunnelOrigin(client, port));
  }

  public void close() {
    client.close();
  }
}
