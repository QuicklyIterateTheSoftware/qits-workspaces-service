package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A placed agent was taken out of its workspace and is QUEUED again, waiting for another one.
 * {@code workspaceId} is the workspace it left.
 *
 * <p><b>{@code reason} is one of two words</b>, a plain {@code String} on the wire:
 *
 * <ul>
 *   <li>{@link #WORKSPACE_FAILED} — the workspace never came up (FAILED, no daemon) and the agent
 *       was never started there, so nothing of it is in that volume. A second failure in a row waits
 *       before the agent is placed again, and {@code detail} says until when.
 *   <li>{@link #WORKSPACE_GONE} — the workspace was removed or resolved under the agent.
 * </ul>
 *
 * <p>{@code detail} is this service's sentence. Everything else is {@link AgentPlaced}'s.
 */
public record AgentRequeued(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String reason,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  /** The workspace never came up. */
  public static final String WORKSPACE_FAILED = "WORKSPACE_FAILED";

  /** The workspace is no longer ACTIVE. */
  public static final String WORKSPACE_GONE = "WORKSPACE_GONE";

  public AgentRequeued {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentRequeued(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String reason,
      String detail,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, reason, detail, occurredAt);
  }
}
