package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.AgentPhase;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import java.time.Instant;

/**
 * <b>An agent's lifecycle phase</b> (qits-2007), computed on read: pure, so every rule is one row of
 * its unit test. {@code runState} RUNNING only means the agent holds its slot and {@code activity}
 * is null until its harness reports, so neither says whether an agent is still coming up; this
 * does, and changes neither. First match wins:
 *
 * <ol>
 *   <li>REMOVED, or a run state other than QUEUED or RUNNING: no phase.
 *   <li>QUEUED with no slot, or on a slot that is itself QUEUED: {@link AgentPhase#QUEUED}, since
 *       the slot's {@code queuedAt} (on a slot) or the agent's own queued time.
 *   <li>Its slot not RUNNING, or its daemon not connected: {@link AgentPhase#PROVISIONING}, since it
 *       was placed.
 *   <li>Not started there: {@link AgentPhase#STARTING}, since it was placed.
 *   <li>Otherwise {@link AgentPhase#READY}, since its harness was started.
 * </ol>
 *
 * <p>An agent placed before {@code V39} has neither time. Its phase falls back to what this process
 * knows: started here, or a reported activity, reads READY (with no since); neither reads STARTING.
 * So a live agent reads READY after a deploy rather than STARTING until its next placement.
 */
public final class AgentPhases {

  private AgentPhases() {}

  /** A phase and since when; both null for an agent with no phase. */
  public record Reading(AgentPhase phase, Instant since) {
    static final Reading NONE = new Reading(null, null);
  }

  /** The two facts of the agent's slot the rules read; null when it names none (or it is gone). */
  public record Slot(WorkspaceRuntimeStatus runtimeStatus, Instant queuedAt) {}

  /**
   * @param queuedAt when the agent last became QUEUED (its {@code createdAt} where it never said)
   * @param slot the agent's slot, or null
   * @param daemonConnected whether the slot's daemon holds an open control socket
   * @param placedAt when it was placed in the slot; null before {@code V39}
   * @param startedAt when its harness was started there; null until then
   * @param startedHere whether this process started it on the daemon's current connection
   * @param activity what its daemon reports for it; null when nothing
   */
  public static Reading of(
      AgentState state,
      AgentRunState runState,
      Instant queuedAt,
      Slot slot,
      boolean daemonConnected,
      Instant placedAt,
      Instant startedAt,
      boolean startedHere,
      AgentActivityState activity) {
    if (state == AgentState.REMOVED
        || (runState != AgentRunState.QUEUED && runState != AgentRunState.RUNNING)) {
      return Reading.NONE;
    }
    if (runState == AgentRunState.QUEUED) {
      return new Reading(AgentPhase.QUEUED, queuedAt);
    }
    if (slot != null && slot.runtimeStatus() == WorkspaceRuntimeStatus.QUEUED) {
      return new Reading(AgentPhase.QUEUED, slot.queuedAt() != null ? slot.queuedAt() : placedAt);
    }
    if (slot == null || slot.runtimeStatus() != WorkspaceRuntimeStatus.RUNNING || !daemonConnected) {
      return new Reading(AgentPhase.PROVISIONING, placedAt);
    }
    if (startedAt != null) {
      return new Reading(AgentPhase.READY, startedAt);
    }
    if (placedAt == null && (startedHere || activity != null)) {
      // Placed before V39: what this process knows of it is all there is.
      return new Reading(AgentPhase.READY, null);
    }
    return new Reading(AgentPhase.STARTING, placedAt);
  }
}
