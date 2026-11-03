package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.control.ArchivedAgentTranscripts;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ArchivedAgentTranscripts}, the one bean of it: an agent's sessions are what its daemon
 * shipped into the transcript store ({@link ShippedClaudeTranscripts}, qits-1752), and nothing else.
 *
 * <p>A slot workspace (no branch) answers its agents' sessions, agent by agent. An old-model
 * workspace (a branch of its own, no agents) never had a daemon that shipped, so it answers no
 * sessions, and any session id asked of it is the same 404 an unknown one gets.
 */
@ApplicationScoped
public class AgentTranscripts implements ArchivedAgentTranscripts {

  @Inject ShippedClaudeTranscripts shipped;

  @Inject AgentWorktreeRepository agents;

  @Override
  public List<ArchivedSessionDto> sessionsFor(Workspace workspace) {
    if (workspace.branch != null) {
      return List.of();
    }
    List<ArchivedSessionDto> sessions = new ArrayList<>();
    for (AgentWorktree agent : agentsOf(workspace)) {
      sessions.addAll(shipped.sessionsFor(agent));
    }
    return sessions;
  }

  @Override
  public List<String> transcriptOf(Workspace workspace, String sessionId) {
    if (workspace.branch == null) {
      for (AgentWorktree agent : agentsOf(workspace)) {
        if (shipped.sessionsFor(agent).stream()
            .anyMatch(session -> session.sessionId().equals(sessionId))) {
          return shipped.transcriptOf(agent, sessionId);
        }
      }
    }
    throw new NotFoundException("Agent session not found: " + sessionId);
  }

  @Override
  public List<ArchivedSessionDto> sessionsFor(AgentWorktree agent) {
    return shipped.sessionsFor(agent);
  }

  @Override
  public List<String> transcriptOf(AgentWorktree agent, String sessionId) {
    return shipped.transcriptOf(agent, sessionId);
  }

  private List<AgentWorktree> agentsOf(Workspace workspace) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findByWorkspace(workspace.id));
  }
}
