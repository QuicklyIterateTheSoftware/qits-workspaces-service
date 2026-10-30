package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.control.RepositoryLookup;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The agent sessions on the shared harness volume mounted into this service — the FALLBACK half of
 * {@link AgentTranscripts} since qits-1752.
 *
 * <p>A DIRECT (admin) workspace's harness writes to the platform host's {@code
 * qits_shared_dot_claude}, which is what is mounted here, and so did every workspace before its
 * daemon shipped transcripts. A TARGET workspace's harness writes to its execution target's
 * volume, which this service cannot mount: those sessions come from {@link
 * ShippedClaudeTranscripts} instead, and this reader finds nothing for them.
 *
 * <p><b>Deliberately almost empty.</b> Everything that could be wrong — which sessions belong to
 * which workspace, how a session id is resolved without touching a path, what a missing directory
 * means — is in {@link ClaudeTranscriptArchive}, which has no framework in it and is tested as plain
 * JUnit against a temp directory. This class supplies the one configured value and adapts a row
 * into the three facts attribution actually needs. If it ever grows a rule, the rule is in the
 * wrong file.
 *
 * <p><b>It resolves even when nothing is mounted</b>, and answers with an empty list when the root
 * is absent, which is exactly the answer a workspace where no agent ever ran gets. A caller must
 * not read an empty list as a fault.
 */
@ApplicationScoped
public class MountedClaudeTranscripts {
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

  @Inject RepositoryLookup repositories;

  /** An old-model workspace's sessions (it has a branch): keyed on {@code /workspace}. */
  public List<ArchivedSessionDto> sessionsFor(Workspace workspace) {
    return archive().sessionsFor(attribution(workspace));
  }

  /** One of an old-model workspace's sessions, raw; a 404 for one that does not attribute. */
  public List<String> transcriptOf(Workspace workspace, String sessionId) {
    return archive().transcriptOf(attribution(workspace), sessionId);
  }

  /**
   * One agent's sessions (qits-1152, epic qits-1667): keyed on its own directory {@code
   * /workspace/agents/<id>/<wrapper>} and attributed by its wrapper branch and its life.
   *
   * @param wrapperRepositoryId the repository whose name the worktree directory carries
   */
  public List<ArchivedSessionDto> sessionsFor(String wrapperRepositoryId, AgentWorktree agent) {
    return agentArchive(wrapperRepositoryId, agent).sessionsFor(attribution(agent));
  }

  /** One of the agent's sessions, raw; a 404 for one that does not attribute to it. */
  public List<String> transcriptOf(
      String wrapperRepositoryId, AgentWorktree agent, String sessionId) {
    return agentArchive(wrapperRepositoryId, agent).transcriptOf(attribution(agent), sessionId);
  }

  /** The agent's worktree directory, keyed on the wrapper's name: where its harness ran. */
  private ClaudeTranscriptArchive agentArchive(String wrapperRepositoryId, AgentWorktree agent) {
    String name;
    try {
      name = repositories.find(wrapperRepositoryId).map(RepositoryLookup.RepositoryView::name).orElse(null);
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
