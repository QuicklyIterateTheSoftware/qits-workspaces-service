package eu.wohlben.qits.workspaces.control;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Where {@link DispatchService} keeps a launch (or a delivery) it holds for a RUNNER workspace
 * QUEUED for a slot, so that it outlives the process (qits-1064). One per workspace: a second
 * {@link #hold} for a workspace that holds one is refused, and that is the one-launch rule.
 *
 * <p>A port for one reason: {@code DispatchServiceQueuedTest} drives two {@code DispatchService}
 * instances — a process and the one that replaced it — over one shared fake, with no database. The
 * shipped implementation is {@link PersistedHeldAgentLaunches}, on {@code pending_agent_launch}.
 *
 * <p><b>The claim.</b> A held launch is unclaimed while it waits for a take. Whoever is to deliver it
 * {@link #claim}s it first, and only the caller that got it back delivers; it stays stored while the
 * daemon is waited for and is {@link #finish}ed once the wait ended. A claim older than the caller's
 * {@code staleBefore} is treated as abandoned and may be taken again.
 */
public interface HeldAgentLaunches {

  /**
   * A held launch as stored.
   *
   * @param claimedBy the process delivering it, null while unclaimed
   * @param claimedAt when it was claimed, null with {@code claimedBy}
   */
  record Held(
      Long workspaceId,
      String text,
      boolean delivery,
      boolean compactFirst,
      Instant parkedAt,
      String claimedBy,
      Instant claimedAt) {

    /** Unclaimed, or claimed before {@code staleBefore}: nobody is delivering it. */
    public boolean claimable(Instant staleBefore) {
      return claimedBy == null || claimedAt == null || claimedAt.isBefore(staleBefore);
    }
  }

  /**
   * Stores a held launch, unclaimed.
   *
   * @return false when the workspace already holds one, which is left as it is
   */
  boolean hold(
      Long workspaceId, String text, boolean delivery, boolean compactFirst, Instant parkedAt);

  /** Every held launch, claimed or not. */
  List<Held> all();

  /**
   * Claims the workspace's held launch for {@code owner} when it is unclaimed or its claim is older
   * than {@code staleBefore}.
   *
   * @return the launch when this call won it; empty when there is none or somebody else holds it
   */
  Optional<Held> claim(Long workspaceId, String owner, Instant now, Instant staleBefore);

  /** Gives {@code owner}'s claim back, leaving the launch stored for the next taker. */
  void release(Long workspaceId, String owner);

  /**
   * Gives every claim {@code owner} holds back — a process shutting down mid-wait.
   *
   * @return how many
   */
  int releaseAll(String owner);

  /** Deletes the launch {@code owner} claimed: the daemon took it, or the wait gave up. */
  void finish(Long workspaceId, String owner);

  /**
   * Deletes the workspace's held launch unless somebody holds a claim on it younger than {@code
   * staleBefore}: the workspace left the queue, and a launch already being delivered is that
   * delivery's to end.
   *
   * @return whether one was deleted
   */
  boolean dropUnclaimed(Long workspaceId, Instant staleBefore);

  /**
   * Deletes whatever the workspace holds, claimed or not: it resolved. Joins the caller's
   * transaction — the resolving one.
   */
  void discard(Long workspaceId);
}
