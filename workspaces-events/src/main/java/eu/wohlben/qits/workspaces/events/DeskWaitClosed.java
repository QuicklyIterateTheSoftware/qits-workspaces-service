package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A project front desk's declared wait closed (qits-1618): an event matched it, or its timeout
 * passed. The desk is a Claude session qits-projects runs, with no agent row here to be woken, so
 * this event is its wake — qits-projects consumes it and hands {@code turn} to the desk.
 *
 * <p>The field names are a contract with that consumer. {@code state} is {@link #MATCHED} or {@link
 * #TIMED_OUT}; a cancelled wait announces nothing. {@code matchedEventId} is the id of the event
 * that matched, absent on a timeout. {@code turn} is the sentence an agent's wake would carry,
 * verbatim, and {@code closedAt} is the close's ISO-8601 instant (also the envelope's {@code
 * occurredAt}).
 *
 * <p><b>Unlike the lifecycle events, it carries text somebody wrote</b>: the desk's own {@code
 * label}, and in {@code turn} the values the selection matched on, fenced as event-log data exactly
 * as an agent's wake fences them. Both are what the desk itself asked to be told.
 *
 * <p><b>Its id is fixed when the wait closes</b> and every re-publish reuses it, so the record has
 * no constructor that draws a fresh one.
 */
public record DeskWaitClosed(
    UUID eventId,
    String waitId,
    String projectId,
    String label,
    String state,
    String eventName,
    String matchedEventId,
    String turn,
    String closedAt,
    Instant occurredAt)
    implements QitsEvent {

  /** An event matched the wait's selection. */
  public static final String MATCHED = "MATCHED";

  /** The wait's timeout passed first. */
  public static final String TIMED_OUT = "TIMED_OUT";

  /** The facts of a closed wait, under the id stored when it closed; {@code occurredAt} is the close. */
  public DeskWaitClosed(
      UUID eventId,
      String waitId,
      String projectId,
      String label,
      String state,
      String eventName,
      String matchedEventId,
      String turn,
      Instant closedAt) {
    this(
        eventId,
        waitId,
        projectId,
        label,
        state,
        eventName,
        matchedEventId,
        turn,
        closedAt.toString(),
        closedAt);
  }
}
