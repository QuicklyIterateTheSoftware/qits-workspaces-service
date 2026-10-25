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

  private final ConcurrentHashMap<String, Boolean> backgroundWork = new ConcurrentHashMap<>();

  public void report(String agentId, AgentActivityState state) {
    activity.put(agentId, state);
    backgroundWork.remove(agentId);
  }

  /**
   * {@code state} with background work the agent started still in flight (qits-1601): the raw
   * state stays {@code state}, the reported one reads {@code BUSY} when it is IDLE or WAITING — the
   * registry's rule, short of the grace-expiry case, which its own test covers.
   */
  public void reportWithBackgroundWork(String agentId, AgentActivityState state) {
    activity.put(agentId, state);
    backgroundWork.put(agentId, true);
  }

  public void forget(String agentId) {
    activity.remove(agentId);
    backgroundWork.remove(agentId);
  }

  @Override
  public Optional<AgentActivityState> activityOf(String agentId) {
    return Optional.ofNullable(activity.get(agentId));
  }

  @Override
  public Optional<AgentActivityState> reportedActivityOf(String agentId) {
    return activityOf(agentId)
        .map(
            state ->
                backgroundWork.containsKey(agentId)
                        && (state == AgentActivityState.IDLE
                            || state == AgentActivityState.WAITING)
                    ? AgentActivityState.BUSY
                    : state);
  }
}
