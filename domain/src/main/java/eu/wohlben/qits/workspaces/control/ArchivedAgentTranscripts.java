package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import java.util.List;

/**
 * The agent sessions that ran in a workspace, read back after the workspace itself is gone.
 *
 * <p><b>One source: the transcript store (qits-1752).</b> A workspace-daemon ships each of its own
 * agents' transcripts over the control socket into this service's transcript store, one directory
 * per agent. Resolving a workspace destroys its container and its {@code /workspace} volume and
 * leaves that store alone, so an agent's sessions outlive the workspace that ran it. This port is
 * the read side of that survival — the one part of a resolved workspace's record that is not a
 * database row.
 *
 * <p><b>Attribution is the daemon's.</b> It ships only its own agents' files, and the store keeps
 * them under the agent's id, so every session in an agent's directory is that agent's. A slot
 * workspace's sessions are its agents'; an old-model workspace (a branch of its own, no agents) has
 * none.
 *
 * <p>Injected as {@code Instance<ArchivedAgentTranscripts>}: an absent port answers with an empty
 * list. An empty list is also the honest answer for a workspace where no agent ever ran, or whose
 * daemon never shipped, so a caller must not read it as an error.
 */
public interface ArchivedAgentTranscripts {

  /**
   * The workspace's sessions, agent by agent. Empty when there are none — never an exception, and
   * never a partial failure the caller has to distinguish.
   */
  List<ArchivedSessionDto> sessionsFor(Workspace workspace);

  /**
   * The session's raw JSONL lines: the harness's own envelopes verbatim, followed by each
   * subagent's sidechain introduced by a synthetic {@code qits_agent_meta} marker line.
   *
   * <p>{@code sessionId} is a caller-supplied string and must never reach a path. It is resolved
   * against the set {@link #sessionsFor} returns for this workspace, and anything else is a 404:
   * the store holds every agent's transcripts, so a traversal here reads another's.
   */
  List<String> transcriptOf(Workspace workspace, String sessionId);

  /**
   * One agent's sessions, oldest first (epic qits-1667): every session its daemon shipped into the
   * agent's own directory of the store. A REMOVED agent's sessions still answer.
   */
  List<ArchivedSessionDto> sessionsFor(AgentWorktree agent);

  /**
   * One of the agent's sessions, raw, under the rule {@link #transcriptOf(Workspace, String)} keeps:
   * a session that is not the agent's is a 404, as one that never existed.
   */
  List<String> transcriptOf(AgentWorktree agent, String sessionId);
}
