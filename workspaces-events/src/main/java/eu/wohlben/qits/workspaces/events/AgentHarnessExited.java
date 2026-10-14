package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent's harness process ended in its workspace's container, as the workspace daemon reported
 * it. {@code commandId} is the harness the daemon ended ({@link AgentStarted}'s), {@code status} the
 * daemon's word for how, and {@code exitCode} the process's exit code, absent when it had none.
 *
 * <p><b>{@code stoppedBy} is one of four words</b>, a plain {@code String} on the wire: {@link
 * #DAEMON_REMOVE} — the daemon stopped it removing the agent; {@link #DAEMON_YIELD} — the daemon
 * stopped it for a yield; {@link #KILLED} — something outside the harness killed it (the OOM killer,
 * a signal); {@link #SELF} — it exited on its own. Everything else is {@link AgentPlaced}'s.
 */
public record AgentHarnessExited(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String commandId,
    String status,
    Integer exitCode,
    String stoppedBy,
    Instant occurredAt)
    implements QitsEvent {

  /** Stopped by the daemon removing the agent. */
  public static final String DAEMON_REMOVE = "DAEMON_REMOVE";

  /** Stopped by the daemon for a yield. */
  public static final String DAEMON_YIELD = "DAEMON_YIELD";

  /** Killed from outside the harness. */
  public static final String KILLED = "KILLED";

  /** Exited on its own. */
  public static final String SELF = "SELF";

  public AgentHarnessExited {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentHarnessExited(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String commandId,
      String status,
      Integer exitCode,
      String stoppedBy,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, commandId, status, exitCode, stoppedBy, occurredAt);
  }
}
