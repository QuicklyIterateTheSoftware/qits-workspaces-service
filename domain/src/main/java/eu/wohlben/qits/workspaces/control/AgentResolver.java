package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;

/**
 * An agent that is not REMOVED, or 404 — what the prompt draft and its images are keyed on since
 * qits-1152 (the composition belongs to the agent that will hear it, not to the slot it runs in).
 */
@ApplicationScoped
public class AgentResolver {

  @Inject AgentWorktreeRepository agents;

  public AgentWorktree resolveLive(UUID id) {
    if (id == null) {
      throw new NotFoundException("Agent not found: null");
    }
    AgentWorktree agent = agents.findById(id);
    if (agent == null || agent.state == AgentState.REMOVED) {
      throw new NotFoundException("Agent not found: " + id);
    }
    return agent;
  }
}
