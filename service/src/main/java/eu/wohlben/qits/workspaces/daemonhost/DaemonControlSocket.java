package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The backend endpoint each workspace's in-container {@code workspace-daemon} dials on boot
 * (docs/epics/qits-workspace-daemon/). It owns only the WebSocket lifecycle and JSON framing —
 * {@link WorkspaceDaemonRegistry} owns the state and correlated traffic.
 *
 * <p>Reachability mirrors the container's other channels ({@code /artifacts/git}, {@code
 * /observability/api/otel}, {@code /projects/mcp}): a plain {@code ws://} connection over qits-net
 * on the main HTTP port, authenticated with {@code qits:system}. {@code SameOriginUpgradeCheck}
 * permits it because {@code workspace-daemon} is a non-browser client and sends no {@code Origin}.
 *
 * <p><strong>The path is a cross-repo contract.</strong> {@code WorkspaceContainerFactory} injects
 * {@code ws://qits-workspaces:8080/workspaces/daemon/<id>} as {@code QITS_WORKSPACE_DAEMON_URL} into
 * every container it creates, and qits-workspace-daemon dials exactly that; the two must be changed
 * together. {@code daemon} is a second-level segment beside {@code api} because this is not a JSON
 * API — and note a {@code @WebSocket} path is a literal that does <em>not</em> follow {@code
 * quarkus.rest.path}, so it carries the {@code /workspaces} segment itself.
 *
 * <p>Part 1: the socket carries the {@code Hello}/{@code Ack} handshake, heartbeats, {@code
 * workspace-daemon}'s own logs, and — for the demonstration/extended tests only — a {@code
 * RunCommand} round-trip. It drives no existing behaviour; the {@code docker exec} paths are
 * untouched.
 */
@WebSocket(path = "/workspaces/daemon/{id}")
// qits:agent too, for the day agents stop presenting the owner's roles. An agent may open only its
// own workspace's socket: DaemonAgentBindingCheck refuses any other path with 403.
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class DaemonControlSocket {

  private static final Logger LOG = Logger.getLogger(DaemonControlSocket.class);

  @Inject WorkspaceDaemonRegistry registry;

  @Inject DaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("id") String id, WebSocketConnection connection) {
    Long workspaceId = parse(id);
    if (workspaceId != null) {
      registry.register(workspaceId, connection);
    }
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message, @PathParam("id") String id, WebSocketConnection connection) {
    Long workspaceId = parse(id);
    if (workspaceId == null) {
      return;
    }
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable workspace-daemon frame for workspace %s: %s", id, e.getMessage());
      return;
    }
    registry.onMessage(workspaceId, connection, decoded);
  }

  @OnClose
  public void onClose(@PathParam("id") String id, WebSocketConnection connection) {
    Long workspaceId = parse(id);
    if (workspaceId != null) {
      registry.unregister(workspaceId, connection);
    }
  }

  /**
   * The path segment as a workspace id, or null when it is not one.
   *
   * <p>It arrives as text because websockets-next requires {@code @PathParam} parameters to be
   * {@code String} — the framework rejects the endpoint at build time otherwise. So the parse is
   * here rather than in the signature, and a non-numeric segment is simply not a workspace: the
   * connection is left unregistered rather than guessed at.
   */
  private Long parse(String id) {
    try {
      return Long.valueOf(id);
    } catch (NumberFormatException notAnId) {
      LOG.warnf("Ignoring a workspace-daemon connection on a non-numeric workspace id '%s'", id);
      return null;
    }
  }
}
