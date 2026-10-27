package eu.wohlben.qits.workspaces.control;

import java.util.List;
import java.util.Map;

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
      String blockSource,
      AgentConfig agentConfig) {}

  /**
   * The {@code qits-agent-config} repository to check the agent's own branch of out, so the daemon
   * can launch Claude with it (qits-1382). Null: the feature is off, and the daemon starts the
   * harness as before. {@code repositoryId} is this platform's id for that repository;
   * {@code repositoryPath} is {@code <projectId>/<repoName>}, what the daemon clones it under.
   */
  record AgentConfig(String repositoryId, String repositoryPath) {}

  /** How a call went. */
  enum Outcome {
    /** Done. */
    OK,
    /** The daemon does not know the agent (a restart since its start): start it again. */
    UNKNOWN_AGENT,
    /** The daemon refused (409): another worktree holds the branch, or no harness sign-in. */
    REFUSED,
    /** No tunnel, a timeout or a 5xx: try again later. */
    UNREACHABLE,
    /**
     * A removal without force refused (409) because the agent's harness still has background work
     * running — a background task or subagent after its main turn ended (qits-1233). Not leftover
     * work: try again once it is done.
     */
    BUSY
  }

  /**
   * A start's answer. {@code launched} is the daemon's flag: whether this start launched a
   * harness, with the instruction as its opening turn. False when the agent's harness still ran (a
   * restart of this service, a reconnect of the daemon): the daemon attached to it and ignored the
   * instruction, so the turn is still to be said (qits-1225).
   */
  record Started(
      Outcome outcome,
      String commandId,
      String sessionId,
      boolean resumed,
      boolean launched,
      String detail) {

    public static Started of(Outcome outcome, String detail) {
      return new Started(outcome, null, null, false, false, detail);
    }
  }

  /**
   * The cleanup check (D5): {@code clean}, the dirty repositories and the unpushed branches, and
   * whether the harness is {@code busy} with background work (qits-1233). {@code clean} is about
   * git only, so a busy agent is never described as holding leftover work; a daemon that does not
   * say {@code busy} reads as not busy.
   *
   * <p>{@code inRelease} and {@code delivered} are qits-1688. A commit the git host holds on a
   * branch or in a release tag, but not on the default branch yet, is in release: nothing is lost,
   * so it is not leftover work, but the work is not delivered until its release reaches the
   * default branch. {@code delivered} is {@code clean} with nothing in release. A daemon that does
   * not say it reads as {@code delivered = clean}, as before.
   */
  record CleanupCheck(
      boolean clean,
      List<String> dirty,
      List<Unpushed> unpushed,
      List<Unpushed> inRelease,
      boolean delivered,
      boolean busy) {

    /** A check with nothing in release. */
    public CleanupCheck(boolean clean, List<String> dirty, List<Unpushed> unpushed, boolean busy) {
      this(clean, dirty, unpushed, List.of(), clean, busy);
    }

    /** A check that says nothing about background work: not busy. */
    public CleanupCheck(boolean clean, List<String> dirty, List<Unpushed> unpushed) {
      this(clean, dirty, unpushed, false);
    }

    /** Commits on {@code branch} (null: a detached HEAD) of {@code repository} that never left. */
    public record Unpushed(String repository, String branch, int commits) {}
  }

  /**
   * The cleanup check's answer (qits-1233), one of three: {@link Outcome#OK} with the {@code
   * check}; {@link Outcome#UNKNOWN_AGENT}, the daemon answered 404 because it no longer knows the
   * agent (its worktree and harness are already gone); or {@link Outcome#UNREACHABLE} — no tunnel,
   * no answer, a 5xx or any other answer that is no check — with what happened in {@code detail}.
   */
  record CleanupAnswer(Outcome outcome, CleanupCheck check, String detail) {

    public static CleanupAnswer found(CleanupCheck check) {
      return new CleanupAnswer(Outcome.OK, check, null);
    }

    public static CleanupAnswer unknownAgent() {
      return new CleanupAnswer(Outcome.UNKNOWN_AGENT, null, "the daemon does not know the agent");
    }

    public static CleanupAnswer unreachable(String detail) {
      return new CleanupAnswer(Outcome.UNREACHABLE, null, detail);
    }

    public boolean isFound() {
      return outcome == Outcome.OK && check != null;
    }

    public boolean isUnknownAgent() {
      return outcome == Outcome.UNKNOWN_AGENT;
    }
  }

  /** Whether the workspace's daemon can be reached at all (its tunnel is up). */
  boolean reachable(Long workspaceRowId);

  /** {@code POST agent-worktrees}: idempotent; starts or resumes the harness. */
  Started start(Long workspaceRowId, Start start);

  /** {@code POST agent-worktrees/{id}/turn}: a user turn, restarting a stopped harness first. */
  Outcome turn(Long workspaceRowId, String agentId, String text);

  /** {@code POST agent-worktrees/{id}/entity}: the work item's title, status and blocked flag. */
  Outcome entity(Long workspaceRowId, String agentId, EntityFacts facts, String blockSource);

  /** {@code GET agent-worktrees/{id}/cleanup-check}: found, unknown agent (404) or unreachable. */
  CleanupAnswer cleanupCheck(Long workspaceRowId, String agentId);

  /**
   * {@code DELETE agent-worktrees/{id}}. {@link Outcome#REFUSED} means leftover work and {@code
   * force} false; {@link Outcome#BUSY} a refusal because the harness has background work running;
   * {@link Outcome#UNKNOWN_AGENT} means there is nothing to remove.
   */
  Outcome remove(Long workspaceRowId, String agentId, boolean force);
}
