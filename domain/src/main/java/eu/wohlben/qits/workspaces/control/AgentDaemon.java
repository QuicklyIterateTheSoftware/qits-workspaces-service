package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The workspace daemon's per-agent surface (qits-1152): {@code agent-worktrees} on its HTTP API,
 * reached through the workspace's reverse tunnel. A port so {@code domain} stays free of Vert.x;
 * {@code daemonhost/DaemonAgentClient} is the transport. Absent is a supported configuration: no
 * agent is ever started, and the rows wait.
 *
 * <p>Every call answers rather than throws: an unreachable daemon is an outcome the scheduler reads
 * and retries on its next pass.
 */
public interface AgentDaemon {

  /** What a start needs: the agent, its work item and branch, its credential and its first turn. */
  record Start(
      String agentId,
      String workId,
      String entityId,
      String wrapperBranch,
      String harness,
      String sessionId,
      Map<String, String> env,
      String instruction,
      EntityFacts facts,
      String blockSource) {}

  /** How a call went. */
  enum Outcome {
    /** Done. */
    OK,
    /** The daemon does not know the agent (a restart since its start): start it again. */
    UNKNOWN_AGENT,
    /** The daemon refused (409): another worktree holds the branch, or no harness sign-in. */
    REFUSED,
    /** No tunnel, a timeout or a 5xx: try again later. */
    UNREACHABLE
  }

  /** A start's answer. */
  record Started(Outcome outcome, String commandId, String sessionId, boolean resumed, String detail) {

    public static Started of(Outcome outcome, String detail) {
      return new Started(outcome, null, null, false, detail);
    }
  }

  /** The cleanup check (D5): {@code clean}, the dirty repositories and the unpushed branches. */
  record CleanupCheck(boolean clean, List<String> dirty, List<Unpushed> unpushed) {

    /** Commits on {@code branch} (null: a detached HEAD) of {@code repository} that never left. */
    public record Unpushed(String repository, String branch, int commits) {}
  }

  /** Whether the workspace's daemon can be reached at all (its tunnel is up). */
  boolean reachable(Long workspaceRowId);

  /** {@code POST agent-worktrees}: idempotent; starts or resumes the harness. */
  Started start(Long workspaceRowId, Start start);

  /** {@code POST agent-worktrees/{id}/yield}: stops the harness, keeps everything. */
  Outcome yield(Long workspaceRowId, String agentId);

  /** {@code POST agent-worktrees/{id}/turn}: a user turn, restarting a stopped harness first. */
  Outcome turn(Long workspaceRowId, String agentId, String text);

  /** {@code POST agent-worktrees/{id}/entity}: the work item's title, status and blocked flag. */
  Outcome entity(Long workspaceRowId, String agentId, EntityFacts facts, String blockSource);

  /** {@code GET agent-worktrees/{id}/cleanup-check}; empty when the daemon could not be asked. */
  Optional<CleanupCheck> cleanupCheck(Long workspaceRowId, String agentId);

  /**
   * {@code DELETE agent-worktrees/{id}}. {@link Outcome#REFUSED} means leftover work and {@code
   * force} false; {@link Outcome#UNKNOWN_AGENT} means there is nothing to remove.
   */
  Outcome remove(Long workspaceRowId, String agentId, boolean force);
}
