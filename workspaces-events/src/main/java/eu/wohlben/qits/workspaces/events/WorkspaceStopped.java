package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A workspace's container was stopped and its row reads STOPPED (qits-2265): a person's or the
 * desk's stop-container, or a lease that ended or exited under a live row. Announced once per
 * transition, by whichever write turned the row STOPPED — a door's stop is announced there, and the
 * lease's ENDED that follows finds the row STOPPED already and says nothing.
 *
 * <p>{@code stoppedBy} is the stopping caller's principal, absent for a lease that ended on its
 * own; {@code reason} is this service's one line (the reason it sent qits-system, or the lease's
 * {@code detail}); {@code affectedAgentIds} the live agents the slot hosted, oldest first, empty
 * when it hosted none — every RUNNING one of them is YIELDED by the stop. The conventions are
 * {@link AgentPlaced}'s.
 */
public record WorkspaceStopped(
    UUID eventId,
    String workspaceId,
    String repositoryId,
    String stoppedBy,
    String reason,
    List<String> affectedAgentIds,
    Instant occurredAt)
    implements QitsEvent {

  public WorkspaceStopped {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
    affectedAgentIds = affectedAgentIds == null ? List.of() : List.copyOf(affectedAgentIds);
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public WorkspaceStopped(
      String workspaceId,
      String repositoryId,
      String stoppedBy,
      String reason,
      List<String> affectedAgentIds,
      Instant occurredAt) {
    this(null, workspaceId, repositoryId, stoppedBy, reason, affectedAgentIds, occurredAt);
  }
}
