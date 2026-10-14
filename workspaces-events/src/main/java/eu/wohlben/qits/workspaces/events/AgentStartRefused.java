package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent was not started, and {@code detail} says why: the daemon's refusal, verbatim, or the
 * sentence this service stores when the agent's workspace never came up. Announced once per
 * distinct detail, not every scheduler pass; an accepted start ({@link AgentStarted}) ends the
 * refusal. Everything else is {@link AgentPlaced}'s.
 */
public record AgentStartRefused(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  public AgentStartRefused {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentStartRefused(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String detail,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, detail, occurredAt);
  }
}
