package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace's runtime status turned RUNNING: its container runs and, on a runner, its daemon
 * dialled home. Announced once per transition — an inventory that finds a RUNNING row running again
 * says nothing — for a DIRECT row and a RUNNER row alike. {@code workspaceId} is the row id and
 * {@code repositoryId} the wrapper repository; the conventions are {@link AgentPlaced}'s.
 */
public record WorkspaceStarted(
    UUID eventId,
    String workspaceId,
    String repositoryId,
    Instant occurredAt)
    implements QitsEvent {

  public WorkspaceStarted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public WorkspaceStarted(
      String workspaceId,
      String repositoryId,
      Instant occurredAt) {
    this(null, workspaceId, repositoryId, occurredAt);
  }
}
