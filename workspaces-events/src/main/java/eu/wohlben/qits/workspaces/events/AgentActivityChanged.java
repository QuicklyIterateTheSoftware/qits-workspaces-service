package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent started or stopped waiting for input (epic qits-1681): the flip of its {@code
 * waitingSince} that is relayed to qits-projects, and never any other frame. {@link
 * AgentWaitOpened}'s five ids, the {@code activity} its daemon reports ({@code IDLE}, {@code BUSY},
 * {@code WAITING} or {@code ENDED}), {@code waitingSince} (ISO-8601, absent when it stopped
 * waiting) and {@code at}, the frame's ISO-8601 instant.
 */
public record AgentActivityChanged(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String activity,
    String waitingSince,
    String at,
    Instant occurredAt)
    implements QitsEvent {

  public AgentActivityChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of; {@code at} is the envelope's too. */
  public AgentActivityChanged(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String projectId,
      String activity,
      Instant waitingSince,
      Instant at) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, activity,
        waitingSince == null ? null : waitingSince.toString(), at.toString(), at);
  }
}
