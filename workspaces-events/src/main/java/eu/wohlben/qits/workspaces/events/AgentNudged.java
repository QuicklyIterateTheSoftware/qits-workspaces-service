package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A dispatched agent ended its turn with no open wait and no block on its item, stayed quiet for
 * {@code qits.workspace.agent.nudge-after}, and was handed the nudge as its next turn (qits-1460).
 * {@code nudgeCount} is how many nudges in a row it has had, this one included; at the limit it is
 * nudged no more until it hears another turn. Everything else is {@link AgentPlaced}'s.
 */
public record AgentNudged(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    int nudgeCount,
    Instant occurredAt)
    implements QitsEvent {

  public AgentNudged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentNudged(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      int nudgeCount,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, nudgeCount, occurredAt);
  }
}
