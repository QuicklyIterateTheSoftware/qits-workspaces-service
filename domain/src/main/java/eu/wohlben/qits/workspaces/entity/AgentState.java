package eu.wohlben.qits.workspaces.entity;

/**
 * An agent's lifecycle (qits-1152, D4): ACTIVE while it works on its item, OBSOLETE once a release
 * consumed its wrapper branch, REMOVED once its worktrees and credential are gone.
 */
public enum AgentState {
  ACTIVE,
  OBSOLETE,
  REMOVED
}
