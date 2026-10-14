package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A RUNNING agent that waited for input gave its workspace up to another agent that wanted it. Only
 * the row changed: its harness keeps running and it stays in {@code workspaceId}, where {@link
 * AgentResumed} makes it RUNNING again. Everything else is {@link AgentPlaced}'s.
 */
public record AgentYielded(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    Instant occurredAt)
    implements QitsEvent {

  public AgentYielded {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentYielded(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, occurredAt);
  }
}
