package eu.wohlben.qits.workspaces.transcripthost;

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

  @Override
  public List<ArchivedSessionDto> sessionsFor(Workspace workspace) {
    return archive().sessionsFor(attribution(workspace));
  }

  @Override
  public List<String> transcriptOf(Workspace workspace, String sessionId) {
    return archive().transcriptOf(attribution(workspace), sessionId);
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
