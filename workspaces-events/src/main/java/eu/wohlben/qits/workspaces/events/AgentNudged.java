package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A dispatched agent ended its turn with no open wait, stayed quiet long enough, and was handed the
 * nudge as its next turn (qits-1460). {@code nudgeCount} is how many nudges in a row it has had,
 * this one included; at the limit it is nudged no more until it hears another turn. Everything
 * else is {@link AgentPlaced}'s.
 *
 * <p><b>{@code reason} is one of two words</b> (qits-1511): {@link #IDLE} — no block on its item;
 * {@link #SELF_BLOCKED} — it blocked its item itself and declared no wait.
 */
public record AgentNudged(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    int nudgeCount,
    String reason,
    Instant occurredAt)
    implements QitsEvent {

  /** No block on the item: carry on, declare a wait, or block it. */
  public static final String IDLE = "IDLE";

  /** The agent blocked its item itself and declared no wait: declare one, or leave the block. */
  public static final String SELF_BLOCKED = "SELF_BLOCKED";

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
      String reason,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, nudgeCount, reason, occurredAt);
  }
}
