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
 *
 * <p><b>A claude harness's remote-control session</b> (qits-1779): {@code remoteSession} is the
 * claude.ai Remote Control session ({@code cse_…}) the harness's debug log last named, and {@code
 * remoteArchive} the status of the last archive of that session the log records, as the log wrote
 * it ({@code "200"}, {@code "401"}, …), or {@link #NO_ARCHIVE} when the log records no archive
 * attempt at all. Both ride {@code AgentRemoved#discardedBy}'s rule — absent keys, not nulls, under
 * {@code CanonicalJson}'s {@code NON_NULL} inclusion — when the harness ran without remote control,
 * or when the daemon that sent the frame is older than its capability 14 and never names them at
 * all; an old consumer that has never seen the keys ignores them exactly as it ignores any other
 * absent field.
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
    String remoteSession,
    String remoteArchive,
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

  /**
   * {@link #remoteArchive} for a remote-control log that records no archive attempt at all — the
   * same word {@code AgentHarnessExit.NO_ARCHIVE} carries on the daemon's own frame.
   */
  public static final String NO_ARCHIVE = "NONE";

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
      String remoteSession,
      String remoteArchive,
      Instant occurredAt) {
    this(
        null,
        agentId,
        workId,
        workspaceId,
        repositoryId,
        commandId,
        status,
        exitCode,
        stoppedBy,
        remoteSession,
        remoteArchive,
        occurredAt);
  }

  /**
   * The constructor a publisher used before qits-1779: no remote-control outcome on the wire at
   * all, {@code remoteSession} and {@code remoteArchive} both absent.
   */
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
    this(agentId, workId, workspaceId, repositoryId, commandId, status, exitCode, stoppedBy, null, null, occurredAt);
  }
}
