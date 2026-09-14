package eu.wohlben.qits.workspaces.control;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceAgentActivity}: the turn-boundary rollup a delivery waits on,
 * driven by a test instead of by a daemon's lifecycle hooks.
 *
 * <p><b>A profile-scoped {@link Alternative} and not a global {@code @Mock}</b>, exactly like {@link
 * FakeWorkspaceServiceDriver} beside it and for its reason: the real implementation here is {@code
 * WorkspaceDaemonRegistry}, which every other service test and the daemon ITs need answering for
 * themselves. A test opts in with {@code getEnabledAlternatives()}.
 *
 * <p>Unknown until a test {@linkplain #report reports} something, which is what a workspace whose
 * daemon has not said anything about its agent looks like — the {@code domain} copy ({@code
 * FakeWorkspaceAgentActivity}) makes the same default for the same reason.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FakeAgentActivity implements WorkspaceAgentActivity {

  // Accessed through methods rather than the field: this is an @ApplicationScoped bean, so a test
  // injects a client proxy whose field reads would not see the real instance's state.
  private final ConcurrentHashMap<Long, AgentActivityState> activity = new ConcurrentHashMap<>();

  public void report(Long workspaceId, AgentActivityState state) {
    activity.put(workspaceId, state);
  }

  public void forget(Long workspaceId) {
    activity.remove(workspaceId);
  }

  @Override
  public Optional<AgentActivityState> activityFor(Long workspaceId) {
    return Optional.ofNullable(activity.get(workspaceId));
  }
}
