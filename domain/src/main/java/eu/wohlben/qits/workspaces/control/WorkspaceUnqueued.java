package eu.wohlben.qits.workspaces.control;

/**
 * A RUNNER row's start ended other than by a runner taking it (qits-626): it was stopped while
 * queued, its container was deleted, it was resolved or abandoned, or its runner reported it failed
 * or gone. Fired by {@link RunnerClaims#abandonStart}, which every one of those paths calls.
 *
 * <p>Read by {@link AgentService#onUnqueued}, <b>after the firing transaction committed</b>
 * (immediately when there is none), which asks for a scheduler pass (qits-1233): an agent placed in
 * a workspace that ended FAILED — its daemon never dialed home, or its runner could not launch it —
 * is queued for another workspace by that pass. It was read by {@code DispatchService} until
 * qits-1152, and by nobody between the two, which is how such an agent was stranded.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param reason why the start ended, in the words its technical process was failed with
 */
public record WorkspaceUnqueued(Long rowId, String reason) {}
