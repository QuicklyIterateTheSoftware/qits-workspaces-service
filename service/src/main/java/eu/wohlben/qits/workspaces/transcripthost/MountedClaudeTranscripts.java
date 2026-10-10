package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;

import eu.wohlben.qits.workspaces.control.ArchivedAgentTranscripts;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@link ArchivedAgentTranscripts} backed by the shared harness volume mounted into this service.
 *
 * <p><b>Deliberately almost empty.</b> Everything that could be wrong — which sessions belong to
 * which workspace, how a session id is resolved without touching a path, what a missing directory
 * means — is in {@link ClaudeTranscriptArchive}, which has no framework in it and is tested as plain
 * JUnit against a temp directory. This class supplies the one configured value and adapts a {@code
 * Workspace} row into the three facts attribution actually needs. If it ever grows a rule, the rule
 * is in the wrong file.
 *
 * <p><b>It resolves even when nothing is mounted, and that is the point.</b> {@code
 * WorkspaceHistoryService} injects the port as {@code Instance<>} so a deployment without an
 * implementation degrades instead of breaking; but "the volume is not mounted" is not the same as
 * "there is no implementation", and today it is the normal case — applying {@code mounts[1]} needs
 * an operator. So the bean exists unconditionally and answers with an empty list when the root is
 * absent, which is exactly the answer a workspace where no agent ever ran gets. A caller must not
 * read an empty list as a fault.
 */
@ApplicationScoped
public class MountedClaudeTranscripts implements ArchivedAgentTranscripts {

  /**
   * Where <em>this service's</em> container sees the shared volume — not {@code
   * qits.workspace.claude-mount}, which is where a WORKSPACE container sees the same volume. Two
   * mounts of one volume: writable there, read-only here. They share a default because the
   * conventional path is convenient on both sides, and either may move without the other.
   */
  @ConfigProperty(name = "qits.workspace.claude-archive-root")
  String archiveRoot;

  private volatile ClaudeTranscriptArchive archive;

  private ClaudeTranscriptArchive archive() {
    ClaudeTranscriptArchive existing = archive;
    if (existing == null) {
      existing = new ClaudeTranscriptArchive(Path.of(archiveRoot));
      archive = existing;
    }
    return existing;
  }

  /** The agents of a slot workspace, whose transcripts are keyed on their own directories. */
  @Inject AgentWorktreeRepository agents;

  @Inject RepositoryLookup repositories;

  /**
   * An old-model workspace's sessions, keyed on {@code /workspace} and its branch; a slot's
   * (qits-1152) are its agents', each keyed on {@code /workspace/agents/<id>/<wrapper>} and
   * attributed by its wrapper branch.
   */
  @Override
  public List<ArchivedSessionDto> sessionsFor(Workspace workspace) {
    if (workspace.branch != null) {
      return archive().sessionsFor(attribution(workspace));
    }
    List<ArchivedSessionDto> sessions = new java.util.ArrayList<>();
    for (AgentWorktree agent : agentsOf(workspace)) {
      sessions.addAll(agentArchive(workspace, agent).sessionsFor(attribution(agent)));
    }
    return sessions;
  }

  @Override
  public List<String> transcriptOf(Workspace workspace, String sessionId) {
    if (workspace.branch != null) {
      return archive().transcriptOf(attribution(workspace), sessionId);
    }
    for (AgentWorktree agent : agentsOf(workspace)) {
      ClaudeTranscriptArchive archive = agentArchive(workspace, agent);
      ClaudeTranscriptArchive.Attribution attribution = attribution(agent);
      if (archive.sessionsFor(attribution).stream().anyMatch(s -> s.sessionId().equals(sessionId))) {
        return archive.transcriptOf(attribution, sessionId);
      }
    }
    throw new NotFoundException("Agent session not found: " + sessionId);
  }

  private List<AgentWorktree> agentsOf(Workspace workspace) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findByWorkspace(workspace.id));
  }

  private ClaudeTranscriptArchive agentArchive(Workspace workspace, AgentWorktree agent) {
    String name;
    try {
      name = repositories.find(workspace.repositoryId).map(RepositoryLookup.RepositoryView::name).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      name = null;
    }
    String cwd = "/workspace/agents/" + agent.id + (name == null ? "" : "/" + name);
    return new ClaudeTranscriptArchive(Path.of(archiveRoot), cwd);
  }

  private static ClaudeTranscriptArchive.Attribution attribution(AgentWorktree agent) {
    return new ClaudeTranscriptArchive.Attribution(
        agent.wrapperBranch, agent.createdAt, agent.removedAt);
  }

  /**
   * The row reduced to what attribution reads: the branch the agent worked on, and the window the
   * workspace was alive for. {@code resolvedAt} is null while it still is, which the archive reads
   * as "up to now" — so a live workspace's sessions appear as they are written.
   */
  private static ClaudeTranscriptArchive.Attribution attribution(Workspace workspace) {
    return new ClaudeTranscriptArchive.Attribution(
        workspace.branch, workspace.createdAt, workspace.resolvedAt);
  }
}
