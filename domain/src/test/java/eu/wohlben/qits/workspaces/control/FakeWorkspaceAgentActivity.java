package eu.wohlben.qits.workspaces.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link WorkspaceAgentActivity}: a workspace's agent activity is unknown ({@link
 * Optional#empty()}) until a test {@linkplain #report reports} it, so by default every
 * {@code @QuarkusTest} sees no daemon-reported activity and {@code WorkspaceDto.agentActivity} is
 * null. Mirrors {@link FakeWorkspaceDaemonInfo}.
 */
@Mock
@ApplicationScoped
public class FakeWorkspaceAgentActivity implements WorkspaceAgentActivity {

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
