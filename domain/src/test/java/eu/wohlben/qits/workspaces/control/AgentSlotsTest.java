package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.AgentSlots.Workspace;
import eu.wohlben.qits.workspaces.dto.AgentSlot;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link AgentSlots}, one rule a row (qits-2573). */
class AgentSlotsTest {

  private static final UUID LEASE = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  private static Workspace target(UUID lease, UUID target, WorkspaceRuntimeStatus status) {
    return new Workspace(WorkspacePlacement.TARGET, lease, target, status);
  }

  private static AgentSlot active(AgentRunState runState, Workspace workspace) {
    return AgentSlots.of(AgentState.ACTIVE, runState, true, workspace);
  }

  @Test
  void anAgentWithNoWorkspaceYetIsQueued() {
    assertEquals(
        AgentSlot.QUEUED, AgentSlots.of(AgentState.ACTIVE, AgentRunState.QUEUED, false, null));
  }

  @Test
  void anAgentWithNoWorkspaceThatIsNotQueuedHoldsNone() {
    assertEquals(
        AgentSlot.NONE, AgentSlots.of(AgentState.ACTIVE, AgentRunState.STOPPED, false, null));
  }

  @Test
  void theFirstAgentPlacedRunningInAQueuedWorkspaceIsQueued() {
    assertEquals(
        AgentSlot.QUEUED,
        active(AgentRunState.RUNNING, target(LEASE, null, WorkspaceRuntimeStatus.QUEUED)));
  }

  @Test
  void aTargetWorkspaceHoldingItsLeaseIsHeld() {
    for (WorkspaceRuntimeStatus status :
        new WorkspaceRuntimeStatus[] {
          WorkspaceRuntimeStatus.PROVISIONING, WorkspaceRuntimeStatus.RUNNING
        }) {
      assertEquals(
          AgentSlot.HELD, active(AgentRunState.RUNNING, target(LEASE, TARGET, status)), status.name());
    }
  }

  @Test
  void aYieldedAgentStillReadsHeldWhileItsWorkspaceHoldsTheLease() {
    assertEquals(
        AgentSlot.HELD,
        active(AgentRunState.YIELDED, target(LEASE, TARGET, WorkspaceRuntimeStatus.RUNNING)));
  }

  @Test
  void aDirectWorkspaceIsHeld() {
    assertEquals(
        AgentSlot.HELD,
        active(
            AgentRunState.RUNNING,
            new Workspace(WorkspacePlacement.DIRECT, null, null, WorkspaceRuntimeStatus.RUNNING)));
  }

  @Test
  void aFailedWorkspaceAStoppedOneWithNoLeaseAndAGoneOneHoldNone() {
    assertEquals(
        AgentSlot.NONE,
        active(AgentRunState.RUNNING, target(LEASE, TARGET, WorkspaceRuntimeStatus.FAILED)));
    assertEquals(
        AgentSlot.NONE,
        active(
            AgentRunState.RUNNING,
            new Workspace(WorkspacePlacement.DIRECT, null, null, WorkspaceRuntimeStatus.FAILED)));
    assertEquals(
        AgentSlot.NONE,
        active(AgentRunState.RUNNING, target(null, null, WorkspaceRuntimeStatus.STOPPED)));
    assertEquals(AgentSlot.NONE, active(AgentRunState.RUNNING, null));
  }

  @Test
  void aRemovedAgentHoldsNone() {
    assertEquals(
        AgentSlot.NONE,
        AgentSlots.of(
            AgentState.REMOVED,
            AgentRunState.RUNNING,
            true,
            target(LEASE, TARGET, WorkspaceRuntimeStatus.RUNNING)));
  }
}
