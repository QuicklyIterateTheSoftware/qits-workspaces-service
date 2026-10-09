package eu.wohlben.qits.workspaces.control;

/**
 * Tells qits-projects whether the agent a dispatch put on a work item is <b>waiting for its user</b>
 * — the signal it derives the item's BLOCKED from (qits-895).
 *
 * <p>The fact itself is the workspace-daemon's: at capability 8 every agent-activity frame carries
 * {@code awaitingInput}, true when the agent's turn ended with nothing in flight, on a permission
 * prompt, or with the session over. This service only knows <em>which</em> agent the frame is about
 * — the one whose command {@code Workspace.dispatchCommandId} names — and which work item the row is
 * bound to, so the relay is here and the decision about what a waiting agent means is there.
 *
 * <p>A port, for the reason every other context's call is one: the transport is qits-projects' REST
 * door and lives in {@code service}. Injected as {@code Instance<>}; absent means nothing is
 * relayed, which is what a deployment with no qits-projects would want anyway.
 */
public interface AgentWaitingReporter {

  /**
   * Report one flip of the dispatched agent's waiting state on {@code workId}.
   *
   * <p>A qits-projects too old to carry the door is not an error: the implementation logs it and
   * returns. Anything else that goes wrong throws, and the caller logs and drops it — there is no
   * retry beyond the next flip.
   *
   * @param workId the work item's entity id in qits-projects ({@code Workspace.workId})
   * @param waiting whether the agent is waiting for its user
   * @param cause the hook event that said so ({@code Stop}, {@code Notification}, {@code
   *     SessionEnd}, {@code OomKilled}, …), or null when the frame carried none
   * @param sessionId the agent session the frame was about, or null
   * @param at when the daemon saw it, epoch milliseconds
   */
  void report(String workId, boolean waiting, String cause, String sessionId, long at);
}
