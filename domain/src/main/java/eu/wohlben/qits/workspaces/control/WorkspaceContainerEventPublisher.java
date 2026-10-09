package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liners {@link WorkspaceService} calls to announce a workspace container's lifecycle
 * edges: {@code started} is observed by {@link WorkspaceBootstrapRunner}; {@code stopping} has no
 * production observer since the workspace services concept went (qits-947), and stays as the
 * synchronous pre-removal edge the lifecycle tests pin. {@code domain} stays web-framework-free; the
 * coupling is CDI events, inverting the forbidden direct dependency.
 *
 * <p>The two directions fire deliberately differently:
 *
 * <ul>
 *   <li><b>started</b> — {@link Event#fireAsync}, so firing never blocks or fails the transition
 *       that just committed RUNNING; {@code ensureContainer} (and every lazy caller on a request
 *       thread) keeps its latency and the bootstrap await happens on the async observer thread.
 *   <li><b>stopping</b> — synchronous {@link Event#fire}, so an observer completes <em>before</em>
 *       the caller removes the container: it must see the container still alive.
 * </ul>
 */
@ApplicationScoped
public class WorkspaceContainerEventPublisher {

  @Inject Event<WorkspaceContainerStarted> started;

  @Inject Event<WorkspaceContainerStopping> stopping;

  /** Restart-shaped convenience (no process, not a fresh provision) — the test-suite shorthand. */
  public void fireStarted(String repoId, String workspaceId, Long workspaceRowId) {
    fireStarted(repoId, workspaceId, workspaceRowId, null, false);
  }

  /**
   * {@code technicalProcessId} correlates the async bootstrap phase with the start's log
   * stream; {@code freshProvision} marks the container→clone transition that triggers bootstrap.
   */
  public void fireStarted(
      String repoId,
      String workspaceId,
      Long workspaceRowId,
      String technicalProcessId,
      boolean freshProvision) {
    started.fireAsync(
        new WorkspaceContainerStarted(
            repoId, workspaceId, workspaceRowId, technicalProcessId, freshProvision));
  }

  /** Synchronous by design — observers must finish before the caller's {@code containers.rm}. */
  public void fireStopping(
      String repoId, String workspaceId, Long workspaceRowId, boolean graceful) {
    stopping.fire(new WorkspaceContainerStopping(repoId, workspaceId, workspaceRowId, graceful));
  }
}
