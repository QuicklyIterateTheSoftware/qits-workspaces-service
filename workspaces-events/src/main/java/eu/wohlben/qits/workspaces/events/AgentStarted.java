package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A workspace daemon accepted an agent's start: the agent holds a harness there now.
 *
 * <p>{@code launched} and {@code resumed} are the daemon's own answer. {@code launched} false is a
 * start that found the harness still running (a restart of this service, a reconnect of the daemon)
 * and attached to it; {@code resumed} true is a launch that resumed the agent's earlier session.
 * {@code commandId} is the daemon's id for the harness, the one {@link AgentHarnessExited} names
 * when it ends. Everything else is {@link AgentPlaced}'s.
 */
public record AgentStarted(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String commandId,
    boolean launched,
    boolean resumed,
    Instant occurredAt)
    implements QitsEvent {

  public AgentStarted {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentStarted(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String commandId,
      boolean launched,
      boolean resumed,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, commandId, launched, resumed, occurredAt);
  }
}
