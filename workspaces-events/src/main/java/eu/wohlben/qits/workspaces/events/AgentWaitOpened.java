package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An agent declared a wait (epic qits-1681). Besides {@link AgentPlaced}'s four it carries {@code
 * projectId} — the agent's project, so a project's pages can follow its agents — and the wait:
 * {@code waitId}, its {@code label} (the agent's own words, or the selection in one line),
 * {@code eventName}, and the subject the selection names ({@code subjectKind} one of {@code
 * RELEASE_REQUEST}, {@code CI_RUN}, {@code WORK_ITEM}, {@code COMMENTS}, {@code OTHER};
 * {@code subjectRef} as written, absent for OTHER; {@code targetStates} the states it waits for,
 * empty when it names none). A desk's wait announces none of this.
 */
public record AgentWaitOpened(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String waitId,
    String label,
    String eventName,
    String subjectKind,
    String subjectRef,
    List<String> targetStates,
    Instant occurredAt)
    implements QitsEvent {

  public AgentWaitOpened {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    targetStates = targetStates == null ? List.of() : List.copyOf(targetStates);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentWaitOpened(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String projectId,
      String waitId,
      String label,
      String eventName,
      String subjectKind,
      String subjectRef,
      List<String> targetStates,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, waitId, label, eventName,
        subjectKind, subjectRef, targetStates, occurredAt);
  }
}
