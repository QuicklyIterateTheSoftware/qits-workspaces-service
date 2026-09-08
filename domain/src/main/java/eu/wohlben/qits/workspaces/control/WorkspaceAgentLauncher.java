package eu.wohlben.qits.workspaces.control;

/**
 * The host's one verb against a running workspace-daemon's coding-agent surface: <b>start an agent
 * in this workspace</b>, and the one question that has to be asked before it — <b>is one already
 * going?</b>
 *
 * <p><b>Why this is a port at all, when the daemon is already reachable.</b> Everything that has
 * ever called a daemon's HTTP API did so on a browser's behalf: {@code ContainerProxyRoute}
 * forwards the request the SPA made, and no host-side code has ever originated one. The dispatch
 * door does — nobody is holding a socket open for it — so somebody has to build the request, and
 * that somebody needs Vert.x, the tunnel registry and the daemon bearer, none of which belong in
 * {@code domain}. So the shape is declared here and the transport lives in {@code service}, exactly
 * as {@link WorkspaceDaemonLiveness} and {@link WorkspaceProcessTracker} already do.
 *
 * <p>Injected as {@code Instance<WorkspaceAgentLauncher>} like every other port here. Absent means
 * {@link AgentState#UNREACHABLE} and a launch that never happens — which is the honest answer for a
 * deployment with no daemon transport, and one the dispatch door reports as a warning rather than
 * as a failure. See {@link DispatchService} for why a launch failing is never an error the caller
 * sees.
 *
 * <p><b>Liveness here is the daemon's own API answering, and deliberately not the control
 * socket.</b> {@link DaemonProxyTargets} already records why: the daemon's HTTP server and its
 * control socket are independent listeners, and a socket in reconnect backoff leaves the API bound
 * and serving. A launcher that gated on the socket would refuse to start an agent in a container
 * that would have taken one — so the probe is the request itself.
 */
public interface WorkspaceAgentLauncher {

  /**
   * What the daemon says about this workspace's agents right now.
   *
   * <p>Three values and not a boolean, because "no agent is running" and "nobody could be asked"
   * lead to opposite actions: the first is a launch, the second is a container to bring up and a
   * wait. Collapsing them is how a dispatch onto a workspace whose container is still pulling an
   * image would launch nothing and report success.
   */
  enum AgentState {
    /** The daemon did not answer: no container, no route to it, or an error status. */
    UNREACHABLE,
    /** The daemon answered and no agent command is running in it. */
    IDLE,
    /** The daemon answered and an agent command is running. */
    RUNNING
  }

  /** Ask {@code workspaceRowId}'s daemon what its agents are doing. Never throws for absence. */
  AgentState agentState(Long workspaceRowId);

  /**
   * Start a chat-mode agent seeded with {@code instruction}.
   *
   * @return whether the daemon accepted the launch. False rather than an exception: the caller runs
   *     off the request thread and has already answered, so a refusal is something to log, not
   *     something to unwind.
   */
  boolean launch(Long workspaceRowId, String instruction);
}
