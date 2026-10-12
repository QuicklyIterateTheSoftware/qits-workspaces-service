package eu.wohlben.qits.workspaces.entity;

/**
 * Whether an agent holds its workspace slot (D14, D16): QUEUED has no workspace yet, RUNNING holds
 * the slot, YIELDED gave the slot up while it waits for input. A yielded agent's harness keeps
 * running (qits-1212).
 */
public enum AgentRunState {
  QUEUED,
  RUNNING,
  YIELDED
}
