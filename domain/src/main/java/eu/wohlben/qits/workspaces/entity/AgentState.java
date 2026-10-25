package eu.wohlben.qits.workspaces.entity;

/**
 * An agent's lifecycle (qits-1152, D4): ACTIVE while it works on its item, OBSOLETE once a release
 * consumed its wrapper branch and its item is closed — VERIFIED, DONE or DROPPED, in whichever
 * order the two happen (qits-1605); a DROPPED item's agent needs no release (qits-1276) — REMOVED
 * once its worktrees and credential are gone.
 */
public enum AgentState {
  ACTIVE,
  OBSOLETE,
  REMOVED
}
