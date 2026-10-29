package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a workspace slot would lose if its container went away now (qits-1263): the work its agents
 * hold in their worktrees that never reached the git host. The one question every destructive
 * workspace verb asks — recreate, abandon, integrate's source check, branch cleanup — and the same
 * question an agent's own discard asks of one agent ({@link AgentService#discard}).
 *
 * <p>It replaces the slot-wide clean/dirty a {@code GitStatus} frame used to carry. Since daemon
 * protocol 10 (qits-1152) the daemon never sends that frame — agents work in worktrees of their
 * own, and the wrapper checkout itself is not anybody's work — so the cache it filled stayed
 * "unknown" forever and refused every recreate. The daemon answers per agent instead, through
 * {@link AgentDaemon#cleanupCheck}, and this asks it once for every agent hosted on the slot.
 *
 * <p>The answer fails closed where it can: a slot with live agents whose daemon cannot be asked
 * (no tunnel, no {@link AgentDaemon} bean, a check that went unanswered) is a refusal, because an
 * unknowable worktree is not a safe basis for destroying a container. A slot with no live agent has
 * nothing to lose and passes whatever its daemon's state. An agent the daemon does not know (404)
 * has no worktree there, so it holds nothing either, and one that never started on the slot ({@link
 * #mayHoldWorktree}) is not asked at all (qits-1750). A harness still busy in the background is a
 * refusal too: tearing its container down kills that work mid-flight.
 *
 * <p>"Live" is ACTIVE <em>and</em> OBSOLETE: an obsolete agent's worktree stays on the slot until
 * the cleanup sweep has checked and removed it, and leftover work there is exactly what the D7
 * prompt exists to rescue.
 *
 * <p>Its own bean, not a method on {@link AgentService}, so {@link WorkspaceService} can inject it:
 * {@code AgentService} already injects {@code WorkspaceService}, and this needs nothing of either.
 */
@ApplicationScoped
public class AgentWorktreeGuard {

  /** Why a slot is refused: the daemon could not be asked about a live agent. */
  public static final String DAEMON_UNREACHABLE = "AGENT_DAEMON_UNREACHABLE";

  /** Why a slot is refused: an agent's harness still works in the background. */
  public static final String BUSY = "AGENT_BUSY";

  /** Why a slot is refused: an agent's worktrees hold uncommitted or unpushed work. */
  public static final String LEFTOVER_WORK = "AGENT_HAS_LEFTOVER_WORK";

  @Inject AgentWorktreeRepository agents;

  /** Absent is supported: then no live agent can be checked, and a slot holding one is refused. */
  @Inject Instance<AgentDaemon> daemon;

  /**
   * Why {@code operation} may not destroy the slot's container, or empty when it may. {@code code}
   * is one of {@link #LEFTOVER_WORK}, {@link #BUSY} and {@link #DAEMON_UNREACHABLE}, the most
   * serious that applies, and {@code message} names every agent that stands in the way.
   */
  public record Refusal(String code, String message) {}

  /**
   * The one cleanup check of one agent, as {@link AgentService#discard} and this guard both ask it:
   * an unreachable answer, with no call, when the daemon's tunnel is down or there is no client.
   */
  public AgentDaemon.CleanupAnswer check(Long workspaceRowId, String agentId) {
    if (!daemon.isResolvable()) {
      return AgentDaemon.CleanupAnswer.unreachable("no daemon client is configured");
    }
    AgentDaemon port = daemon.get();
    return port.reachable(workspaceRowId)
        ? port.cleanupCheck(workspaceRowId, agentId)
        : AgentDaemon.CleanupAnswer.unreachable("the daemon is not connected");
  }

  /** Whether the slot's container can go with nothing of any agent's lost. */
  public boolean nothingToLose(Long workspaceRowId) {
    return refusal(workspaceRowId, null, "remove").isEmpty();
  }

  /**
   * Asks the daemon about every live agent on the slot and answers why {@code operation} on
   * workspace {@code label} must not proceed, or empty when nothing would be lost.
   */
  public Optional<Refusal> refusal(Long workspaceRowId, String label, String operation) {
    List<AgentWorktree> live =
        QuarkusTransaction.requiringNew().call(() -> agents.findLiveByWorkspace(workspaceRowId));
    if (live.isEmpty()) {
      return Optional.empty();
    }
    List<String> leftover = new ArrayList<>();
    List<String> busy = new ArrayList<>();
    List<String> unchecked = new ArrayList<>();
    for (AgentWorktree agent : live) {
      if (!mayHoldWorktree(agent, workspaceRowId)) {
        // Never started here (qits-1750): nothing of it is on the slot, so its daemon need not be
        // asked — and one that connected but never answered must not hold the slot hostage.
        continue;
      }
      AgentDaemon.CleanupAnswer answer = check(workspaceRowId, agent.id.toString());
      if (answer.isUnknownAgent()) {
        continue;
      }
      if (!answer.isFound()) {
        unchecked.add(name(agent) + " (" + answer.detail() + ")");
      } else if (answer.check().busy()) {
        busy.add(name(agent));
      } else if (!answer.check().clean()) {
        leftover.add(name(agent) + ": " + AgentService.describe(answer.check()));
      }
    }
    String subject =
        "Cannot "
            + operation
            + " workspace "
            + (label == null ? workspaceRowId : "'" + label + "'");
    if (!leftover.isEmpty()) {
      return Optional.of(
          new Refusal(
              LEFTOVER_WORK,
              subject
                  + ": its agents' worktrees hold work that never reached the git host — "
                  + String.join("; ", leftover)
                  + ". Release or discard that work, or discard the agent, first."));
    }
    if (!busy.isEmpty()) {
      return Optional.of(
          new Refusal(
              BUSY,
              subject
                  + ": the harness of "
                  + String.join(", ", busy)
                  + " is still working in the background (a subagent, a monitor or a background"
                  + " shell). Wait for it to finish first."));
    }
    if (!unchecked.isEmpty()) {
      return Optional.of(
          new Refusal(
              DAEMON_UNREACHABLE,
              subject
                  + ": its daemon cannot be asked whether the worktrees of "
                  + String.join(", ", unchecked)
                  + " hold unpushed work. Wait for the daemon to connect, or discard "
                  + (unchecked.size() == 1 ? "the agent" : "those agents")
                  + ", first."));
    }
    return Optional.empty();
  }

  /**
   * Whether the agent's worktree may live in workspace {@code workspaceRowId}'s volume (qits-1233):
   * a start was accepted there last, or — a row from before {@code started_workspace_id} — it was
   * started somewhere (it holds a command id) and no start was recorded since. The one rule both
   * this guard and {@link AgentService}'s FAILED-slot requeue read (qits-1750).
   */
  static boolean mayHoldWorktree(AgentWorktree agent, Long workspaceRowId) {
    return agent.startedWorkspaceId == null
        ? agent.commandId != null
        : agent.startedWorkspaceId.equals(workspaceRowId);
  }

  /** The agent as a person finds it: its id and its work item. */
  private static String name(AgentWorktree agent) {
    String item =
        agent.entityId != null && !agent.entityId.isBlank() ? agent.entityId : agent.workId;
    return "agent " + agent.id + (item == null ? "" : " (work item " + item + ")");
  }
}
