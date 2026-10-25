package eu.wohlben.qits.workspaces.entity;

/**
 * An agent's lifecycle (qits-1152, D4): ACTIVE while it works on its item, OBSOLETE once its item
 * is closed (VERIFIED or DONE) and its wrapper branch's work has been released — by a release
 * consuming the branch, or by the branch being gone from the git host or merged into the default
 * branch — in whichever order the two happen (qits-1605); a DROPPED item's agent needs no release
 * (qits-1276). REMOVED once its worktrees and credential are gone.
 */
public enum AgentState {
  ACTIVE,
  OBSOLETE,
  REMOVED
}
