package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The one lookup the workspace-daemon proxy performs: workspace row id → where that workspace's
 * daemon is, or why it cannot be reached. The sibling of {@link ServiceSupervisor#proxyTarget} and
 * deliberately the same shape, so {@code ContainerProxyRoute} reads like {@code ServiceProxyRoute}.
 *
 * <p><b>The origin comes exclusively from our own state.</b> The container name is derived
 * deterministically from the row ({@link ContainerRuntime#containerName}) and the port is
 * configuration; no component of the request ever selects a host or a port. A container runs an
 * untrusted checkout with unrestricted outbound network, so an address it reported would be
 * attacker-controlled input and a host that dialled it would be an SSRF primitive aimed at
 * everything on {@code qits-net}. This is the same rule {@link ServiceProxyRoute} follows for dev
 * servers, and the reason the daemon's {@code Hello} does not — and must not — announce an address.
 *
 * <h2>Scoping, not authorization</h2>
 *
 * <p>The resolution goes through {@link WorkspaceRepository#findActiveById}, so an unknown id and a
 * soft-deleted row are one answer and neither reaches a container. That is the whole check, and it
 * is scoping rather than authorization: qits is a single-user application and a workspace has no
 * owner to compare a caller against. Inventing one would be machinery defending a boundary the
 * product does not have — and this service authenticates nothing anyway (see {@code
 * ForwardAuthMechanism}); a check of the form "is the caller anonymous" would look like a security
 * control and be worth nothing.
 *
 * <h2>A RUNNER row has no direct origin (qits-625, qits-812)</h2>
 *
 * <p>Its container is on a runner's node, which has no {@code qits-net}: the daemon is reachable
 * through its reverse tunnel or not at all. So a RUNNER row resolves to {@link
 * Reachability#NOT_CONNECTED} — the answer the callers reach only once the tunnel was not there —
 * without asking the {@link ContainerRuntime} anything, and never to a {@code container:13338}
 * origin, which on this host would name nothing or, worse, somebody else's container.
 */
@ApplicationScoped
public class DaemonProxyTargets {

  @Inject WorkspaceRepository workspaces;

  @Inject ContainerRuntime containers;

  /** The port {@code WorkspaceApi} binds inside the container; see the daemon's own config key. */
  @ConfigProperty(name = "qits.workspace.daemon-api-port", defaultValue = "13338")
  int daemonApiPort;

  /**
   * Why a daemon cannot be reached, or that it can — the proxy answers differently for each.
   *
   * <p><b>Control-socket liveness is deliberately not one of these.</b> {@link
   * WorkspaceDaemonLiveness} would be the obvious fifth state, and gating on it would be a bug: the
   * daemon's HTTP server and its control socket are two independent listeners, and a socket that is
   * merely in reconnect backoff leaves the HTTP API bound and answering. Refusing here would take
   * file browsing and every open terminal down for the length of a blip — the availability coupling
   * that is the whole reason these calls do not ride the control socket in the first place. A
   * daemon that genuinely is not there fails the connection instead, which is one 502 we accept
   * being generic.
   */
  public enum Reachability {
    /** No ACTIVE workspace with that id. Indistinguishable from a soft-deleted one, deliberately. */
    NO_WORKSPACE,
    /**
     * A RUNNER row whose daemon holds no tunnel: there is no direct path to try (qits-812). The
     * callers answer 503 "workspace daemon not connected".
     */
    NOT_CONNECTED,
    /** The workspace exists but its container is not running. */
    NO_CONTAINER,
    /** The container is there and the runtime cannot say where to reach it. */
    UNREACHABLE,
    /** Reachable at {@link DaemonTarget#origin()}. */
    READY
  }

  /** Where a workspace's daemon is, or why it is not there. {@code origin} is set only on READY. */
  public record DaemonTarget(Reachability reachability, ProxyOrigin origin) {}

  /**
   * Resolve the proxy target for {@code workspaceRowId}.
   *
   * <p>{@code @Transactional} because the row read needs a session and the caller is a raw Vert.x
   * route with none — the route runs this on a worker thread for that reason, exactly as {@code
   * ServiceProxyRoute} runs its supervisor lookup off the event loop.
   */
  @Transactional
  public DaemonTarget resolve(Long workspaceRowId) {
    if (workspaceRowId == null) {
      return new DaemonTarget(Reachability.NO_WORKSPACE, null);
    }
    Optional<Workspace> found = workspaces.findActiveById(workspaceRowId);
    if (found.isEmpty()) {
      return new DaemonTarget(Reachability.NO_WORKSPACE, null);
    }
    Workspace workspace = found.get();
    if (workspace.placement == WorkspacePlacement.RUNNER) {
      return new DaemonTarget(Reachability.NOT_CONNECTED, null);
    }
    String container = containers.containerName(workspace.workspaceId, workspace.repositoryId);
    if (!containers.isRunning(container)) {
      // Present-but-Exited counts as not running: a stopped container answers nothing, and saying
      // "not running" is what tells the caller to start it rather than to retry.
      return new DaemonTarget(Reachability.NO_CONTAINER, null);
    }
    ProxyOrigin origin = containers.resolveTarget(container, daemonApiPort);
    return origin == null
        ? new DaemonTarget(Reachability.UNREACHABLE, null)
        : new DaemonTarget(Reachability.READY, origin);
  }
}
