package eu.wohlben.qits.workspaces.dto;

/**
 * Where an ACTIVE agent that wants to run is in coming up (qits-2007): the phase the agent views
 * answer beside {@code runState} and {@code activity}, which keep their meanings. Null for an agent
 * that is not coming up or running at all (YIELDED, STOPPED, REMOVED).
 */
public enum AgentPhase {
  /** It waits for a slot, or its slot waits for a target lease. */
  QUEUED,
  /** It holds a slot whose container is not running yet, or whose daemon has not connected. */
  PROVISIONING,
  /** Its slot runs and its daemon is connected, but its harness has not been started yet. */
  STARTING,
  /** Its harness was started; {@code activity} says what it is doing. */
  READY
}
