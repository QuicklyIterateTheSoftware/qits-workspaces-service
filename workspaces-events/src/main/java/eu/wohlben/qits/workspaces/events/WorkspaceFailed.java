package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace's runtime status turned FAILED, and {@code runtimeError} is what the row stores as
 * why: a launch the runner could not make, a daemon that never dialled home, a failed clone, a
 * container start that threw, or a row that could not be placed at all. Announced once per
 * transition. The conventions are {@link AgentPlaced}'s.
 */
public record WorkspaceFailed(
    UUID eventId,
    String workspaceId,
    String repositoryId,
    String runtimeError,
    Instant occurredAt)
    implements QitsEvent {

  public WorkspaceFailed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public WorkspaceFailed(
      String workspaceId,
      String repositoryId,
      String runtimeError,
      Instant occurredAt) {
    this(null, workspaceId, repositoryId, runtimeError, occurredAt);
  }
}
