package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The agent rows (qits-1152). "Live" means not REMOVED: ACTIVE or OBSOLETE. */
@ApplicationScoped
public class AgentWorktreeRepository implements PanacheRepositoryBase<AgentWorktree, UUID> {

  /** The ACTIVE agent of a work item, if any (at most one, {@code uq_agent_worktree_active_work}). */
  public Optional<AgentWorktree> findActiveByWorkId(String workId) {
    if (workId == null || workId.isBlank()) {
      return Optional.empty();
    }
    return find("workId = ?1 and state = ?2", workId.trim(), AgentState.ACTIVE)
        .firstResultOptional();
  }

  /** The newest live agent of a work item: the ACTIVE one, else the newest OBSOLETE one. */
  public Optional<AgentWorktree> findLiveByWorkId(String workId) {
    Optional<AgentWorktree> active = findActiveByWorkId(workId);
    if (active.isPresent() || workId == null || workId.isBlank()) {
      return active;
    }
    return find(
            "workId = ?1 and state = ?2 order by createdAt desc",
            workId.trim(),
            AgentState.OBSOLETE)
        .firstResultOptional();
  }

  /** Every live agent of a work item (ACTIVE, else every OBSOLETE): the ACTIVE one first, then OBSOLETE newest first. */
  public List<AgentWorktree> findLiveAllByWorkId(String workId) {
    if (workId == null || workId.isBlank()) {
      return List.of();
    }
    String trimmed = workId.trim();
    List<AgentWorktree> out = new ArrayList<>();
    findActiveByWorkId(trimmed).ifPresent(out::add);
    out.addAll(
        list("workId = ?1 and state = ?2 order by createdAt desc", trimmed, AgentState.OBSOLETE));
    return out;
  }

  /** Every agent of these work items, whatever its state, newest first. */
  public List<AgentWorktree> findByWorkIds(Collection<String> workIds) {
    if (workIds == null || workIds.isEmpty()) {
      return List.of();
    }
    return list("workId in ?1 order by createdAt desc", List.copyOf(workIds));
  }

  public List<AgentWorktree> findByWorkspace(Long workspaceId) {
    return list("workspaceId = ?1 order by createdAt", workspaceId);
  }

  /** The agents in a workspace that are not REMOVED. */
  public List<AgentWorktree> findLiveByWorkspace(Long workspaceId) {
    return list(
        "workspaceId = ?1 and state <> ?2 order by createdAt", workspaceId, AgentState.REMOVED);
  }

  /**
   * Whether a live agent placed in this workspace holds a token whose subject is {@code subject}:
   * the binding {@code ContainerProxyRoute} admits an agent's own credential by (qits-1386). A
   * REMOVED agent's token is deleted with it, so it binds nothing; a blank subject never matches.
   */
  public boolean existsLiveInWorkspaceWithTokenSubject(Long workspaceId, String subject) {
    if (workspaceId == null || subject == null || subject.isBlank()) {
      return false;
    }
    return count(
            "workspaceId = ?1 and commissionedTokenSubject = ?2 and state <> ?3",
            workspaceId,
            subject,
            AgentState.REMOVED)
        > 0;
  }

  /** ACTIVE agents waiting for a slot, oldest first. */
  public List<AgentWorktree> findQueued() {
    return list(
        "state = ?1 and runState = ?2 order by createdAt", AgentState.ACTIVE, AgentRunState.QUEUED);
  }

  /** Every agent that is placed and not REMOVED. */
  public List<AgentWorktree> findPlacedLive() {
    return list(
        "workspaceId is not null and state <> ?1 order by createdAt", AgentState.REMOVED);
  }

  /** OBSOLETE agents, oldest first: the cleanup sweep's work list. */
  public List<AgentWorktree> findObsolete() {
    return list("state = ?1 order by obsoleteAt", AgentState.OBSOLETE);
  }

  /** ACTIVE agents of a repository standing on this wrapper branch. */
  public List<AgentWorktree> findActiveByWrapperBranch(String repositoryId, String branch) {
    return list(
        "repositoryId = ?1 and wrapperBranch = ?2 and state = ?3",
        repositoryId,
        branch,
        AgentState.ACTIVE);
  }

  /** ACTIVE agents whose item's status is {@code status}, any case, oldest first (qits-1276). */
  public List<AgentWorktree> findActiveByEntityStatus(String status) {
    if (status == null || status.isBlank()) {
      return List.of();
    }
    return list(
        "state = ?1 and upper(entityStatus) = ?2 order by createdAt",
        AgentState.ACTIVE,
        status.trim().toUpperCase(java.util.Locale.ROOT));
  }

  /** Every query of the listing door, each filter optional. */
  public List<AgentWorktree> search(String workId, Long workspaceId, AgentState state) {
    StringBuilder q = new StringBuilder("1 = 1");
    java.util.Map<String, Object> params = new java.util.HashMap<>();
    if (workId != null && !workId.isBlank()) {
      q.append(" and workId = :workId");
      params.put("workId", workId.trim());
    }
    if (workspaceId != null) {
      q.append(" and workspaceId = :workspaceId");
      params.put("workspaceId", workspaceId);
    }
    if (state != null) {
      q.append(" and state = :state");
      params.put("state", state);
    }
    q.append(" order by createdAt desc");
    return list(q.toString(), params);
  }

  /** Every placed agent of a work item, by its id or its qualified id, newest first. */
  public List<AgentWorktree> findPlacedByWorkRef(String workRef) {
    if (workRef == null || workRef.isBlank()) {
      return List.of();
    }
    return list(
        "workspaceId is not null and (workId = ?1 or entityId = ?1) order by createdAt desc",
        workRef.trim());
  }

  /** Token ids live agents hold: the commission reconcile keeps them. */
  public List<String> liveCommissionedTokenIds() {
    return list("state <> ?1 and commissionedTokenId is not null", AgentState.REMOVED).stream()
        .map(a -> a.commissionedTokenId)
        .toList();
  }
}
