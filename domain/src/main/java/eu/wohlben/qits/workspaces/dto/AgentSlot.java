package eu.wohlben.qits.workspaces.dto;

/**
 * Whether an agent's workspace holds its lease (qits-2573): what qits-projects' delivery desk
 * counts as a running slot. Answered beside {@code runState}, which says only whether the agent is
 * placed, and {@code phase}, which says how far it has come up. Computed on read by {@code
 * control/AgentSlots}.
 */
public enum AgentSlot {
  /**
   * Placed in a workspace that holds its slot: a DIRECT workspace, or a TARGET one with its lease
   * and target set and not QUEUED.
   */
  HELD,
  /**
   * It waits for one: no workspace yet ({@code runState} QUEUED), or placed in a workspace whose
   * lease still waits at qits-system ({@code runtimeStatus} QUEUED).
   */
  QUEUED,
  /**
   * Neither: a REMOVED agent, a workspace that FAILED, is gone, or is a TARGET row without a lease
   * (STOPPED).
   */
  NONE
}
