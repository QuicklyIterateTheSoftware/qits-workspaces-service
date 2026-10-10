package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * The last activity state a workspace daemon reported for one agent (qits-1152), in memory and only
 * while the daemon's socket is up. The scheduler reads it to deliver a turn at a turn boundary, and
 * the read doors show it.
 */
public interface AgentActivityView {

  Optional<AgentActivityState> activityOf(String agentId);
}
