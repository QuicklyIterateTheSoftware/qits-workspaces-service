package eu.wohlben.qits.workspaces.daemonhost;

import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.transcripthost.ShippedClaudeTranscripts;
import eu.wohlben.qits.workspaces.transcripthost.TranscriptStore;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptChunk;
import eu.wohlben.qits.workspacedaemon.protocol.TranscriptResync;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A workspace-daemon's {@link TranscriptChunk} into the transcript store (qits-1752, capability 13),
 * and the {@link TranscriptResync} to answer it with when it did not start where the store ends.
 *
 * <p><b>Whose transcript it is, is decided here and not by the frame.</b> The agent is the frame's
 * {@code agentId} when it names one, else the agent running the frame's {@code commandId}; either
 * way the chunk is accepted only when that agent is placed in, or was last started in, the
 * workspace whose control socket it arrived on — the daemon's own row, which the socket binding
 * already ties to that daemon's credential. Anything else is dropped, logged, and never written: a
 * daemon may ship only its own agents' sessions, so one workspace cannot write into another
 * agent's history. {@code startedWorkspaceId} counts because that is where the agent's worktree,
 * and so its harness's files, still live after it was requeued.
 *
 * <p>Synchronous and blocking (a query and a file append): {@link WorkspaceDaemonRegistry} calls it
 * on a thread of its own, never the socket's, in arrival order.
 */
@ApplicationScoped
public class TranscriptIngest {

  private static final Logger LOG = Logger.getLogger(TranscriptIngest.class);

  @Inject AgentWorktreeRepository agents;

  @Inject ShippedClaudeTranscripts transcripts;

  /**
   * Append the chunk for an agent of {@code workspaceId}, or say where the daemon should resume.
   *
   * @return the resync to send back when the chunk's offset is not the size held; empty when it
   *     was appended, or dropped as not this workspace's to send
   */
  public Optional<TranscriptResync> accept(Long workspaceId, TranscriptChunk chunk) {
    Optional<UUID> agent = agentOf(workspaceId, chunk);
    if (agent.isEmpty()) {
      return Optional.empty();
    }
    TranscriptStore.Appended appended;
    try {
      appended =
          transcripts
              .store()
              .append(agent.get(), chunk.sessionId(), chunk.file(), chunk.offset(), chunk.data());
    } catch (IllegalArgumentException refused) {
      LOG.warnf(
          "Dropped a transcript chunk from workspace %s for agent %s: %s",
          workspaceId, agent.get(), refused.getMessage());
      return Optional.empty();
    } catch (IOException e) {
      LOG.warnf(
          e,
          "Could not store a transcript chunk from workspace %s (agent %s, session %s, %s)",
          workspaceId,
          agent.get(),
          chunk.sessionId(),
          chunk.file());
      return Optional.empty();
    }
    if (appended.appended()) {
      return Optional.empty();
    }
    LOG.debugf(
        "Transcript chunk from workspace %s for agent %s (session %s, %s) starts at %d; %d held,"
            + " resyncing",
        workspaceId, agent.get(), chunk.sessionId(), chunk.file(), chunk.offset(), appended.size());
    return Optional.of(new TranscriptResync(chunk.sessionId(), chunk.file(), appended.size()));
  }

  /** The chunk's agent, when it is one of {@code workspaceId}'s; empty (and logged) otherwise. */
  private Optional<UUID> agentOf(Long workspaceId, TranscriptChunk chunk) {
    String named = chunk.agentId();
    Optional<AgentWorktree> agent;
    if (named != null && !named.isBlank()) {
      UUID id;
      try {
        id = UUID.fromString(named);
      } catch (IllegalArgumentException notAnAgent) {
        LOG.warnf("Dropped a transcript chunk from workspace %s naming no agent id: %s", workspaceId, named);
        return Optional.empty();
      }
      agent =
          QuarkusTransaction.requiringNew()
              .call(() -> agents.findByIdOptional(id))
              .filter(row -> belongsTo(row, workspaceId));
    } else {
      agent =
          QuarkusTransaction.requiringNew()
              .call(() -> agents.findByCommandInWorkspace(chunk.commandId(), workspaceId));
    }
    if (agent.isEmpty()) {
      // A frame naming no agent is normally a command no agent owns (the sign-in terminal): DEBUG.
      // One naming an agent that is not this workspace's is a daemon writing somebody else's
      // history, which is worth seeing.
      LOG.logf(
          named == null || named.isBlank() ? Logger.Level.DEBUG : Logger.Level.WARN,
          "Dropped a transcript chunk from workspace %s: agent %s / command %s is not one of its"
              + " agents (session %s)",
          workspaceId,
          named,
          chunk.commandId(),
          chunk.sessionId());
      return Optional.empty();
    }
    return Optional.of(agent.get().id);
  }

  private static boolean belongsTo(AgentWorktree agent, Long workspaceId) {
    return workspaceId.equals(agent.workspaceId) || workspaceId.equals(agent.startedWorkspaceId);
  }
}
