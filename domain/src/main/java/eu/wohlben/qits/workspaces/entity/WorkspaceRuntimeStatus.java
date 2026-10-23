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
 * <p><b>Two values belong to TARGET placement only</b> ({@code V12}). {@link #QUEUED} is
 * <em>persisted</em>: the row's lease waits for a slot, and {@code Workspace.runtimeError} carries
 * the lease's {@code detail} (why it waits). {@link #UNAVAILABLE} is no longer produced (epic
 * qits-1481): it was laid over a row whose workspace runner was offline, and a lease has no such
 * state. It stays in the enum so a client's exhaustive switch keeps compiling; the column's check
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
   * A TARGET row whose lease waits for a slot. <b>Persisted</b>: {@code Workspace.queuedAt} says since
   * when, and {@code Workspace.runtimeError} why (the lease's {@code detail}).
   */
  QUEUED,
  /**
   * Not produced since epic qits-1481: it was a TARGET row whose workspace runner was offline beyond
   * the reconnect grace. Never stored; the column's check refuses it.
   */
  UNAVAILABLE
}
