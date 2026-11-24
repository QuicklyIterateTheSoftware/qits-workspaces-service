package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A project agent's declared wait closed (qits-2596, epic qits-2520): an event matched it, its
 * subject was looked up in a target state, its timeout passed, its subject can no longer reach what
 * it waits for, or the agent cancelled it. A project agent is the long-running agent
 * qits-agents-service runs for a project; it has no agent row here to be woken, so this event is
 * the fact its wake is made of — qits-agents-service renders the turn (epic decision 8), and this
 * service renders none.
 *
 * <p>{@code state} is {@link #MATCHED}, {@link #TIMED_OUT}, {@link #BROKEN} or {@link #CANCELLED};
 * a cancel is announced for the record, and the consumer ignores it. {@code eventName} is the event
 * the wait selected, absent for a timer; {@code matchedEventId} the event that matched, absent
 * unless an event did (a lookup's match names none); {@code reason} the platform's one line on why
 * it broke, absent unless BROKEN. {@code agentSubject} is the holder's token principal (e.g. {@code
 * tok-project-agent-qits-developer}), {@code projectId} its token's project, {@code workId} and
 * {@code workQualifiedId} the item the wait was held for.
 *
 * <p>It carries the holder's own {@code label}, text the agent wrote and asked to be told; nothing
 * any other party wrote. <b>Its id is fixed when the wait closes</b> and every re-publish reuses
 * it, so qits-events takes a repeat as the same event.
 */
public record ProjectAgentWaitClosed(
    UUID eventId,
    UUID waitId,
    String projectId,
    String agentSubject,
    String workId,
    String workQualifiedId,
    String label,
    String state,
    String eventName,
    String matchedEventId,
    String reason,
    Instant occurredAt)
    implements QitsEvent {

  /** An event matched the wait's selection, or a lookup found its subject in a target state. */
  public static final String MATCHED = "MATCHED";

  /** The wait's timeout (or a timer's time) passed first. */
  public static final String TIMED_OUT = "TIMED_OUT";

  /** The wait's subject can no longer reach what it waits for. */
  public static final String BROKEN = "BROKEN";

  /** The holder cancelled it. */
  public static final String CANCELLED = "CANCELLED";
}
