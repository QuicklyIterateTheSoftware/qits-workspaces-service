package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.PendingAgentLaunch;
import eu.wohlben.qits.workspaces.persistence.PendingAgentLaunchRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The shipped {@link HeldAgentLaunches}, on {@code pending_agent_launch} ({@code V17}).
 *
 * <p>Every method but {@link #discard} runs in a transaction of its own: its callers are {@code
 * AFTER_SUCCESS} observers, the boot and the launch-wait threads, none of which stands in a
 * transaction worth joining. {@link #discard} is the exception because its caller is the resolution,
 * and the row has to go in the transaction that resolved the workspace.
 */
@ApplicationScoped
public class PersistedHeldAgentLaunches implements HeldAgentLaunches {

  @Inject PendingAgentLaunchRepository launches;

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public boolean hold(
      Long workspaceId, String text, boolean delivery, boolean compactFirst, Instant parkedAt) {
    return launches.insertIfAbsent(workspaceId, text, delivery, compactFirst, parkedAt) == 1;
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public List<Held> all() {
    return launches.listAll().stream().map(PersistedHeldAgentLaunches::held).toList();
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public Optional<Held> claim(Long workspaceId, String owner, Instant now, Instant staleBefore) {
    if (launches.claim(workspaceId, owner, now, staleBefore) != 1) {
      return Optional.empty();
    }
    // Read after the update and never before it, so the row is the one the update wrote.
    return launches.findByIdOptional(workspaceId).map(PersistedHeldAgentLaunches::held);
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public void release(Long workspaceId, String owner) {
    launches.release(workspaceId, owner);
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public int releaseAll(String owner) {
    return launches.releaseAll(owner);
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public void finish(Long workspaceId, String owner) {
    launches.deleteClaimed(workspaceId, owner);
  }

  @Override
  @Transactional(TxType.REQUIRES_NEW)
  public boolean dropUnclaimed(Long workspaceId, Instant staleBefore) {
    return launches.deleteUnclaimed(workspaceId, staleBefore) > 0;
  }

  @Override
  @Transactional
  public void discard(Long workspaceId) {
    launches.deleteById(workspaceId);
  }

  private static Held held(PendingAgentLaunch row) {
    return new Held(
        row.workspaceId,
        row.text,
        row.delivery,
        row.compactFirst,
        row.parkedAt,
        row.claimedBy,
        row.claimedAt);
  }
}
