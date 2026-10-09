package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint.Topic;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner producers call to announce a workspace change. Wraps CDI {@link Event#fireAsync} so
 * firing never blocks or fails the mutating transaction — some producers (the daemon socket's
 * dispatch, {@code TechnicalProcessRegistry}) run on threads that must not wait, so the emit returns
 * immediately and hands off to the async observer thread. {@code domain} stays web-framework-free: the SSE plumbing that
 * consumes these hints lives in {@code service} and subscribes with {@code @ObservesAsync}.
 */
@ApplicationScoped
public class WorkspaceChangePublisher {

  @Inject Event<WorkspaceChangeHint> event;

  /**
   * Announce a change. { workspaceRowId} names the workspace scope; pass { null} for the
   * repository scope, and null for both to reach the global channel.
   */
  public void fire(String repoId, Long workspaceRowId, Topic topic) {
    event.fireAsync(new WorkspaceChangeHint(repoId, workspaceRowId, topic));
  }

  /**
   * A RUNNER-placed workspace's runtime status changed (QUEUED, taken, launched, stopped, ...) with
   * no container event this host would otherwise announce. Fired as {@code GIT_STATUS} on the
   * workspace's channel and its repository's, because that is the hint on which the SPA already
   * re-reads the workspace row and the branch tree.
   */
  public void runtimeChanged(String repoId, Long workspaceRowId) {
    fire(repoId, workspaceRowId, Topic.GIT_STATUS);
    fire(repoId, null, Topic.GIT_STATUS);
  }
}
