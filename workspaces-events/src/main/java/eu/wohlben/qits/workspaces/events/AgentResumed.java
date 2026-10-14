package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A YIELDED agent is RUNNING again in {@code workspaceId}.
 *
 * <p><b>{@code reason} is one of two words</b>: {@link #TURN_PENDING} — it has a turn to hear (a
 * delivery, a woken wait, a cleanup prompt), and resumes before anyone new is placed; {@link
 * #WORKS_AGAIN} — its daemon reported it working (a person typed into its terminal). Everything
 * else is {@link AgentPlaced}'s.
 */
public record AgentResumed(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String reason,
    Instant occurredAt)
    implements QitsEvent {

  /** A turn waits for it. */
  public static final String TURN_PENDING = "TURN_PENDING";

  /** Its daemon reported it working. */
  public static final String WORKS_AGAIN = "WORKS_AGAIN";

  public AgentResumed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentResumed(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String reason,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, reason, occurredAt);
  }
}
