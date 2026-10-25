package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The last activity state a workspace daemon reported for one agent (qits-1152), in memory and only
 * while the daemon's socket is up. The scheduler reads it to deliver a turn at a turn boundary, and
 * the read doors show it.
 */
public interface AgentActivityView {

  /**
   * The raw state, exactly as the harness's last hook said. Every internal gate reads this one: a
   * turn boundary is a turn boundary even while background work the agent started is in flight, so
   * a pending turn is typed into an {@code IDLE} agent at once (qits-1601).
   */
  Optional<AgentActivityState> activityOf(String agentId);

  /**
   * The state the agent doors show (qits-1601): {@link #activityOf}, except {@code BUSY} while the
   * harness has ended its turn ({@code IDLE} or {@code WAITING}) but background work it started — a
   * subagent, a monitor, a background shell — is still in flight, and the daemon has not yet given
   * up on it ({@code awaitingInput} is not {@code true}). Without it an agent waiting on its own
   * subagent reads as stalled. Same emptiness and expiry as {@link #activityOf}.
   */
  Optional<AgentActivityState> reportedActivityOf(String agentId);
}
