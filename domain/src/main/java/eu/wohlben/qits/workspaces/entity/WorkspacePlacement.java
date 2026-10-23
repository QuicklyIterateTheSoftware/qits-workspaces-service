package eu.wohlben.qits.workspaces.entity;

/**
 * Where a workspace's container runs ({@code Workspace.placement}, {@code V12}, {@code V30}).
 * Decided when the workspace is created and never changed afterwards.
 */
public enum WorkspacePlacement {
  /** On the platform host, through qits-containers. Every admin workspace is DIRECT. */
  DIRECT,
  /**
   * In a WORKSPACE slot of an execution target (epic qits-1481): the row's container runs under a
   * qits-system lease ({@code Workspace.leaseId}), and its work volume {@code ws-<rowId>} stays on
   * the target that first held it.
   */
  TARGET
}
