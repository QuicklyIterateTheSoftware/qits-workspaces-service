package eu.wohlben.qits.workspaces.entity;

/**
 * The runtime state of a workspace's container, distinct from the lifecycle {@link WorkspaceStatus}
 * (which records whether the <em>unit of work</em> is ACTIVE or resolved). A workspace's real work
 * lives in its durable branch; the container is a recreatable cache of it, so the container can be
 * absent for mundane reasons without the workspace being dead. This status reflects that cache.
 *
 * <p>{@link #RUNNING} is computed live from the actual container listing; {@link #STOPPED}, {@link
 * #PROVISIONING} and {@link #FAILED} are persisted so the UI can offer a recreate action and, on
 * failure, tell the user <em>why</em> (see {@code Workspace.runtimeError}).
 *
 * <p><b>Two values belong to RUNNER placement only</b> ({@code V12}). {@link #QUEUED} is
 * <em>persisted</em>: the column admits it, and a runner's reserve reads it. {@link #UNAVAILABLE}
 * is <em>computed only</em>: it is laid over a read and never written, and the column's check
 * refuses it.
 */
public enum WorkspaceRuntimeStatus {
  /** The container exists and can be executed against. */
  RUNNING,
  /** No container right now, but the branch survives; re-provision lazily on next use. */
  STOPPED,
  /** A re-provision (docker run + clone) is in flight. */
  PROVISIONING,
  /** The last re-provision attempt failed; {@code Workspace.runtimeError} holds the reason. */
  FAILED,
  /**
   * A RUNNER row that was asked to start and waits for a slot on a runner. <b>Persisted</b>: {@code
   * Workspace.queuedAt} says since when, and a runner's reserve takes the oldest.
   */
  QUEUED,
  /**
   * A RUNNER row whose owning runner has been offline beyond the reconnect grace. <b>Computed only,
   * never stored</b>: it is laid over the persisted status on every read, and the column's check
   * refuses it, so a reconnect clears it without a write.
   */
  UNAVAILABLE
}
