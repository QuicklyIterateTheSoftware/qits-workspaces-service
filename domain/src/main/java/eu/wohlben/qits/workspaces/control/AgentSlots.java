package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.AgentSlot;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import java.util.UUID;

/**
 * <b>Whether an agent's workspace holds its lease</b> (qits-2573), computed on read: pure, so every
 * rule is one row of its unit test. First match wins:
 *
 * <ol>
 *   <li>REMOVED: {@link AgentSlot#NONE}.
 *   <li>No workspace named: {@link AgentSlot#QUEUED} while its {@code runState} is QUEUED, else
 *       {@link AgentSlot#NONE}.
 *   <li>Its workspace gone (resolved): {@link AgentSlot#NONE}.
 *   <li>Its workspace's {@code runtimeStatus} QUEUED: {@link AgentSlot#QUEUED}. This is the first
 *       agent of a new TARGET workspace, which {@code placeQueued} sets RUNNING at once while the
 *       lease still waits.
 *   <li>Its workspace FAILED: {@link AgentSlot#NONE}.
 *   <li>A DIRECT workspace, or a TARGET one with {@code leaseId} and {@code targetId} set: {@link
 *       AgentSlot#HELD}.
 *   <li>Otherwise (a TARGET row with no lease, STOPPED): {@link AgentSlot#NONE}.
 * </ol>
 *
 * <p>A YIELDED or STOPPED agent in a workspace that holds its lease reads HELD: the slot is the
 * workspace's, and the workspace still holds it.
 */
public final class AgentSlots {

  private AgentSlots() {}

  /** The facts of the agent's workspace the rules read; null when it names none or it is gone. */
  public record Workspace(
      WorkspacePlacement placement,
      UUID leaseId,
      UUID targetId,
      WorkspaceRuntimeStatus runtimeStatus) {}

  /**
   * @param namesWorkspace whether the agent row names a workspace ({@code workspaceId} set)
   * @param workspace that workspace while it is ACTIVE, or null
   */
  public static AgentSlot of(
      AgentState state, AgentRunState runState, boolean namesWorkspace, Workspace workspace) {
    if (state == AgentState.REMOVED) {
      return AgentSlot.NONE;
    }
    if (!namesWorkspace) {
      return runState == AgentRunState.QUEUED ? AgentSlot.QUEUED : AgentSlot.NONE;
    }
    if (workspace == null) {
      return AgentSlot.NONE;
    }
    if (workspace.runtimeStatus() == WorkspaceRuntimeStatus.QUEUED) {
      return AgentSlot.QUEUED;
    }
    if (workspace.runtimeStatus() == WorkspaceRuntimeStatus.FAILED) {
      return AgentSlot.NONE;
    }
    if (workspace.placement() == WorkspacePlacement.DIRECT) {
      return AgentSlot.HELD;
    }
    if (workspace.placement() == WorkspacePlacement.TARGET
        && workspace.leaseId() != null
        && workspace.targetId() != null) {
      return AgentSlot.HELD;
    }
    return AgentSlot.NONE;
  }
}
