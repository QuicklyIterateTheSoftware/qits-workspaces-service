package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.AgentPhases.Reading;
import eu.wohlben.qits.workspaces.control.AgentPhases.Slot;
import eu.wohlben.qits.workspaces.dto.AgentPhase;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** {@link AgentPhases} (qits-2007, qits-2515): one test per rule, first match wins. */
class AgentPhasesTest {

  static final Instant QUEUED_AT = Instant.parse("2026-11-09T10:00:00Z");
  static final Instant SLOT_QUEUED_AT = Instant.parse("2026-11-09T10:01:00Z");
  static final Instant PLACED_AT = Instant.parse("2026-11-09T10:02:00Z");
  static final Instant STARTED_AT = Instant.parse("2026-11-09T10:03:00Z");

  static final String LEASE_DETAIL = "no target has memory for a 6g WORKSPACE lease";

  static final Slot RUNNING_SLOT = new Slot(WorkspaceRuntimeStatus.RUNNING, null, null);

  private static Reading running(Slot slot, boolean connected, Instant placed, Instant started) {
    return running(slot, connected, placed, started, false, null);
  }

  private static Reading running(
      Slot slot,
      boolean connected,
      Instant placed,
      Instant started,
      boolean startedHere,
      AgentActivityState activity) {
    return AgentPhases.of(
        AgentState.ACTIVE,
        AgentRunState.RUNNING,
        QUEUED_AT,
        slot,
        connected,
        placed,
        started,
        startedHere,
        activity);
  }

  @Test
  void aRemovedAgentHasNoPhaseWhateverItsRunState() {
    assertEquals(
        new Reading(null, null, null),
        AgentPhases.of(
            AgentState.REMOVED,
            AgentRunState.RUNNING,
            QUEUED_AT,
            RUNNING_SLOT,
            true,
            PLACED_AT,
            STARTED_AT,
            true,
            AgentActivityState.BUSY));
  }

  @ParameterizedTest
  @EnumSource(
      value = AgentRunState.class,
      names = {"YIELDED", "STOPPED"})
  void anAgentNeitherQueuedNorRunningHasNoPhase(AgentRunState runState) {
    assertEquals(
        new Reading(null, null, null),
        AgentPhases.of(
            AgentState.ACTIVE,
            runState,
            QUEUED_AT,
            RUNNING_SLOT,
            true,
            PLACED_AT,
            STARTED_AT,
            true,
            AgentActivityState.IDLE));
  }

  @Test
  void anObsoleteAgentStillHasItsPhase() {
    assertEquals(
        new Reading(AgentPhase.READY, STARTED_AT, null),
        AgentPhases.of(
            AgentState.OBSOLETE,
            AgentRunState.RUNNING,
            QUEUED_AT,
            RUNNING_SLOT,
            true,
            PLACED_AT,
            STARTED_AT,
            true,
            null));
  }

