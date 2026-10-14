package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace was removed — container, volume and credential — and its row resolved.
 *
 * <p><b>{@code reason} is one word today</b>: {@link #NO_AGENT_LEFT}, the scheduler retiring a slot
 * whose last agent was removed (or that no agent ever took). The conventions are {@link
 * AgentPlaced}'s.
 */
public record WorkspaceRemoved(
    UUID eventId,
    String workspaceId,
    String repositoryId,
    String reason,
    Instant occurredAt)
    implements QitsEvent {

  /** Its last agent was removed. */
  public static final String NO_AGENT_LEFT = "NO_AGENT_LEFT";

  public WorkspaceRemoved {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public WorkspaceRemoved(
      String workspaceId,
      String repositoryId,
      String reason,
      Instant occurredAt) {
    this(null, workspaceId, repositoryId, reason, occurredAt);
  }
}
