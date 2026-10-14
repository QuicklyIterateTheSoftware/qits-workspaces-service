package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The cleanup sweep left an OBSOLETE agent alone this pass, and {@code reason} says why. Announced
 * once per agent and reason word — the sweep runs every few seconds — and again only when the
 * reason changes; a changing detail under the same word (a workspace's runtime status, the item's
 * status) is not a new deferral.
 *
 * <p><b>{@code reason} is one of these words</b>, a plain {@code String} on the wire:
 *
 * <ul>
 *   <li>{@link #ITEM_NOT_CLOSED} — the work item is not VERIFIED, DONE or DROPPED.
 *   <li>{@link #RECENTLY_ACTIVE} — the agent worked within the idle window.
 *   <li>{@link #TURN_PENDING} — a turn is still to be said to it.
 *   <li>{@link #HARNESS_BUSY} — its harness is busy with a turn.
 *   <li>{@link #DAEMON_NOT_CONNECTED} — its workspace's daemon is not connected; a STOPPED workspace
 *       is started so the check can run.
 *   <li>{@link #NO_DAEMON_CLIENT} — this service has no daemon client configured.
 *   <li>{@link #CHECK_UNANSWERED} — the cleanup check went unanswered.
 *   <li>{@link #BUSY_IN_BACKGROUND} — the harness has background work running, found by the check
 *       or by the daemon refusing the removal.
 *   <li>{@link #REMOVAL_UNANSWERED} — the removal was answered with neither OK nor a refusal.
 *   <li>{@link #LEFTOVER_WORK_PROMPTED} — it holds leftover work, was prompted to clean up and has
 *       not worked since.
 * </ul>
 *
 * <p>{@code detail} is the sentence the service logs. Everything else is {@link AgentPlaced}'s.
 */
public record AgentCleanupDeferred(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String reason,
    String detail,
    Instant occurredAt)
    implements QitsEvent {

  /** The work item is not closed. */
  public static final String ITEM_NOT_CLOSED = "ITEM_NOT_CLOSED";

  /** Active within the idle window. */
  public static final String RECENTLY_ACTIVE = "RECENTLY_ACTIVE";

  /** A turn is still to be said. */
  public static final String TURN_PENDING = "TURN_PENDING";

  /** Busy with a turn. */
  public static final String HARNESS_BUSY = "HARNESS_BUSY";

  /** The workspace daemon is not connected. */
  public static final String DAEMON_NOT_CONNECTED = "DAEMON_NOT_CONNECTED";

  /** No daemon client is configured. */
  public static final String NO_DAEMON_CLIENT = "NO_DAEMON_CLIENT";

  /** The cleanup check went unanswered. */
  public static final String CHECK_UNANSWERED = "CHECK_UNANSWERED";

  /** Background work in the harness. */
  public static final String BUSY_IN_BACKGROUND = "BUSY_IN_BACKGROUND";

  /** The removal was answered neither OK nor refused. */
  public static final String REMOVAL_UNANSWERED = "REMOVAL_UNANSWERED";

  /** Leftover work, prompted, not worked since. */
  public static final String LEFTOVER_WORK_PROMPTED = "LEFTOVER_WORK_PROMPTED";

  public AgentCleanupDeferred {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentCleanupDeferred(
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
