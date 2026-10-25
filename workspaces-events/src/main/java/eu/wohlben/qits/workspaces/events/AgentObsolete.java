package eu.wohlben.qits.workspaces.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A release consumed an agent's wrapper branch and its work item is closed (VERIFIED or DONE), so
 * the agent is OBSOLETE: its work item's work is released. The two happen in either order
 * (qits-1605): a release of an open item's branch leaves its agent ACTIVE and this event comes when
 * the item closes, carrying that release's refs; a release of a closed item's branch announces it
 * at once. A closed item whose wrapper branch is gone from the git host or merged into the default
 * branch, with no release consuming it, marks it OBSOLETE with neither ref. A drop of the work item
 * marks it OBSOLETE too (qits-1276): a dropped item's branch is
 * never released, and its agent would otherwise hold its slot for good. Nothing is removed yet —
 * the cleanup sweep does that once the agent is idle ({@link AgentRemoved}, or {@link
 * AgentCleanupDeferred} saying why not).
 *
 * <p>One event per agent, so a subscriber waiting on one agent selects it by {@code agentId}.
 * {@code branch} is the released wrapper branch, {@code version} the release that consumed it and
 * {@code commitSha} the released commit, as the release reported them (for an item closed after
 * the release, as they were recorded when it consumed the branch); either of the last two is
 * absent when it did not say. For a drop, {@code branch} is the agent's wrapper branch and both
 * {@code version} and {@code commitSha} are absent: nothing was released. {@code workspaceId} is
 * absent for an agent that was never placed. Everything else is {@link AgentPlaced}'s.
 */
public record AgentObsolete(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String branch,
    String version,
    String commitSha,
    Instant occurredAt)
    implements QitsEvent {

  public AgentObsolete {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }

  /** The constructor a publisher uses: the facts, with the identity taken care of. */
  public AgentObsolete(
      String agentId,
      String workId,
      String workspaceId,
      String repositoryId,
      String branch,
      String version,
      String commitSha,
      Instant occurredAt) {
    this(null, agentId, workId, workspaceId, repositoryId, branch, version, commitSha, occurredAt);
  }
}
