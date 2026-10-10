package eu.wohlben.qits.workspaces.entity;

/**
 * Whether an agent holds its workspace's one harness (D14, D16): QUEUED has no workspace yet,
 * RUNNING holds the slot, YIELDED keeps its worktree and session but not the harness.
 */
public enum AgentRunState {
  QUEUED,
  RUNNING,
  YIELDED
}