  @Test
  void aQueuedAgentIsQueuedSinceItWasQueued() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, QUEUED_AT, null),
        AgentPhases.of(
            AgentState.ACTIVE, AgentRunState.QUEUED, QUEUED_AT, null, false, null, null, false, null));
  }

  @Test
  void anAgentOnAQueuedSlotIsQueuedSinceTheSlotWas() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, SLOT_QUEUED_AT, null),
        running(new Slot(WorkspaceRuntimeStatus.QUEUED, SLOT_QUEUED_AT, null), false, PLACED_AT, null));
  }

  @Test
  void anAgentOnAQueuedSlotWithNoQueuedTimeIsQueuedSinceItWasPlaced() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, PLACED_AT, null),
        running(new Slot(WorkspaceRuntimeStatus.QUEUED, null, null), false, PLACED_AT, null));
  }

  @ParameterizedTest
  @EnumSource(
      value = WorkspaceRuntimeStatus.class,
      names = {"PROVISIONING", "STOPPED", "FAILED"})
  void anAgentOnASlotThatDoesNotRunIsProvisioningSinceItWasPlaced(WorkspaceRuntimeStatus status) {
    assertEquals(
        new Reading(AgentPhase.PROVISIONING, PLACED_AT, null),
        running(new Slot(status, null, null), true, PLACED_AT, STARTED_AT));
  }

  @Test
  void anAgentWhoseSlotIsGoneIsProvisioning() {
    assertEquals(
        new Reading(AgentPhase.PROVISIONING, PLACED_AT, null), running(null, false, PLACED_AT, null));
  }

  @Test
  void anAgentOnARunningSlotWhoseDaemonIsNotConnectedIsProvisioning() {
    assertEquals(
        new Reading(AgentPhase.PROVISIONING, PLACED_AT, null),
        running(RUNNING_SLOT, false, PLACED_AT, STARTED_AT, true, AgentActivityState.IDLE));
  }

  @Test
  void aPlacedAgentNotStartedYetIsStartingSinceItWasPlaced() {
    assertEquals(
        new Reading(AgentPhase.STARTING, PLACED_AT, null), running(RUNNING_SLOT, true, PLACED_AT, null));
  }

  @Test
  void aStartedAgentIsReadySinceItWasStarted() {
    assertEquals(
        new Reading(AgentPhase.READY, STARTED_AT, null),
        running(RUNNING_SLOT, true, PLACED_AT, STARTED_AT, false, null));
  }

  @Test
  void aLegacyAgentThisProcessStartedIsReadyWithNoSince() {
    assertEquals(
        new Reading(AgentPhase.READY, null, null), running(RUNNING_SLOT, true, null, null, true, null));
  }

  @Test
  void aLegacyAgentWithAReportedActivityIsReadyWithNoSince() {
    assertEquals(
        new Reading(AgentPhase.READY, null, null),
        running(RUNNING_SLOT, true, null, null, false, AgentActivityState.IDLE));
  }

  @Test
  void aLegacyAgentNotStartedAndSilentIsStartingWithNoSince() {
    assertEquals(
        new Reading(AgentPhase.STARTING, null, null), running(RUNNING_SLOT, true, null, null, false, null));
  }

  @Test
  void aLegacyAgentWithAStartTimeIsReadySinceThen() {
    assertEquals(
        new Reading(AgentPhase.READY, STARTED_AT, null),
        running(RUNNING_SLOT, true, null, STARTED_AT, false, null));
  }

  @Test
  void anAgentOnAQueuedSlotCarriesTheSlotsLeaseDetail() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, SLOT_QUEUED_AT, LEASE_DETAIL),
        running(
            new Slot(WorkspaceRuntimeStatus.QUEUED, SLOT_QUEUED_AT, LEASE_DETAIL),
            false,
            PLACED_AT,
            null));
  }

  @Test
  void aBlankSlotErrorIsNoDetail() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, SLOT_QUEUED_AT, null),
        running(new Slot(WorkspaceRuntimeStatus.QUEUED, SLOT_QUEUED_AT, "  "), false, PLACED_AT, null));
  }

  @Test
  void anAgentProvisioningOnASlotCarriesItsError() {
    assertEquals(
        new Reading(AgentPhase.PROVISIONING, PLACED_AT, "pulling qits/workspace"),
        running(
            new Slot(WorkspaceRuntimeStatus.PROVISIONING, null, "pulling qits/workspace"),
            true,
            PLACED_AT,
            null));
  }

  @Test
  void aReadyAgentCarriesNoDetailWhateverItsSlotSays() {
    assertEquals(
        new Reading(AgentPhase.READY, STARTED_AT, null),
        running(
            new Slot(WorkspaceRuntimeStatus.RUNNING, null, LEASE_DETAIL),
            true,
            PLACED_AT,
            STARTED_AT));
  }

  @Test
  void aStartingAgentCarriesNoDetailWhateverItsSlotSays() {
    assertEquals(
        new Reading(AgentPhase.STARTING, PLACED_AT, null),
        running(new Slot(WorkspaceRuntimeStatus.RUNNING, null, LEASE_DETAIL), true, PLACED_AT, null));
  }

  @Test
  void aQueuedAgentWithNoSlotCarriesNoDetail() {
    assertEquals(
        new Reading(AgentPhase.QUEUED, QUEUED_AT, null),
        AgentPhases.of(
            AgentState.ACTIVE, AgentRunState.QUEUED, QUEUED_AT, null, false, null, null, false, null));
  }
}
