package eu.wohlben.qits.workspaces.entity;

/**
 * Whether an agent holds its workspace slot (D14, D16): QUEUED has no workspace yet, RUNNING holds
 * the slot, YIELDED gave the slot up while it waits for input. A yielded agent's harness keeps
 * running (qits-1212).
 *
 * <p>STOPPED (epic qits-1667): a person stopped it ({@code POST /agents/{id}/stop}). It holds no
 * slot, and the scheduler never places, resumes, nudges or drives it; what it should hear waits on
 * its row until it is restarted or prompted. It keeps its workspace when it had one.
 */
public enum AgentRunState {
  QUEUED,
  RUNNING,
  YIELDED,
  STOPPED
}
