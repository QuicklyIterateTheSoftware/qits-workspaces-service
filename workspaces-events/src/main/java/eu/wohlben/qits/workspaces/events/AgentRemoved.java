package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent is REMOVED: the row stays as history, its credential and prompt draft are gone. The last
 * event about an agent, announced once — only by the write that flipped the row.
 *
 * <p><b>{@code reason} is one of five words</b>, a plain {@code String} on the wire:
 *
 * <ul>
 *   <li>{@link #OBSOLETE_NEVER_PLACED} — obsolete and closed, and it never had a workspace.
 *   <li>{@link #OBSOLETE_WORKSPACE_GONE} — obsolete and closed, and its workspace is gone.
 *   <li>{@link #UNKNOWN_TO_DAEMON} — its daemon no longer knows it: a removal whose answer never
 *       arrived, or a daemon that lost it.
 *   <li>{@link #RELEASED_IDLE_CLEAN} — released, closed, idle, and its worktrees clean and pushed.
 *   <li>{@link #DISCARDED} — a person discarded it.
 * </ul>
 *
 * <p><b>{@code harnessStopped}</b> is true exactly when the daemon answered this removal OK — its
 * harness and worktrees were removed by it. False says nothing was confirmed stopped: there was no
 * daemon to ask, the daemon no longer knew the agent, or a person's forced discard went past an
 * unreachable one. {@code detail} is the sentence the service logs. Everything else is {@link
 * AgentPlaced}'s.
 */
public record AgentRemoved(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String reason,
    boolean harnessStopped,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  /** Obsolete, closed, and never placed. */
  public static final String OBSOLETE_NEVER_PLACED = "OBSOLETE_NEVER_PLACED";

  /** Obsolete, closed, and its workspace is gone. */
  public static final String OBSOLETE_WORKSPACE_GONE = "OBSOLETE_WORKSPACE_GONE";

  /** Its daemon no longer knows it. */
  public static final String UNKNOWN_TO_DAEMON = "UNKNOWN_TO_DAEMON";

  /** Released, idle and clean. */
  public static final String RELEASED_IDLE_CLEAN = "RELEASED_IDLE_CLEAN";

  /** Discarded by a person. */
  public static final String DISCARDED = "DISCARDED";

  public AgentRemoved {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentRemoved(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String reason,
      boolean harnessStopped,
      String detail,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, reason, harnessStopped, detail, occurredAt);
  }
}
