package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent's wait left OPEN (epic qits-1681). {@link AgentWaitOpened}'s five ids, the {@code
 * waitId}, its {@code state} — {@link #MATCHED} (an event matched it; {@code matchedEventId} is that
 * event's id), {@link #TIMED_OUT} (its timeout, or the platform's ceiling, passed), {@link #BROKEN}
 * (qits-1961: its subject, looked up, can no longer reach what it waits for) or {@link #CANCELLED}
 * (the agent cancelled it, or it is no longer ACTIVE). A desk's wait announces {@code
 * DeskWaitClosed} instead.
 *
 * <p>{@code label} and {@code reason} (qits-1961) are set for {@link #BROKEN} only, and absent
 * otherwise: the wait's label and the platform's one line saying why it broke ({@code
 * broken_reason}), for a desk to list without reading the wait. The label is the agent's own text.
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
    String label,
    String reason,
    Instant occurredAt)
    implements QitsEvent {

  /** An event matched the wait's selection. */
  public static final String MATCHED = "MATCHED";

  /** Its timeout passed first. */
  public static final String TIMED_OUT = "TIMED_OUT";

  /** Its subject can no longer reach what it waits for (qits-1961); {@code reason} says why. */
  public static final String BROKEN = "BROKEN";

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
      String label,
      String reason,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, waitId, state, matchedEventId,
        label, reason, occurredAt);
  }

  /** A close that is not {@link #BROKEN}: no label and no reason. */
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
        agentId, workId, workspaceId, repositoryId, projectId, waitId, state, matchedEventId, null,
        null, occurredAt);
  }
}
