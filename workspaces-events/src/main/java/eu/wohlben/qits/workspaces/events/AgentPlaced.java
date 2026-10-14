package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A queued agent was given a workspace: its row names {@code workspaceId} now and is RUNNING, and
 * the scheduler starts it once that workspace's daemon answers ({@link AgentStarted}).
 *
 * <p><b>The first of the agent lifecycle events, and the one that states the family's
 * conventions</b>, the way qits-ci's {@code RunnerCreated} states the runner events'. The family is
 * {@code AgentPlaced}, {@link AgentStarted}, {@link AgentStartRefused}, {@link AgentRequeued},
 * {@link AgentYielded}, {@link AgentResumed}, {@link AgentObsolete}, {@link AgentCleanupDeferred},
 * {@link AgentRemoved} and {@link AgentHarnessExited}, with {@link WorkspaceStarted}, {@link
 * WorkspaceFailed} and {@link WorkspaceRemoved} for the slot underneath. Each is one fact, so a
 * subscriber that wants one fact subscribes to one name — {@code qits wait --event AgentRemoved
 * --when 'agentId=<id>'} is the shape this family exists for.
 *
 * <p><b>The common fields, once.</b> Every id is a {@code String}, flat and top-level, so a {@code
 * --when} condition names it directly: {@code agentId} is the agent row's uuid, {@code workId} the
 * work item it works on (qits-projects' id), {@code workspaceId} the workspace <em>row</em> id (the
 * number in {@code /workspaces/daemon/<id>}, not the {@code ws-…} name) and {@code repositoryId}
 * the wrapper repository. A field the row does not have at the site — a queued agent's workspace —
 * is absent from the payload, never an explicit null. {@code eventId} is generated when absent and
 * travels in the envelope, never in the payload; {@code occurredAt} is when the row was written and
 * rides the envelope alone, because {@code CanonicalJson} excludes everything {@code QitsEvent}
 * declares.
 *
 * <p><b>Plain strings, never this service's enums</b>: a reason is an upper-case word, spelled by
 * the constants on its record, and a subscriber shares the word rather than the class.
 *
 * <p><b>Nothing a person or an agent wrote is ever on this bus.</b> No instruction, no pending turn,
 * no prompt draft, no work item title. A {@code detail} is a sentence this service wrote, or a
 * daemon's refusal, and nothing else.
 *
 * <p><b>Published only once the write committed</b>, from the thread that made it; the publish
 * itself happens on a thread of its own, in the order the writes were made.
 */
public record AgentPlaced(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    Instant occurredAt)
    implements QitsEvent {

  public AgentPlaced {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentPlaced(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, occurredAt);
  }
}
