package eu.wohlben.qits.workspaces.control;

/**
 * A RUNNER row's start ended other than by a runner taking it (qits-626): it was stopped while
 * queued, its container was deleted, it was resolved or abandoned, or its runner reported it failed
 * or gone. Fired by {@link RunnerClaims#abandonStart}, which every one of those paths calls.
 *
 * <p>Read by {@link DispatchService}, whose observer runs <b>after the firing transaction
 * committed</b> (immediately when there is none), and drops the agent launch it held parked for the
 * row: no runner is going to take it, so nothing would ever release it. A row that was not parked
 * is no news to anyone.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param reason why the start ended, in the words its technical process was failed with
 */
public record WorkspaceUnqueued(Long rowId, String reason) {}
