package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Agent rows hosted on a workspace, for the suites that prove a workspace verb asks its agents
 * before it destroys their worktrees (qits-1263, {@link AgentWorktreeGuard}). The row is written
 * directly — RUNNING on the slot, as the scheduler leaves a placed agent — so no dispatch, runner
 * or daemon start is involved. {@link #retireAll} marks every row this bean wrote REMOVED, so none
 * outlives its test and stands in another test's way.
 */
@ApplicationScoped
public class TestAgents {

  @Inject AgentWorktreeRepository agents;

  private final List<UUID> written = Collections.synchronizedList(new ArrayList<>());

  /**
   * An ACTIVE agent of a fresh work item {@code entityId}, placed on {@code workspaceRowId} and
   * started there, so its worktree may live on the slot and the guard asks about it (qits-1750).
   */
  public UUID placeOn(String repositoryId, Long workspaceRowId, String entityId) {
    return write(repositoryId, workspaceRowId, entityId, workspaceRowId);
  }

  /**
   * {@link #placeOn}, but never started there: no start recorded and no command id, so nothing of
   * it is on the slot (qits-1750).
   */
  public UUID placeUnstartedOn(String repositoryId, Long workspaceRowId, String entityId) {
    return write(repositoryId, workspaceRowId, entityId, null);
  }

  private UUID write(
      String repositoryId, Long workspaceRowId, String entityId, Long startedWorkspaceId) {
    UUID id = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = new AgentWorktree();
              row.id = id;
              row.repositoryId = repositoryId;
              row.workId = "w-" + id;
              row.entityId = entityId;
              row.wrapperBranch = "ticket/w-" + id;
              row.workspaceId = workspaceRowId;
              row.startedWorkspaceId = startedWorkspaceId;
              row.runState = AgentRunState.RUNNING;
              row.createdAt = Instant.now();
              row.gitRefs = "[]";
              agents.persist(row);
            });
    written.add(id);
    return id;
  }

  /** Every agent this bean wrote is REMOVED. */
  public void retireAll() {
    List<UUID> ids;
    synchronized (written) {
      ids = List.copyOf(written);
      written.clear();
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                ids.forEach(
                    id ->
                        agents
                            .findByIdOptional(id)
                            .ifPresent(
                                row -> {
                                  row.state = AgentState.REMOVED;
                                  row.removedAt = Instant.now();
                                })));
  }
}
