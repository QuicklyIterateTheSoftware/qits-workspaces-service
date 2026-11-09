package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A person or the desk stopped the agent (qits-1970, {@code POST /agents/{id}/stop}): its row is
 * STOPPED, which the scheduler never places, resumes, nudges or drives, and a placed agent's
 * harness was asked to stop on its daemon. Only a prompt to the agent or a restart starts it again;
 * a turn relayed by qits-projects waits on the row. Announced once per stop — stopping a STOPPED
 * agent says nothing.
 *
 * <p><b>{@code by}</b> is the stopping caller's principal, null (an absent key) when it is unknown.
 * <b>{@code previous}</b> is the run state the agent had before, as its word: {@code RUNNING},
 * {@code YIELDED} or {@code QUEUED}. Everything else is {@link AgentPlaced}'s.
 */
public record AgentStopped(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String by,
    String previous,
    Instant occurredAt)
    implements QitsEvent {

  public AgentStopped {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentStopped(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String by,
      String previous,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, by, previous, occurredAt);
  }
}
