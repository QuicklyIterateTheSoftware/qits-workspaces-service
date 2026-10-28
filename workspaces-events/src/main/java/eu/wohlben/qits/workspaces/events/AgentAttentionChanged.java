package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * What an ACTIVE agent needs a person for changed (epic qits-1681, "Attention"): {@link
 * AgentWaitOpened}'s five ids, the {@code code} ({@code WAIT_SUBJECT_PASSED}, {@code
 * BLOCKED_NO_DESK}, {@code IDLE_NO_WAIT} or {@code WAIT_OVERDUE}; absent once it needs nothing),
 * the one-line {@code reason} this service wrote, {@code since} (ISO-8601) and the {@code waitId} it
 * is about (codes {@code WAIT_SUBJECT_PASSED} and {@code WAIT_OVERDUE} only). Said when the code or
 * the wait changes, never when only the reason's minutes moved; an agent that stops being ACTIVE
 * clears without one.
 */
public record AgentAttentionChanged(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String code,
    String reason,
    String since,
    String waitId,
    Instant occurredAt)
    implements QitsEvent {

  public static final String WAIT_SUBJECT_PASSED = "WAIT_SUBJECT_PASSED";
  public static final String BLOCKED_NO_DESK = "BLOCKED_NO_DESK";
  public static final String IDLE_NO_WAIT = "IDLE_NO_WAIT";
  public static final String WAIT_OVERDUE = "WAIT_OVERDUE";

  public AgentAttentionChanged {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentAttentionChanged(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String projectId,
      String code,
      String reason,
      Instant since,
      String waitId,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, repositoryId, projectId, code, reason,
        since == null ? null : since.toString(), waitId, occurredAt);
  }
}
