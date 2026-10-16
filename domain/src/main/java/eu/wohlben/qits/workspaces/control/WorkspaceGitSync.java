package eu.wohlben.qits.workspaces.control;

/**
 * Tells a workspace's in-container {@code workspace-daemon} to pull an incoming merge/integration
 * that a host-side {@code mergeIntoTarget} just pushed to that branch's origin ref
 * (docs/epics/qits-workspace-daemon/features/2026-07-25_daemon-bidirectional-auto-sync.md). Without
 * this the container's checkout stays behind origin until the next host git op reactively syncs it;
 * with it the daemon fast-forwards right away.
 *
 * <p>Framework-free (no websockets type) so it lives in {@code domain}; the {@code service} module
 * implements it over {@code WorkspaceDaemonRegistry} (sending a {@code PullBranch} frame), and
 * {@link WorkspaceService} reads it as an {@code Instance<>} that is simply empty in apps without
 * the backend (e.g. {@code cli}, tests). A no-op when no daemon is live — the checkout then syncs
 * on its next host git op, so a missed notification never loses data.
 *
 * <p>This drives the daemon's inbound sync. The daemon's <em>auto-push</em> half (committed
 * work flowing container → origin) needs no host SPI — it is daemon-autonomous.
 */
public interface WorkspaceGitSync {

  /**
   * Ask {@code workspaceId}'s live daemon to fast-forward its checkout to {@code branch}'s current
   * origin ref. A best-effort, fire-and-forget notification: no-op when no daemon is connected, and
   * the daemon refuses anything but a fast-forward (never clobbers a tree that turned dirty).
   */
  void pullFromOrigin(Long workspaceId, String branch);
}
