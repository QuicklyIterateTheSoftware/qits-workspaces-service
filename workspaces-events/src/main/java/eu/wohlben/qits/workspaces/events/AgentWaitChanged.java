package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * What an agent's OPEN wait waits on moved (epic qits-1681): an event it listens to, or a lookup of
 * the subject, put the subject in another state. {@link AgentWaitOpened}'s five ids, the {@code
 * waitId}, the subject ({@code subjectKind}, {@code subjectRef}, {@code subjectQualifiedId} once
 * known), its new {@code subjectState} and {@code subjectStateAt}, the ISO-8601 instant that state
 * was seen to begin. Said only when the state changed.
 */
public record AgentWaitChanged(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String waitId,
    String subjectKind,
    String subjectRef,
    String subjectQualifiedId,
    String subjectState,
    String subjectStateAt,
    Instant occurredAt)
    implements QitsEvent {

  public AgentWaitChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentWaitChanged(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String projectId,
      String waitId,
      String subjectKind,
      String subjectRef,
      String subjectQualifiedId,
      String subjectState,
      Instant subjectStateAt,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, waitId, subjectKind, subjectRef,
        subjectQualifiedId, subjectState, subjectStateAt == null ? null : subjectStateAt.toString(),
        occurredAt);
  }
}
