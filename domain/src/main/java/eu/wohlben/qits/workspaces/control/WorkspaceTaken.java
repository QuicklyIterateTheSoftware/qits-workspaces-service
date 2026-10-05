package eu.wohlben.qits.workspaces.control;

import java.util.UUID;

/**
 * A QUEUED RUNNER row was taken by a runner (qits-626): the reserve's compare-and-swap moved it to
 * PROVISIONING on {@code runnerId} ({@link RunnerClaims#reserveFor}), or a runner's inventory
 * reported holding it running while it was still queued ({@link RunnerClaims#reconcile}).
 *
 * <p>Fired in-process <b>after the write committed</b>, never inside it, so a claim that rolled back
 * releases nothing. Its one reader is {@link DispatchService}, which holds an agent launch (or a
 * delivery) parked while the row waits in the queue and starts its window from this moment.
 *
 * @param rowId the workspace row, {@code Workspace.id}
 * @param runnerId the runner that holds it now
 */
public record WorkspaceTaken(Long rowId, UUID runnerId) {}
