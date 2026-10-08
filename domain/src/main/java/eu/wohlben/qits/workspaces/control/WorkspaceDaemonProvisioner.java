package eu.wohlben.qits.workspaces.control;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Awaits the in-container workspace-daemon's <b>autonomous self-provision</b> — on boot the daemon
 * clones {@code /workspace} and materializes submodules entirely from its injected env, then
 * reports the outcome up over the control socket. This is the qits-side await; it sends the daemon
 * nothing (the whole point of the autonomous model — docs/epics/qits-workspace-daemon/ Part 1).
 * Framework-free so it lives in {@code domain}; the real implementation is the backend {@code
 * WorkspaceDaemonRegistry} (service module), reached over the socket.
 *
 * <p>Apps without the backend impl (cli, tests with no daemon) have no bean — {@link
 * WorkspaceService} injects it as {@code Instance<>} and, finding it empty, runs the host-driven
 * clone (exactly today's behaviour). Distinct from {@link WorkspaceDaemonLiveness}, which is
 * observational only; this one drives behaviour.
 */
public interface WorkspaceDaemonProvisioner {

  /**
   * Wait for the workspace's daemon to self-provision.
   *
   * @param repoId the repository whose branch the workspace tracks — carried so an implementation
   *     can address the workspace's container unambiguously ({@code workspaceId} alone is unique
   *     only within a repository); the socket-backed backend impl awaits purely by {@code
   *     workspaceId} and ignores it
   * @param connectTimeout how long to wait for a daemon to become live at all; if none does,
   *     returns {@link Optional#empty()} and the caller fails the provision (the daemon is the sole
   *     provisioner — there is no host-driven fallback)
   * @param provisionTimeout how long, once a daemon is live, to wait for the terminal outcome; a
   *     timeout resolves to a failed {@link ProvisionResult} (the caller then removes the container
   *     + marks FAILED — a daemon that took ownership may have left a half-populated {@code
   *     /workspace})
   * @param onLine receives streamed clone/submodule output for the {@code clone} process segment;
   *     may be {@code null}
   * @return the provision outcome, or empty when no daemon became live within {@code
   *     connectTimeout}
   */
  default Optional<ProvisionResult> awaitProvision(
      Long workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine) {
    return awaitProvision(workspaceId, connectTimeout, provisionTimeout, onLine, () -> false);
  }

  /**
   * {@link #awaitProvision(Long, Duration, Duration, Consumer)} that a stop can cut short
   * (qits-1076).
   *
   * <p>{@code abandoned} is asked once the await's slot is registered, and again while it waits for
   * a daemon to dial home; answering true ends the await at once with a failed outcome. It closes the
   * one gap {@link #abandonProvision} cannot: a stop that lands after the caller last looked but
   * before the slot existed, whose release therefore found nothing to complete. The caller's stop
   * marker is set before the release is sent, so whichever of the two the await sees first, it sees
   * the stop.
   *
   * @param abandoned whether the start this await belongs to has been overtaken by a stop or a
   *     container delete; never {@code null}
   */
  Optional<ProvisionResult> awaitProvision(
      Long workspaceId,
      Duration connectTimeout,
      Duration provisionTimeout,
      Consumer<String> onLine,
      BooleanSupplier abandoned);

  /**
   * Release the workspace's pending {@link #awaitProvision} with a failed outcome carrying {@code
   * reason}, so the provision thread returns now instead of at the provision timeout (qits-1076).
   *
   * <p>The caller is a stop or a container delete landing in a start's clone window. Before this, the
   * start sat in its await for up to {@code qits.workspace.provision.timeout-ms} (ten minutes) and
   * then — reading the timeout as a broken daemon — removed the container the person had just
   * paused. The await's caller is what decides that a released outcome means "stopped" rather than
   * "failed"; this only wakes it.
   *
   * <p>Never creates a slot: with no await registered there is nothing to release, and a retained
   * outcome would be read by the <em>next</em> start as its own.
   *
   * @return whether an await was pending and has now been released
   */
  boolean abandonProvision(Long workspaceId, String reason);
}
