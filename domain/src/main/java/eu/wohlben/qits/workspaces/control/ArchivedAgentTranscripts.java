package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import java.util.List;

/**
 * The agent sessions that ran in a workspace, read back from the shared harness volume after the
 * workspace itself is gone.
 *
 * <p>Resolving a workspace destroys its container and its {@code /workspace} volume; the shared
 * {@code qits_shared_dot_claude} volume is never touched, so the harness's own JSONL transcripts
 * outlive the workspace that produced them. This port is the read side of that survival — the one
 * part of a resolved workspace's record that is not a database row.
 *
 * <p><b>The volume carries no workspace identity.</b> Every workspace container runs the agent with
 * cwd {@code /workspace}, and the harness keys its transcript directory on the escaped cwd, so
 * every session the estate has ever run lands in a single flat directory. What attributes a session
 * to a workspace is therefore the {@code gitBranch} recorded inside the transcript, joined against
 * the workspace's own branch and its {@code createdAt}/{@code resolvedAt} window — a branch name
 * alone is not enough, because a branch is reusable once its workspace resolves and two rows can
 * legitimately carry the same one at different times.
 *
 * <p>Injected as {@code Instance<ArchivedAgentTranscripts>}: a deployment without the volume
 * mounted is ABSENT rather than broken, and an absent port answers with an empty list. An empty
 * list is also the honest answer for a workspace where no agent ever ran, or one resolved before
 * the mount existed, so a caller must not read it as an error.
 */
public interface ArchivedAgentTranscripts {

  /**
   * The workspace's sessions, oldest first. Empty when nothing attributes — never an exception, and
   * never a partial failure the caller has to distinguish.
   */
  List<ArchivedSessionDto> sessionsFor(Workspace workspace);

  /**
   * The session's raw JSONL lines: the harness's own envelopes verbatim, followed by each
   * subagent's sidechain introduced by a synthetic {@code qits_agent_meta} marker line.
   *
   * <p>{@code sessionId} is a caller-supplied string and must never reach a path. It is resolved
   * against the set {@link #sessionsFor} returns for this workspace, and anything else is a 404:
   * the same volume holds the estate-wide harness credential and history, so a traversal here reads
   * them.
   */
  List<String> transcriptOf(Workspace workspace, String sessionId);
}
