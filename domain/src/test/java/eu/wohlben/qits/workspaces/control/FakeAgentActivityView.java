package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link AgentActivityView}: an agent's activity is unknown ({@link
 * Optional#empty()}) until a test {@linkplain #report reports} it — what an agent whose daemon is
 * not connected looks like, and what every {@code @QuarkusTest} in the module saw before this bean
 * existed (the view was unresolvable), so it changes no other test.
 */
@ApplicationScoped
public class FakeAgentActivityView implements AgentActivityView {

  // Accessed through methods: a test injects a client proxy.
  private final ConcurrentHashMap<String, AgentActivityState> activity = new ConcurrentHashMap<>();

  public void report(String agentId, AgentActivityState state) {
    activity.put(agentId, state);
  }

  public void forget(String agentId) {
    activity.remove(agentId);
  }

  @Override
  public Optional<AgentActivityState> activityOf(String agentId) {
    return Optional.ofNullable(activity.get(agentId));
  }
}
