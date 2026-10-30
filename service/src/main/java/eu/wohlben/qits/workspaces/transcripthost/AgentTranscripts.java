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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link ArchivedAgentTranscripts}, the one bean of it: an agent's sessions are what its daemon
 * shipped ({@link ShippedClaudeTranscripts}) and what the mounted harness volume holds ({@link
 * MountedClaudeTranscripts}), deduplicated by session id with the shipped copy winning (qits-1752).
 *
 * <p>Both, rather than one per placement, because neither answers for every session: a TARGET
 * workspace's harness writes where only its daemon can read, so the store is the only source for
 * it; a DIRECT workspace's writes to the mounted volume and ships too once its daemon speaks
 * capability 13; and every session from before that is on the mount alone. The shipped copy wins a
 * tie because it is the agent's own, attributed by the daemon that ran it, where the mounted one is
 * inferred from a branch and a time window.
 *
 * <p>A slot workspace (no branch) answers its agents' sessions, agent by agent; an old-model
 * workspace (a branch of its own, no agents) has nothing in the store and keeps the mounted read.
 */
@ApplicationScoped
public class AgentTranscripts implements ArchivedAgentTranscripts {

  @Inject ShippedClaudeTranscripts shipped;

  @Inject MountedClaudeTranscripts mounted;

  @Inject AgentWorktreeRepository agents;

  @Override
  public List<ArchivedSessionDto> sessionsFor(Workspace workspace) {
    if (workspace.branch != null) {
      return mounted.sessionsFor(workspace);
    }
    List<ArchivedSessionDto> sessions = new ArrayList<>();
    for (AgentWorktree agent : agentsOf(workspace)) {
      sessions.addAll(sessionsFor(workspace.repositoryId, agent));
    }
    return sessions;
  }

  @Override
  public List<String> transcriptOf(Workspace workspace, String sessionId) {
    if (workspace.branch != null) {
      return mounted.transcriptOf(workspace, sessionId);
    }
    for (AgentWorktree agent : agentsOf(workspace)) {
      if (sessionsFor(workspace.repositoryId, agent).stream()
          .anyMatch(session -> session.sessionId().equals(sessionId))) {
        return transcriptOf(workspace.repositoryId, agent, sessionId);
      }
    }
    throw new NotFoundException("Agent session not found: " + sessionId);
  }

  @Override
  public List<ArchivedSessionDto> sessionsFor(AgentWorktree agent) {
    return sessionsFor(agent.repositoryId, agent);
  }

  @Override
  public List<String> transcriptOf(AgentWorktree agent, String sessionId) {
    return transcriptOf(agent.repositoryId, agent, sessionId);
  }

  private List<ArchivedSessionDto> sessionsFor(String wrapperRepositoryId, AgentWorktree agent) {
    return merge(shipped.sessionsFor(agent), mounted.sessionsFor(wrapperRepositoryId, agent));
  }

  /** {@code shipped} ∪ {@code mounted}, oldest first; a session id both hold is the shipped one. */
  static List<ArchivedSessionDto> merge(
      List<ArchivedSessionDto> shipped, List<ArchivedSessionDto> mounted) {
    List<ArchivedSessionDto> sessions = new ArrayList<>(shipped);
    Set<String> held = new HashSet<>();
    sessions.forEach(session -> held.add(session.sessionId()));
    for (ArchivedSessionDto session : mounted) {
      if (held.add(session.sessionId())) {
        sessions.add(session);
      }
    }
    sessions.sort(
        Comparator.comparing(
                ArchivedSessionDto::startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ArchivedSessionDto::sessionId));
    return List.copyOf(sessions);
  }

  /**
   * The shipped transcript when the store lists the id, else the mounted one — which answers a 404
   * for an id it does not attribute either, so an unknown id is never joined onto a path by
   * anybody.
   */
  private List<String> transcriptOf(
      String wrapperRepositoryId, AgentWorktree agent, String sessionId) {
    if (shipped.sessionsFor(agent).stream()
        .anyMatch(session -> session.sessionId().equals(sessionId))) {
      return shipped.transcriptOf(agent, sessionId);
    }
    return mounted.transcriptOf(wrapperRepositoryId, agent, sessionId);
  }

  private List<AgentWorktree> agentsOf(Workspace workspace) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findByWorkspace(workspace.id));
  }
}
