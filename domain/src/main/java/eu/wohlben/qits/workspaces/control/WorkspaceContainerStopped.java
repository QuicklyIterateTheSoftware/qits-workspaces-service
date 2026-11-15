package eu.wohlben.qits.workspaces.control;

/**
 * A live workspace row's container was stopped and the row now reads STOPPED (qits-2265): a
 * stop-container ({@link WorkspaceService#stopContainer(Long, String)}), or a lease that ended or
 * exited under a row still QUEUED, PROVISIONING or RUNNING ({@link TargetLeaseStates}). Fired once
 * per transition, by the write that made it: the ENDED that follows a door's stop finds the row
 * STOPPED already and fires nothing, and a verb that ends the lease and writes the row itself
 * (delete-container, recreate, a start retiring the previous lease) fires nothing either.
 *
 * <p>Read by {@link AgentService#onContainerStopped}, <b>after the firing transaction committed</b>
 * (immediately when there is none): the slot's RUNNING agents are YIELDED there and forgotten as
 * started, so the next turn they hear brings the slot up and starts them afresh, and {@code
 * WorkspaceStopped} is announced. Until qits-2265 nothing read it, and those agents stayed RUNNING
 * in a slot no pass would ever start again.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param repositoryId the row's repository
 * @param stoppedBy the stopping caller's principal; null for a lease that ended on its own
 * @param reason this service's one line: the reason sent to qits-system, or the lease's detail
 */
public record WorkspaceContainerStopped(
    Long rowId, String repositoryId, String stoppedBy, String reason) {}
