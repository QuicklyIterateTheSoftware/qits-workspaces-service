package eu.wohlben.qits.workspaces.dto;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import java.time.Instant;
import java.util.List;

/**
 * An agent as the doors answer it (qits-1152): its work item, the workspace it runs in (null while
 * QUEUED), its wrapper branch and the branches it pushed, and where it is in its two lifecycles.
 *
 * @param agentId the agent's id, which names it to the daemon ({@code agent-worktrees/{id}})
 * @param workspaceRowId the workspace (slot) it runs in; null while it waits for one
 * @param repositoryId the wrapper repository
 * @param branches every (repository, branch) it pushed, oldest first
 * @param activity the harness's last reported state; null when nothing is known
 * @param waitingOn the labels of its OPEN declared waits (qits-1153), joined with {@code "; "};
 *     null when it waits on nothing
 * @param startRefusal why its workspace's daemon refused to start it (qits-1217), as the daemon
 *     said; null while no start is refused. The scheduler keeps trying; the next accepted start
 *     clears it
 * @param startRefusedAt when that refusal was first seen; null with it
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
    Instant lastActivityAt,
    Instant createdAt,
    Instant obsoleteAt,
    Instant removedAt,
    String startRefusal,
    Instant startRefusedAt) {

  /** One branch the agent pushed. */
  public record Branch(String repositoryId, String branch, String sha, Instant pushedAt) {}
}
