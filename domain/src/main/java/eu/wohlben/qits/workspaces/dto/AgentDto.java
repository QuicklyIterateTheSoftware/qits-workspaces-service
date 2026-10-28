package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An agent as the doors answer it (qits-1152): its work item, the workspace it runs in (null while
 * QUEUED), its wrapper branch and the branches it pushed, and where it is in its two lifecycles.
 *
 * @param agentId the agent's id, which names it to the daemon ({@code agent-worktrees/{id}})
 * @param workspaceRowId the workspace (slot) it runs in; null while it waits for one
 * @param repositoryId the wrapper repository
 * @param branches every (repository, branch) it pushed, oldest first
 * @param activity the harness's reported state; null when nothing is known. {@code BUSY} also
 *     while the harness has ended its turn but background work it started (a subagent, a monitor, a
 *     background shell) is still in flight, until the daemon gives up waiting on it (qits-1601) — so
 *     an agent waiting on its own subagent does not read as idle or stalled
 * @param waitingOn the labels of its OPEN declared waits (qits-1153), joined with {@code "; "};
 *     null when it waits on nothing
 * @param waitingOnSince when the oldest of those OPEN waits was declared (qits-1229), so a reader
 *     can say how long the agent has waited on them; null when it waits on nothing. Not {@code
 *     waitingSince}, which is when the harness last stopped for input
 * @param startRefusal why its workspace's daemon refused to start it (qits-1217), as the daemon
 *     said; null while no start is refused. The scheduler keeps trying; the next accepted start
 *     clears it
 * @param startRefusedAt when that refusal was first seen; null with it
 * @param waits its OPEN declared waits, oldest first, with what each waits on (epic qits-1681);
 *     empty when it waits on nothing
 * @param attention what it needs a person for, as last evaluated (epic qits-1681); null when
 *     nothing, and always null for an agent that is not ACTIVE
 * @param blockedAt when its item's explicit block ({@code EXPLICIT} or {@code BOTH}) began; null
 *     while there is none
 * @param deskAskedAt when its item was last commented with an {@code @desk} mention; null when never
 */
public record AgentDto(
    String agentId,
    Long workspaceRowId,
    String repositoryId,
    String workId,
    String entityId,
    String ticketId,
    String epicId,
    String wrapperBranch,
    List<Branch> branches,
    AgentState state,
    AgentRunState runState,
    boolean admin,
    AgentHarness harness,
    String sessionId,
    String entityTitle,
    String entityStatus,
    Boolean entityBlocked,
    AgentActivityState activity,
    Instant waitingSince,
    String waitingOn,
    Instant waitingOnSince,
    Instant lastActivityAt,
    Instant createdAt,
    Instant obsoleteAt,
    Instant removedAt,
    String startRefusal,
    Instant startRefusedAt,
    List<WaitView> waits,
    Attention attention,
    Instant blockedAt,
    Instant deskAskedAt) {

  /**
   * What an ACTIVE agent needs a person for (epic qits-1681).
   *
   * @param code {@code WAIT_SUBJECT_PASSED}, {@code BLOCKED_NO_DESK}, {@code IDLE_NO_WAIT} or
   *     {@code WAIT_OVERDUE}
   * @param reason one line this service wrote, e.g. {@code Idle 42 min with no wait and no block}
   * @param since since when it holds
   * @param waitId the wait it is about, for {@code WAIT_SUBJECT_PASSED} and {@code WAIT_OVERDUE};
   *     null otherwise
   */
  public record Attention(
      @Schema(enumeration = {"WAIT_SUBJECT_PASSED", "BLOCKED_NO_DESK", "IDLE_NO_WAIT", "WAIT_OVERDUE"})
          String code,
      String reason,
      Instant since,
      String waitId) {}

  /** One branch the agent pushed. */
  public record Branch(String repositoryId, String branch, String sha, Instant pushedAt) {}
}
