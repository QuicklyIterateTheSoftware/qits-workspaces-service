package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWait;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The declared waits (qits-1153). */
@ApplicationScoped
public class AgentWaitRepository implements PanacheRepositoryBase<AgentWait, UUID> {

  /** An agent's waits, newest first; {@code state} null = every state. */
  public List<AgentWait> findByAgent(UUID agentId, AgentWaitState state) {
    if (state == null) {
      return list("agentId = ?1 order by createdAt desc", agentId);
    }
    return list("agentId = ?1 and state = ?2 order by createdAt desc", agentId, state);
  }

  /** The OPEN waits of these agents, oldest first. */
  public List<AgentWait> findOpenByAgents(Collection<UUID> agentIds) {
    if (agentIds == null || agentIds.isEmpty()) {
      return List.of();
    }
    return list(
        "agentId in ?1 and state = ?2 order by createdAt", List.copyOf(agentIds), AgentWaitState.OPEN);
  }

  public long countOpen(UUID agentId) {
    return count("agentId = ?1 and state = ?2", agentId, AgentWaitState.OPEN);
  }

  /** The OPEN waits on one event name, oldest first, locked so two deliveries cannot both close one. */
  public List<AgentWait> findOpenByEventForUpdate(String eventName) {
    return find("eventName = ?1 and state = ?2 order by createdAt", eventName, AgentWaitState.OPEN)
        .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        .list();
  }

  /** The event names some OPEN wait names: the listener's cheap narrowing. */
  public Set<String> openEventNames() {
    return new HashSet<>(
        getEntityManager()
            .createQuery(
                "select distinct w.eventName from AgentWait w where w.state = :open", String.class)
            .setParameter("open", AgentWaitState.OPEN)
            .getResultList());
  }

  /** OPEN waits whose timeout passed, locked. */
  public List<AgentWait> findExpiredForUpdate(Instant now) {
    return find(
            "state = ?1 and expiresAt is not null and expiresAt <= ?2 order by expiresAt",
            AgentWaitState.OPEN,
            now)
        .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        .list();
  }

  /** OPEN waits of agents that are no longer ACTIVE, locked. */
  public List<AgentWait> findOpenOfInactiveAgentsForUpdate() {
    return find(
            "state = ?1 and agentId in (select a.id from AgentWorktree a where a.state <> ?2)",
            AgentWaitState.OPEN,
            AgentState.ACTIVE)
        .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        .list();
  }
}
