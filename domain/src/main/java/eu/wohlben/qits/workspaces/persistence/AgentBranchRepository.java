package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.AgentBranch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The branches agents pushed (qits-1152, D22). */
@ApplicationScoped
public class AgentBranchRepository
    implements PanacheRepositoryBase<AgentBranch, AgentBranch.Key> {

  /** Records a push; a later push of the same branch moves its sha. One native statement. */
  public void upsert(UUID agentId, String repositoryId, String branch, String sha, Instant at) {
    getEntityManager()
        .createNativeQuery(
            "insert into agent_branch (agent_id, repository_id, branch, sha, pushed_at)"
                + " values (?1, ?2, ?3, ?4, ?5)"
                + " on conflict (agent_id, repository_id, branch) do update set"
                + " sha = excluded.sha, pushed_at = excluded.pushed_at")
        .setParameter(1, agentId)
        .setParameter(2, repositoryId)
        .setParameter(3, branch)
        .setParameter(4, sha)
        .setParameter(5, at)
        .executeUpdate();
  }

  public List<AgentBranch> findByAgent(UUID agentId) {
    return list("key.agentId = ?1 order by pushedAt", agentId);
  }

  public List<AgentBranch> findByAgents(Collection<UUID> agentIds) {
    if (agentIds == null || agentIds.isEmpty()) {
      return List.of();
    }
    return list("key.agentId in ?1 order by pushedAt", List.copyOf(agentIds));
  }

  /** The agents that pushed this branch of this repository. */
  public List<UUID> agentsOn(String repositoryId, String branch) {
    return list("key.repositoryId = ?1 and key.branch = ?2", repositoryId, branch).stream()
        .map(b -> b.key.agentId)
        .toList();
  }
}
