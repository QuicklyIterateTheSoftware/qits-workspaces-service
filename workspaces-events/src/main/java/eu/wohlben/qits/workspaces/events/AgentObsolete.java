package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A release consumed an agent's wrapper branch, so the agent is OBSOLETE: its work item's work is
 * released. Nothing is removed yet — the cleanup sweep does that once the item is closed and the
 * agent idle ({@link AgentRemoved}, or {@link AgentCleanupDeferred} saying why not).
 *
 * <p>One event per agent, so a subscriber waiting on one agent selects it by {@code agentId}.
 * {@code branch} is the released wrapper branch, {@code version} the release that consumed it and
 * {@code commitSha} the released commit, as the release reported them; either of the last two is
 * absent when it did not say. {@code workspaceId} is absent for an agent that was never placed.
 * Everything else is {@link AgentPlaced}'s.
 */
public record AgentObsolete(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String branch,
    String version,
    String commitSha,
    Instant occurredAt)
    implements QitsEvent {

  public AgentObsolete {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentObsolete(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String branch,
      String version,
      String commitSha,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, branch, version, commitSha, occurredAt);
  }
}
