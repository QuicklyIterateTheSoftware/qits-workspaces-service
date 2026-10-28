package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent's wait left OPEN (epic qits-1681). {@link AgentWaitOpened}'s five ids, the {@code
 * waitId}, its {@code state} — {@link #MATCHED} (an event matched it; {@code matchedEventId} is that
 * event's id), {@link #TIMED_OUT} (its timeout passed) or {@link #CANCELLED} (the agent cancelled
 * it, or it is no longer ACTIVE) — and nothing else. A desk's wait announces {@code DeskWaitClosed}
 * instead.
 */
public record AgentWaitClosed(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String waitId,
    String state,
    String matchedEventId,
    Instant occurredAt)
    implements QitsEvent {

  /** An event matched the wait's selection. */
  public static final String MATCHED = "MATCHED";

  /** Its timeout passed first. */
  public static final String TIMED_OUT = "TIMED_OUT";

  /** The agent cancelled it, or the agent is no longer ACTIVE. */
  public static final String CANCELLED = "CANCELLED";

  public AgentWaitClosed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentWaitClosed(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String projectId,
      String waitId,
      String state,
      String matchedEventId,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, waitId, state, matchedEventId,
        occurredAt);
  }
}
