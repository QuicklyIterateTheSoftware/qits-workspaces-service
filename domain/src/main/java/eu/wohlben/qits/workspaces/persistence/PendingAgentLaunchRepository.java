package eu.wohlben.qits.workspaces.persistence;

import eu.wohlben.qits.workspaces.entity.PendingAgentLaunch;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * {@code pending_agent_launch}: every write is one statement whose row count is the answer, so two
 * processes contending for one launch are serialised by the row lock and exactly one of them sees a
 * 1.
 */
@ApplicationScoped
public class PendingAgentLaunchRepository
    implements PanacheRepositoryBase<PendingAgentLaunch, Long> {

  /**
   * Stores a held launch unless one is already stored for the workspace. Native, because the
   * primary key is the one-launch rule and a read-then-persist would let two presses both read
   * nothing.
   *
   * @return 1 when stored, 0 when the workspace already held one
   */
  public int insertIfAbsent(
      Long workspaceId, String text, boolean delivery, boolean compactFirst, Instant parkedAt) {
    return getEntityManager()
        .createNativeQuery(
            "insert into pending_agent_launch"
                + " (workspace_id, text, delivery, compact_first, parked_at)"
                + " values (?1, ?2, ?3, ?4, ?5)"
                + " on conflict (workspace_id) do nothing")
        .setParameter(1, workspaceId)
        .setParameter(2, text)
        .setParameter(3, delivery)
        .setParameter(4, compactFirst)
        .setParameter(5, parkedAt)
        .executeUpdate();
  }

  /** Claims it for {@code owner} when it is unclaimed, or claimed before {@code staleBefore}. */
  public int claim(Long workspaceId, String owner, Instant now, Instant staleBefore) {
    return update(
        "claimedBy = ?1, claimedAt = ?2 where workspaceId = ?3"
            + " and (claimedBy is null or claimedAt < ?4)",
        owner,
        now,
        workspaceId,
        staleBefore);
  }

  /** Gives {@code owner}'s claim on one launch back. */
  public int release(Long workspaceId, String owner) {
    return update(
        "claimedBy = null, claimedAt = null where workspaceId = ?1 and claimedBy = ?2",
        workspaceId,
        owner);
  }

  /** Gives every claim {@code owner} holds back. */
  public int releaseAll(String owner) {
    return update("claimedBy = null, claimedAt = null where claimedBy = ?1", owner);
  }

  /** Deletes it when {@code owner} holds the claim. */
  public long deleteClaimed(Long workspaceId, String owner) {
    return delete("workspaceId = ?1 and claimedBy = ?2", workspaceId, owner);
  }

  /** Deletes it when nobody holds a claim on it younger than {@code staleBefore}. */
  public long deleteUnclaimed(Long workspaceId, Instant staleBefore) {
    return delete(
        "workspaceId = ?1 and (claimedBy is null or claimedAt < ?2)", workspaceId, staleBefore);
  }
}
