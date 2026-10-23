package eu.wohlben.qits.workspaces.control;

/**
 * A TARGET row's start ended other than by its container coming up (qits-626, epic qits-1481): it
 * was stopped while queued, its container was deleted, it was resolved or abandoned, or its lease
 * reported it failed or gone. Fired by {@link TargetLeaseStates#abandonStart}, which every one of
 * those paths calls.
 *
 * <p>Read by {@link AgentService#onUnqueued}, <b>after the firing transaction committed</b>
 * (immediately when there is none), which asks for a scheduler pass (qits-1233): an agent placed in
 * a workspace that ended FAILED — its daemon never dialed home, or its target could not launch it —
 * is queued for another workspace by that pass. It was read by {@code DispatchService} until
 * qits-1152, and by nobody between the two, which is how such an agent was stranded.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param reason why the start ended, in the words its technical process was failed with
 */
public record WorkspaceUnqueued(Long rowId, String reason) {}
