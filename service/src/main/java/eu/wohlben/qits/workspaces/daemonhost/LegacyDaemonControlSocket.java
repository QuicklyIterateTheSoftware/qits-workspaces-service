package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspacedaemon.protocol.DaemonMessage;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The pre-id dial-home path, kept alive for containers that were provisioned before the control
 * socket moved to {@link DaemonControlSocket}'s {@code /id/{id}}.
 *
 * <p>A daemon learns its URL from {@code QITS_WORKSPACE_DAEMON_URL}, injected once at container
 * creation, so a running container keeps dialling whatever it was given until it is recreated.
 * Without this it would simply lose its control plane — no clean/dirty badge, no service
 * supervision, no bootstrap reporting — until someone recreated it. That is too much to charge for
 * a rename.
 *
 * <p><strong>This path is the defect, not a supported address.</strong> A workspace label is
 * branch-derived and unique only within a repository, so it cannot identify a workspace on its own —
 * every repository tends to have one called {@code main}. What this does is resolve the label and
 * <em>refuse</em> when it is ambiguous, which is strictly better than the silent collision the
 * unqualified key used to produce. A refused connection is a workspace whose daemon needs its
 * container recreated to pick up the id-addressed URL.
 *
 * <p><strong>Its path deliberately keeps no {@code /workspaces} segment</strong>, while {@link
 * DaemonControlSocket} moved to {@code /workspaces/daemon/{id}} with the rest of this service's
 * surface. Prefixing this one would defeat the only thing it does: the address is not ours to
 * choose, it is whatever was already baked into a running container's environment. That leaves it
 * unreachable through qits-gateway, which routes {@code /workspaces/*} and nothing else here — and
 * that is correct too, since its callers are daemons on qits-net dialling the container directly,
 * never a browser coming through the gateway.
 *
 * <p>Delete this once no container provisioned against the old URL can still be running.
 */
@WebSocket(path = "/api/workspace-daemon/{workspaceId}")
// As DaemonControlSocket: qits:agent may open only the socket of the workspace it was commissioned
// for, which DaemonAgentBindingCheck resolves from this label.
@jakarta.annotation.security.RolesAllowed({"qits:system", "qits:agent"})
public class LegacyDaemonControlSocket {

  private static final Logger LOG = Logger.getLogger(LegacyDaemonControlSocket.class);

  @Inject WorkspaceDaemonRegistry registry;

  @Inject WorkspaceRepository workspaces;

  @Inject DaemonMessageCodec codec;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("workspaceId") String label, WebSocketConnection connection) {
    Long id = resolve(label);
    if (id == null) {
      return;
    }
    LOG.warnf(
        "workspace-daemon for '%s' dialled the pre-id control path; it resolved to workspace %d."
            + " Recreate its container to move it onto the id-addressed URL.",
        label, id);
    registry.register(id, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(
      String message, @PathParam("workspaceId") String label, WebSocketConnection connection) {
    Long id = resolve(label);
    if (id == null) {
      return;
    }
    DaemonMessage decoded;
    try {
      decoded = codec.decode(message);
    } catch (RuntimeException e) {
      LOG.debugf(
          "Dropped an undecodable workspace-daemon frame for workspace %s: %s",
          label, e.getMessage());
      return;
    }
    registry.onMessage(id, connection, decoded);
  }

  @OnClose
  public void onClose(@PathParam("workspaceId") String label, WebSocketConnection connection) {
    Long id = resolve(label);
    if (id != null) {
      registry.unregister(id, connection);
    }
  }

  /**
   * The single ACTIVE workspace carrying this label, or null when none or more than one does. The
   * ambiguous case is exactly the collision this path cannot resolve, and answering it with a guess
   * is what the id exists to stop.
   */
  @Transactional
  Long resolve(String label) {
    List<Workspace> matches =
        workspaces.list("workspaceId = ?1 and status = ?2", label, eu.wohlben.qits.workspaces.entity.WorkspaceStatus.ACTIVE);
    if (matches.size() == 1) {
      return matches.get(0).id;
    }
    if (matches.isEmpty()) {
      LOG.debugf("No active workspace named '%s' dialled home on the pre-id control path", label);
    } else {
      LOG.warnf(
          "%d active workspaces are named '%s', so the pre-id control path cannot say which one"
              + " dialled home. Recreate its container to move it onto the id-addressed URL.",
          matches.size(), label);
    }
    return null;
  }
}
