package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * Why a workspace's daemon cannot be reached, asked once its reverse tunnel was not there, so
 * {@code ContainerProxyRoute} can answer each absence with its own status.
 *
 * <p><b>The tunnel is the only way to a daemon</b> (qits-780). Every daemon at {@code
 * DaemonProtocol.TUNNEL_CAPABILITY_VERSION} or above binds its API to {@code 127.0.0.1} and is
 * reached by dialling back; the direct {@code container:13338} fallback this class used to resolve
 * for older daemons is deleted, because no such daemon was left running (measured: no pre-id dial
 * in seven days, every live daemon above the tunnel capability). So this never answers an origin:
 * a caller with no tunnel has nothing to dial, and this only says which absence it is.
 *
 * <h2>Scoping, not authorization</h2>
 *
 * <p>The resolution goes through {@link WorkspaceRepository#findActiveById}, so an unknown id and a
 * soft-deleted row are one answer and neither reaches a container. That is the whole check, and it
 * is scoping rather than authorization: qits is a single-user application and a workspace has no
 * owner to compare a caller against.
 *
 * <h2>The container is asked about only behind the router's refusal</h2>
 *
 * <p>A RUNNER row's container is on a runner's node, so it answers {@link
 * Reachability#NOT_CONNECTED} without asking the {@link ContainerRuntime} anything (qits-812). A
 * DIRECT row — admin and editor only, {@link WorkspacePlacements#requireDirectAllowed} — is asked
 * whether its container runs, so a stopped one says "start it" rather than "not connected".
 */
@ApplicationScoped
public class DaemonProxyTargets {

  @Inject WorkspaceRepository workspaces;

  @Inject ContainerRuntime containers;

  /**
   * Why a daemon cannot be reached — the proxy answers differently for each.
   *
   * <p><b>Control-socket liveness is deliberately not one of these.</b> The daemon's HTTP server and
   * its control socket are two independent listeners; the tunnel's own presence is the answer the
   * callers asked before this one.
   */
  public enum Reachability {
    /** No ACTIVE workspace with that id. Indistinguishable from a soft-deleted one, deliberately. */
    NO_WORKSPACE,
    /**
     * The workspace exists and its daemon holds no tunnel: there is no other path to try. The
     * callers answer 503 "workspace daemon not connected".
     */
    NOT_CONNECTED,
    /** A DIRECT workspace whose container is not running. */
    NO_CONTAINER
  }

  /**
   * Resolve why {@code workspaceRowId}'s daemon has no tunnel.
   *
   * <p>{@code @Transactional} because the row read needs a session and the caller is a raw Vert.x
   * route with none — the route runs this on a worker thread for that reason, never on the event loop.
   */
  @Transactional
  public Reachability resolve(Long workspaceRowId) {
    if (workspaceRowId == null) {
      return Reachability.NO_WORKSPACE;
    }
    Optional<Workspace> found = workspaces.findActiveById(workspaceRowId);
    if (found.isEmpty()) {
      return Reachability.NO_WORKSPACE;
    }
    Workspace workspace = found.get();
    if (workspace.placement == WorkspacePlacement.RUNNER) {
      return Reachability.NOT_CONNECTED;
    }
    WorkspacePlacements.requireDirectAllowed(workspace);
    String container = containers.containerName(workspace.workspaceId, workspace.repositoryId);
    // Present-but-Exited counts as not running: a stopped container answers nothing, and saying
    // "not running" is what tells the caller to start it rather than to retry.
    return containers.isRunning(container) ? Reachability.NOT_CONNECTED : Reachability.NO_CONTAINER;
  }
}
