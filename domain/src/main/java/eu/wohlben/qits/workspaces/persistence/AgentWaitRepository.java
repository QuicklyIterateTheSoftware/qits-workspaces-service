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

/** The declared waits (qits-1153), an agent's or a project desk's (qits-1618). */
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

  /** A project desk's waits, newest first; {@code state} null = every state. */
  public List<AgentWait> findByDesk(UUID projectId, AgentWaitState state) {
    if (state == null) {
      return list("deskProjectId = ?1 order by createdAt desc", projectId);
    }
    return list("deskProjectId = ?1 and state = ?2 order by createdAt desc", projectId, state);
  }

  public long countOpenOfDesk(UUID projectId) {
    return count("deskProjectId = ?1 and state = ?2", projectId, AgentWaitState.OPEN);
  }

  /**
   * Serialise the declarations of one project's desk for the rest of the caller's transaction, so
   * two concurrent ones cannot both pass the cap. A desk has no row of its own to lock, so this is a
   * postgres transaction-scoped advisory lock keyed on the project.
   */
  public void lockDesk(UUID projectId) {
    getEntityManager()
        .createNativeQuery("select count(*) from (select pg_advisory_xact_lock(?1)) held")
        .setParameter(1, projectId.getMostSignificantBits() ^ projectId.getLeastSignificantBits())
        .getSingleResult();
  }

  /**
   * Closed desk waits whose {@code DeskWaitClosed} the bus never took, oldest first: what the sweep
   * publishes again.
   */
  public List<AgentWait> findUnannounced() {
    return list(
        "closedEventId is not null and announcedAt is null order by closedAt");
  }

  /**
   * OPEN waits whose subject was parsed and never looked up (epic qits-1681), oldest first, at most
   * {@code limit}: what a scheduler pass resolves again. An agent's or, since qits-1668, a desk's; an
   * OTHER one is never among them.
   */
  public List<AgentWait> findOpenUnresolved(int limit) {
    return find(
            "state = ?1 and subjectKind is not null and subjectKind <> ?2"
                + " and subjectResolvedAt is null order by createdAt",
            AgentWaitState.OPEN,
            "OTHER")
        .page(0, limit)
        .list();
  }

  /** The bus took the wait's announcement; a second confirmation changes nothing. */
  public void markAnnounced(UUID waitId, Instant at) {
    update("announcedAt = ?1 where id = ?2 and announcedAt is null", at, waitId);
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

  /** OPEN waits of agents that are no longer ACTIVE, locked. A desk wait is never one. */
  public List<AgentWait> findOpenOfInactiveAgentsForUpdate() {
    return find(
            "state = ?1 and agentId is not null and agentId in (select a.id from AgentWorktree a where a.state <> ?2)",
            AgentWaitState.OPEN,
            AgentState.ACTIVE)
        .withLock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
        .list();
  }
}
