package eu.wohlben.qits.workspaces.entity;

/**
 * Where a workspace's container runs ({@code Workspace.placement}, {@code V12}). Decided when the
 * workspace is created and never changed afterwards.
 */
public enum WorkspacePlacement {
  /** On the platform host, through qits-containers. Every admin and editor workspace is DIRECT. */
  DIRECT,
  /**
   * On a workspace runner's node. The runner takes the row from the queue, and the row stays with
   * that runner ({@code Workspace.runnerId}) while its volume lives there.
   */
  RUNNER
}
