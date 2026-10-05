package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspacedaemon.protocol.StreamTarget;
import io.vertx.core.http.HttpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link WorkspaceTunnels} whose answer for an ARMED workspace is staged by the test: the
 * capability its daemon announced (or none connected) and the loopback port its SERVICE tunnel
 * enters at. Every other workspace gets the real tunnels, so a test that arms nothing sees the
 * application unchanged.
 *
 * <p>The stub stands in for the daemon and its dial-back, not for the proxy: the port it hands out
 * is a real loopback server the test runs, so what is proved is that {@code ServiceProxyRoute}
 * takes a RUNNER row's origin from here — port and client both — and never from the supervisor's
 * container origin. The tunnel's own byte pipe is {@code EditorTunnelRouteTest}'s and {@code
 * DaemonStreamRouteTest}'s to prove. A profile-scoped {@link Alternative}, opted into with {@code
 * getEnabledAlternatives()}. Deliberately WITHOUT a {@code @Priority}: one would select it for the
 * whole application, and only {@code quarkus.arc.selected-alternatives} — which is what a profile's
 * {@code getEnabledAlternatives()} sets — keeps it scoped to the profile that names it.
 */
@Alternative
@ApplicationScoped
public class StubWorkspaceTunnels extends WorkspaceTunnels {

  /** An armed workspace: a connected daemon at {@code capability}, or none when it is null. */
  private record Armed(Integer capability, int servicePort) {}

  private final Map<Long, Armed> armed = new ConcurrentHashMap<>();

  private final Map<Long, HttpClient> clients = new ConcurrentHashMap<>();

  private final List<String> asked = new CopyOnWriteArrayList<>();

  /** Stage a connected daemon at {@code capability} whose SERVICE tunnel enters at {@code port}. */
  public void arm(Long workspaceId, int capability, int port) {
    armed.put(workspaceId, new Armed(capability, port));
  }

  /** Stage a workspace whose daemon is not connected. */
  public void armDisconnected(Long workspaceId) {
    armed.put(workspaceId, new Armed(null, 0));
  }

  /** Every {@code workspace/target/serviceId} an armed workspace's origin was asked for. */
  public List<String> asked() {
    return asked;
  }

  public void reset() {
    armed.clear();
    asked.clear();
    clients.values().forEach(HttpClient::close);
    clients.clear();
  }

  @Override
  public OptionalInt daemonCapability(Long workspaceRowId) {
    Armed staged = workspaceRowId == null ? null : armed.get(workspaceRowId);
    if (staged == null) {
      return super.daemonCapability(workspaceRowId);
    }
    return staged.capability() == null
        ? OptionalInt.empty()
        : OptionalInt.of(staged.capability().intValue());
  }

  @Override
  public Optional<TunnelOrigin> originFor(
      Long workspaceRowId, StreamTarget target, String serviceId) {
    Armed staged = workspaceRowId == null ? null : armed.get(workspaceRowId);
    if (staged == null) {
      return super.originFor(workspaceRowId, target, serviceId);
    }
    asked.add(workspaceRowId + "/" + target + "/" + serviceId);
    if (staged.capability() == null
        || target != StreamTarget.SERVICE
        || staged.capability() < capabilityFor(StreamTarget.SERVICE)) {
      return Optional.empty();
    }
    // One client per workspace, as the real tunnel owns one: the route must use THIS client.
    HttpClient client = clients.computeIfAbsent(workspaceRowId, id -> vertx.createHttpClient());
    return Optional.of(new TunnelOrigin(client, staged.servicePort()));
  }
}
