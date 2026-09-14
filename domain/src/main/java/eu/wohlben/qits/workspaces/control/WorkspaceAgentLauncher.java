package eu.wohlben.qits.workspaces.control;

/**
 * The host's verbs against a running workspace-daemon's coding-agent surface: <b>start an agent in
 * this workspace</b>, <b>say something to the one that is already going</b>, and the question that
 * has to be asked before either — <b>is one going?</b>
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
   * What became of a turn this host tried to say to a workspace's agent.
   *
   * <p>Three values and not a boolean, for {@link AgentState}'s reason turned around: the caller
   * acts differently on each. {@link #UNREACHABLE} is a container that is not answering yet, which
   * is a wait; {@link #NOT_DELIVERED} is a daemon that answered and had nobody to tell, which is a
   * launch; {@link #DELIVERED} is done. Collapsing the middle two is how a text meant for an agent
   * is dropped while the container it was for finishes starting.
   */
  enum DeliveryOutcome {
    /** The daemon did not answer: no container, no route to it, or an error status. */
    UNREACHABLE,
    /** The daemon answered and said no agent was running to say it to. */
    NOT_DELIVERED,
    /** The daemon took the text and gave it to a running agent as a user turn. */
    DELIVERED
  }

  /**
   * Start a chat-mode agent seeded with {@code instruction}.
   *
   * @return whether the daemon accepted the launch. False rather than an exception: the caller runs
   *     off the request thread and has already answered, so a refusal is something to log, not
   *     something to unwind.
   */
  boolean launch(Long workspaceRowId, String instruction);

  /**
   * Say {@code text} to the agent running in {@code workspaceRowId} — the same thing a person types
   * into the chat tab, originated by this host instead of by a browser.
   *
   * <p><b>This is the verb the platform was missing.</b> {@link #launch} can only open a
   * conversation, so the only thing the dispatch door could do about an agent that was already
   * working was leave it alone ({@code DispatchService.AgentLaunch#SKIPPED_RUNNING}). A user turn
   * had exactly one entrance — a browser on the daemon's command websocket — and no machine holds
   * one. See {@link DispatchService#deliver} for the three arms this is the middle of.
   *
   * <p>Never throws for absence, like everything else here: a daemon that is not there is {@link
   * DeliveryOutcome#UNREACHABLE}, which is an answer.
   */
  DeliveryOutcome deliver(Long workspaceRowId, String text);
}
