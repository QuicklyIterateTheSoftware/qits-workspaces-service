package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The Claude usage an agent's transcript recorded (qits-2675, epic qits-2521): the assistant
 * messages newly counted from one stored transcript chunk — or one session of the boot backfill —
 * summed per {@code (sessionId, model)}. {@code messages} is how many distinct {@code message.id}s
 * went into the four token sums; a message is counted once per session, ever, so two events never
 * count the same message twice.
 *
 * <p>{@code agentId}, {@code workId} and {@code workspaceId} are the agent row's (the workspace it
 * is placed in, else the one it last started in; absent when neither), {@code projectId} its
 * repository's project (absent when qits-projects cannot name it). {@code occurredAt} is the newest
 * counted message's own {@code timestamp}, so a backfill is dated when the usage happened.
 */
public record AgentUsageRecorded(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String projectId,
    String sessionId,
    String model,
    int messages,
    long inputTokens,
    long outputTokens,
    long cacheCreationTokens,
    long cacheReadTokens,
    Instant occurredAt)
    implements QitsEvent {

  public AgentUsageRecorded {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentUsageRecorded(
      String agentId,
      String workId,
      String workspaceId,
      String projectId,
      String sessionId,
      String model,
      int messages,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      Instant occurredAt) {
    this(
        null, agentId, workId, workspaceId, projectId, sessionId, model, messages, inputTokens,
        outputTokens, cacheCreationTokens, cacheReadTokens, occurredAt);
  }
}
