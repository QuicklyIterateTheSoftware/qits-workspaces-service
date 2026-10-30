package eu.wohlben.qits.workspaces.transcripthost;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The transcript store as a bean (qits-1752): the configured root, the {@link TranscriptStore} the
 * control socket's chunks are appended to, and the read of one agent's shipped sessions.
 *
 * <p>Deliberately thin, as {@link MountedClaudeTranscripts} is: the append rule is {@link
 * TranscriptStore}'s and the listing and rendering are {@link ClaudeTranscriptArchive}'s, both
 * framework-free and tested as plain JUnit.
 */
@ApplicationScoped
public class ShippedClaudeTranscripts {

  /** {@code <workspaces data dir>/transcripts} unless moved: this service's own volume. */
  @ConfigProperty(name = "qits.workspace.transcript-store-root")
  String storeRoot;

  private volatile TranscriptStore store;

  public TranscriptStore store() {
    TranscriptStore existing = store;
    if (existing == null) {
      existing = new TranscriptStore(Path.of(storeRoot));
      store = existing;
    }
    return existing;
  }

  /** Every session the agent's daemon shipped, oldest first; empty when it shipped none. */
  public List<ArchivedSessionDto> sessionsFor(AgentWorktree agent) {
    return archiveOf(agent).sessions();
  }

  /** One shipped session, raw; a 404 for an id not among {@link #sessionsFor}. */
  public List<String> transcriptOf(AgentWorktree agent, String sessionId) {
    return archiveOf(agent).transcriptOf(sessionId);
  }

  private ClaudeTranscriptArchive archiveOf(AgentWorktree agent) {
    return ClaudeTranscriptArchive.ofDirectory(store().agentDirectory(agent.id));
  }
}
