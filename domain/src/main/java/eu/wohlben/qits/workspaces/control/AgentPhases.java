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
 * <p>A reading also carries a {@code detail} (qits-2515): why it waits in that phase. It is the
 * slot's {@code runtimeError} — where a waiting lease's reason lands, e.g. {@code no target has
 * memory for a 6g WORKSPACE lease} — when the phase is QUEUED because the slot is QUEUED, or
 * PROVISIONING on a slot; null (and null for blank text) in every other case: an agent QUEUED with
 * no slot, PROVISIONING with none, STARTING, READY, or no phase.
 *
 * <p>An agent placed before {@code V39} has neither time. Its phase falls back to what this process
 * knows: started here, or a reported activity, reads READY (with no since); neither reads STARTING.
 * So a live agent reads READY after a deploy rather than STARTING until its next placement.
 */
public final class AgentPhases {

  private AgentPhases() {}

  /**
   * A phase, since when, and why it waits there; all null for an agent with no phase.
   *
   * @param detail the slot's {@code runtimeError} while it is QUEUED or provisioning; else null
   */
  public record Reading(AgentPhase phase, Instant since, String detail) {
    static final Reading NONE = new Reading(null, null, null);
  }

  /**
   * The facts of the agent's slot the rules read; null when it names none (or it is gone).
   *
   * @param runtimeError the slot's runtime error, a waiting lease's detail among them; or null
   */
  public record Slot(WorkspaceRuntimeStatus runtimeStatus, Instant queuedAt, String runtimeError) {}

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
      return new Reading(AgentPhase.QUEUED, queuedAt, null);
    }
    if (slot != null && slot.runtimeStatus() == WorkspaceRuntimeStatus.QUEUED) {
      return new Reading(
          AgentPhase.QUEUED, slot.queuedAt() != null ? slot.queuedAt() : placedAt, detail(slot));
    }
    if (slot == null || slot.runtimeStatus() != WorkspaceRuntimeStatus.RUNNING || !daemonConnected) {
      return new Reading(AgentPhase.PROVISIONING, placedAt, slot == null ? null : detail(slot));
    }
    if (startedAt != null) {
      return new Reading(AgentPhase.READY, startedAt, null);
    }
    if (placedAt == null && (startedHere || activity != null)) {
      // Placed before V39: what this process knows of it is all there is.
      return new Reading(AgentPhase.READY, null, null);
    }
    return new Reading(AgentPhase.STARTING, placedAt, null);
  }

  private static String detail(Slot slot) {
    String error = slot.runtimeError();
    return error == null || error.isBlank() ? null : error;
  }
}
