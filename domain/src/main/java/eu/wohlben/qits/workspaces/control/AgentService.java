package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.dto.WorkItemWorkspaceDto;
import eu.wohlben.qits.workspaces.entity.AgentBranch;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentRunState;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.entity.AgentWorktree;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.events.AgentCleanupDeferred;
import eu.wohlben.qits.workspaces.events.AgentHarnessExited;
import eu.wohlben.qits.workspaces.events.AgentObsolete;
import eu.wohlben.qits.workspaces.events.AgentPlaced;
import eu.wohlben.qits.workspaces.events.AgentRemoved;
import eu.wohlben.qits.workspaces.events.AgentRequeued;
import eu.wohlben.qits.workspaces.events.AgentResumed;
import eu.wohlben.qits.workspaces.events.AgentStartRefused;
import eu.wohlben.qits.workspaces.events.AgentStarted;
import eu.wohlben.qits.workspaces.events.AgentYielded;
import eu.wohlben.qits.workspaces.events.WorkspaceRemoved;
import eu.wohlben.qits.workspaces.persistence.AgentBranchRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWaitRepository;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptAttachmentRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspacePromptDraftRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>Agents, and the workspaces they run in</b> (qits-1152, decisions D2–D7, D10, D14–D18, D22,
 * D24 of the superproject's {@code agent-workspace-decoupling-plan.md}).
 *
 * <p>A workspace is a <em>slot</em>: one container on a runner, scoped to a project's wrapper. An
 * agent is one work item's coding agent and its worktree inside a workspace. A workspace hosts many
 * agents. An agent waiting for input yields its slot when another agent wants it: its harness keeps
 * running, and the workspace takes one more agent. It is RUNNING again once it has something to
 * hear or works again (qits-1212).
 *
 * <h2>The doors write rows; one scheduler acts on the daemons</h2>
 *
 * <p>Every door here — dispatch, delivery, entity, a person's create, a release's resolution —
 * writes the agent row and answers at once. The daemon calls (start, turn, cleanup check,
 * remove) are made by {@link #reconcile}, one pass at a time on one thread, kicked after every door
 * and run on a timer by {@code wiring/AgentSweep}. So two calls about one agent never race, and
 * what is still to be done is always a row: a restart loses nothing.
 *
 * <p>A pass, in order:
 *
 * <ol>
 *   <li><b>Resume</b>: every YIELDED agent that has something to hear is RUNNING again at once,
 *       even beside another RUNNING agent — a resume goes before a new agent (D17).
 *   <li><b>Place</b>: each QUEUED agent goes to an empty workspace of its wrapper and posture, else
 *       to a new workspace while the runners have free slots (an admin agent: a new DIRECT admin
 *       workspace), else to a workspace whose agents have all yielded, else it stays QUEUED.
 *   <li><b>Yield</b>: a RUNNING agent that has waited for input longer than {@code
 *       qits.workspace.agent.yield-after} gives up its slot when another agent wants it. Only the
 *       row changes: no daemon call, and its harness keeps running.
 *   <li><b>Drive</b>: a RUNNING agent whose daemon answers is started (again after a daemon restart:
 *       its credential lives only in daemon memory) and is told what it has still to hear. A placed
 *       YIELDED agent is started too when this process has not started it on its daemon's current
 *       connection — after a reconnect or a restart of this service — but only started: it stays
 *       YIELDED and hears nothing until it is RUNNING again (qits-1212). A RUNNING agent whose
 *       workspace is FAILED with no daemon — it never came up — is queued for another workspace,
 *       the reason stored as its start refusal, unless it started there: then its worktree is in
 *       that volume and it waits for the workspace (qits-1233).
 *   <li><b>Clean up</b> (D4, D5, D7): an OBSOLETE agent whose work item is closed (VERIFIED,
 *       DONE or DROPPED) and that has been idle for {@code qits.workspace.agent.cleanup-idle} is
 *       removed if its worktrees are clean and pushed; otherwise it is handed the cleanup prompt
 *       instead.
 *   <li><b>Remove empty workspaces</b> (D2).
 * </ol>
 */
@ApplicationScoped
public class AgentService {

  private static final Logger LOG = Logger.getLogger(AgentService.class);

  /** The D7 prompt, ahead of the list of what the check found. */
  static final String CLEANUP_PROMPT =
      "Your work item is released, so this worktree is about to be removed, but the cleanup check"
          + " found work in it that never reached the git host. Either release these changes if"
          + " they were meant to be part of your work, or discard them. Only in special and rare"
          + " cases, if they are blocked somehow, create a new ticket and push them under that"
          + " workId. Say explicitly what you committed, why, and why it has not been released yet.";

  /** Why the sweep leaves an agent whose harness works in the background (qits-1233). */
  private static final String BUSY_IN_BACKGROUND = "busy: its harness has background work running";

  /** The statuses that end an item (D4): the cleanup sweep removes a closed item's OBSOLETE agents. */
  private static final Set<String> CLOSING_STATUSES = Set.of("VERIFIED", "DONE", "DROPPED");

  /** A branch name git and the daemon both take: no leading dash, no spaces or control characters. */
  private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

  /** What a dispatch did about the agent. */
  public enum AgentLaunch {
    /** The agent has a slot; it starts (or hears the instruction) once its daemon answers. */
    SCHEDULED,
    /** The agent was already running; nothing was started and the instruction was not sent. */
    SKIPPED_RUNNING,
    /** No slot is free; the agent waits for one. */
    QUEUED
  }

  /** What a request to create an agent says (the dispatch door and a person's door). */
  public record AgentRequest(
      String repositoryId,
      String wrapperBranch,
      String workId,
      String entityId,
      String ticketId,
      String epicId,
      boolean admin,
      AgentHarness harness,
      String instruction,
      EntityFacts facts) {}

  /**
   * The dispatch door's answer.
   *
   * @param agent the agent, fresh or found
   * @param fresh whether this call created it; false on a re-press, which is not an error
   * @param agentIdentity the principal the agent's own calls carry: its token's subject; null
   *     while it holds none
   */
  public record Dispatch(AgentDto agent, boolean fresh, AgentLaunch agentLaunch, String agentIdentity) {}

  /**
   * The delivery door's answer. The flags say which arm the call took, read from the agent's state;
   * the turn itself is said by the scheduler. All three false with a null {@code agentId}: nobody
   * works on the item.
   */
  public record Delivery(
      String agentId, boolean delivered, boolean launched, boolean resumed, String detail) {}

  /** The entity and blocked doors' answer: the agent the facts are stored on, and whether it heard them live. */
  public record EntityMark(String agentId, boolean applied) {}

  /** The released-branch door's answer: the agents marked OBSOLETE. */
  public record Resolution(boolean resolved, List<String> agentIds) {}

  @Inject AgentWorktreeRepository agents;

  @Inject AgentBranchRepository agentBranches;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceRunnerRepository runnerRepository;

  @Inject WorkspaceService workspaces;

  @Inject RepositoryLookup repositories;

  @Inject WorkspacePromptDraftRepository drafts;

  @Inject WorkspacePromptAttachmentRepository attachments;

  @Inject WorkspaceAddressPlanes addressPlanes;

  @Inject WorkspaceChangePublisher changePublisher;

  /** The daemons' agent surface. Absent: no agent is ever started. */
  @Inject Instance<AgentDaemon> daemon;

  /** The agent credential's issuer. Absent: agents carry no credential of their own. */
  @Inject Instance<CredentialCommissioner> commissioner;

  @Inject Instance<AgentActivityView> activity;

  @Inject Instance<WorkspaceDaemonLiveness> liveness;

  /** Declared waits (qits-1153): swept every pass; they also own the waiting relay to qits-projects. */
  @Inject AgentWaits waits;

  @Inject AgentWaitRepository waitRows;

  /**
   * The lifecycle events (qits-1236), each announced after the write it reports committed. Absent:
   * nothing is announced.
   */
  @Inject Instance<LifecycleAnnouncer> lifecycle;

  /**
   * How long a RUNNING agent waits for input before it yields its slot to another agent that wants
   * it — the blocked-detection time ({@code qits.projects.agent-waiting.debounce}, 30 s).
   */
  @ConfigProperty(name = "qits.workspace.agent.yield-after", defaultValue = "PT30S")
  Duration yieldAfter;

  /** A push the daemon reported, kept until its repository name maps to an id. */
  private record PendingPush(UUID agentId, String repositoryName, String branch, String sha, Instant pushedAt) {}

  /** Pushes whose repository qits-projects could not name yet; tried again every pass. */
  private final Queue<PendingPush> unresolvedPushes = new ConcurrentLinkedQueue<>();

  /** How long a push whose repository cannot be named is tried again before it is given up. */
  static final Duration PUSH_RETRY_LIMIT = Duration.ofHours(1);

  /** The cause relayed to qits-projects when a turn is typed into an agent that was waiting. */
  static final String CAUSE_TURN = "TurnDelivered";

  /** How long an OBSOLETE agent must be idle before the cleanup sweep looks at it (D4). */
  @ConfigProperty(name = "qits.workspace.agent.cleanup-idle", defaultValue = "PT30S")
  Duration cleanupIdle;

  /** How long a delivered turn waits for a busy agent's turn to end before it is said anyway. */
  @ConfigProperty(name = "qits.workspace.agent-dispatch.launch-window-ms", defaultValue = "900000")
  long turnWindowMs;

  /** Whether a delivery's {@code compactFirst} is honoured; off until somebody reads a transcript. */
  @ConfigProperty(name = "qits.workspace.agent-dispatch.compact-before-turn", defaultValue = "false")
  boolean compactBeforeTurn;

  /** A workspace with no agent ever assigned is removed after this long (a crash between create and assign). */
  private static final Duration ORPHAN_SLOT_GRACE = Duration.ofMinutes(2);

  /**
   * The agents started on the current connection of their workspace's daemon, by agent id, with
   * the workspace row. Cleared for a workspace whenever its daemon connects or goes: the daemon
   * keeps the agent credential in memory only, so after a restart every placed agent is started
   * again, a YIELDED one included (qits-1212). Empty in a fresh process for the same reason: a
   * start of an agent whose harness still runs launches nothing in the daemon, and ignores its
   * instruction, so the pending turn stays on the row for {@link #drive} to say (qits-1225).
   */
  private final Map<UUID, Long> started = new ConcurrentHashMap<>();

  /** Since when a pending turn waits for a busy agent, for {@link #turnWindowMs}. */
  private final Map<UUID, Instant> pendingSince = new ConcurrentHashMap<>();

  /** The last refusal logged per agent, so a refusing daemon is logged once and not every pass. */
  private final Map<UUID, String> lastRefusal = new ConcurrentHashMap<>();

  /**
   * The last reason the cleanup sweep logged for leaving an agent alone, so each is logged once and
   * not every pass (qits-1233). An agent leaves it when it is REMOVED.
   */
  private final Map<UUID, String> lastSkip = new ConcurrentHashMap<>();

  /** The first wait before an agent whose workspace failed again is placed again (qits-1233). */
  static final Duration REQUEUE_BACKOFF = Duration.ofMinutes(1);

  /** The longest wait before such an agent is placed again: it doubles up to this. */
  static final Duration REQUEUE_BACKOFF_MAX = Duration.ofMinutes(30);

  /** An agent taken out of a workspace that never came up: how often in a row, and when it may be placed. */
  private record Requeued(int times, Instant notBefore) {}

  /**
   * The agents taken out of a FAILED workspace (qits-1233), by id. The first time the agent is
   * placed again at once; every further time in a row waits {@link #REQUEUE_BACKOFF}, doubled up to
   * {@link #REQUEUE_BACKOFF_MAX}, so a runner that keeps failing is not handed a new workspace every
   * pass. An accepted start ends the row. In memory: a restart places it again at once.
   */
  private final Map<UUID, Requeued> requeued = new ConcurrentHashMap<>();

  /**
   * The queued agents whose wait for a runner slot was logged, so it is logged once and not every
   * pass (qits-1228). An agent leaves it when it is placed or no longer queued, so a re-queue logs
   * again and the set never outgrows the queue.
   */
  private final Set<UUID> waitingForCapacity = ConcurrentHashMap.newKeySet();

  private final ExecutorService pass =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "agent-scheduler");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicBoolean kicked = new AtomicBoolean();

  /**
   * Two locks, so a door never waits on a daemon. {@code passLock} serialises the scheduler's
   * passes, whose daemon calls can take a request timeout each; {@code placeLock} guards only
   * placement (resume and place), which the dispatch door also runs, and which makes no daemon call.
   */
  private final Object passLock = new Object();

  private final Object placeLock = new Object();

  @PreDestroy
  void shutdown() {
    pass.shutdownNow();
  }

  // --- the doors ----------------------------------------------------------------------------------

  /**
   * <b>There should be an agent working on this work item</b> — the dispatch door, said
   * idempotently. An ACTIVE agent of the item is the answer ({@code fresh:false}); so is its newest
   * OBSOLETE agent on the same wrapper branch, made ACTIVE again and told the instruction
   * (qits-1217); otherwise one is created and placed. Never a 409 for an existing agent: the caller
   * presses this to reach a state.
   *
   * @throws NotFoundException no such repository; nothing is created
   * @throws BadRequestException no work item, or a wrapper branch git will not take
   */
  public Dispatch dispatch(AgentRequest request) {
    String workId = trimmed(request.workId());
    if (workId == null) {
      throw new BadRequestException("A work item is required: every agent works on one (D24).");
    }
    String wrapperBranch = trimmed(request.wrapperBranch());
    if (wrapperBranch == null || !BRANCH.matcher(wrapperBranch).matches()) {
      throw new BadRequestException("Invalid wrapper branch: " + request.wrapperBranch());
    }
    RepositoryLookup.RepositoryView repository = repositories.require(request.repositoryId());
    String defaultBranch = defaultBranchOf(repository);
    if (wrapperBranch.equals(defaultBranch)) {
      throw new BadRequestException(
          "'" + wrapperBranch + "' is the repository's default branch; an agent never works on it.");
    }

    Optional<Found> existing = activeOrRevived(workId, wrapperBranch);
    if (existing.isPresent()) {
      return found(existing.get(), request);
    }

    UUID id = UUID.randomUUID();
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                AgentWorktree agent = new AgentWorktree();
                agent.id = id;
                agent.repositoryId = repository.id();
                agent.workId = workId;
                agent.entityId = trimmed(request.entityId());
                agent.ticketId = trimmed(request.ticketId());
                agent.epicId = trimmed(request.epicId());
                agent.wrapperBranch = wrapperBranch;
                agent.harness = request.harness() == null ? AgentHarness.CLAUDE : request.harness();
                agent.admin = request.admin();
                agent.pendingText = trimmed(request.instruction());
                agent.gitRefs = GitRefs.write(gitRefsFor(wrapperBranch, defaultBranch));
                agent.createdAt = Instant.now();
                applyFacts(agent, request.facts(), null);
                agents.persist(agent);
                agents.flush();
              });
    } catch (RuntimeException raced) {
      // Two dispatches for one item: the loser answers the winner, as a re-press would.
      Optional<Found> winner = activeOrRevived(workId, wrapperBranch);
      if (winner.isEmpty()) {
        throw raced;
      }
      return found(winner.get(), request);
    }
    LOG.infof("Agent %s created for work item %s on %s", id, workId, wrapperBranch);
    mintToken(id);
    placeQueued();
    kick();
    AgentWorktree placed = require(id);
    return new Dispatch(
        view(placed),
        true,
        placed.runState == AgentRunState.QUEUED ? AgentLaunch.QUEUED : AgentLaunch.SCHEDULED,
        placed.commissionedTokenSubject);
  }

  /** The item's agent a dispatch answers with, and whether this dispatch revived it. */
  private record Found(UUID id, boolean revived) {}

  /**
   * The item's ACTIVE agent; else its newest OBSOLETE one when that stands on the same wrapper
   * branch, made ACTIVE again (qits-1217). A release of an agent's wrapper branch makes it OBSOLETE
   * while its item goes on, and the agent keeps its worktree, session and slot until the item is
   * closed (VERIFIED, DONE or DROPPED): a second agent on the same wrapper branch would be refused
   * by its daemon ("already checked out") for as long as the first stands. An OBSOLETE
   * agent on another wrapper branch is left alone, and the caller creates a new agent.
   *
   * <p>Two dispatches that revive at once are settled by {@code uq_agent_worktree_active_work}: the
   * loser answers the winner.
   */
  private Optional<Found> activeOrRevived(String workId, String wrapperBranch) {
    Optional<AgentWorktree> active =
        QuarkusTransaction.requiringNew().call(() -> agents.findActiveByWorkId(workId));
    if (active.isPresent()) {
      return Optional.of(new Found(active.get().id, false));
    }
    Optional<UUID> revived;
    try {
      revived =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    AgentWorktree live = agents.findLiveByWorkId(workId).orElse(null);
                    if (live == null
                        || live.state != AgentState.OBSOLETE
                        || !wrapperBranch.equals(live.wrapperBranch)) {
                      return Optional.<UUID>empty();
                    }
                    live.state = AgentState.ACTIVE;
                    live.obsoleteAt = null;
                    live.cleanupPromptedAt = null;
                    agents.flush();
                    return Optional.of(live.id);
                  });
    } catch (RuntimeException raced) {
      Optional<AgentWorktree> winner =
          QuarkusTransaction.requiringNew().call(() -> agents.findActiveByWorkId(workId));
      if (winner.isEmpty()) {
        throw raced;
      }
      return Optional.of(new Found(winner.get().id, false));
    }
    revived.ifPresent(
        id ->
            LOG.infof(
                "Agent %s of work item %s is ACTIVE again: dispatched again on %s after a release"
                    + " made it obsolete",
                id, workId, wrapperBranch));
    return revived.map(id -> new Found(id, true));
  }

  /**
   * A dispatch that found the item's agent: its facts refreshed, an idle one told the instruction.
   * A revived agent is told it whatever its run state, a running one included, as a delivery is:
   * it is a new instruction for an agent that has worked on the item before, not a re-press.
   */
  private Dispatch found(Found found, AgentRequest request) {
    UUID id = found.id();
    String instruction = trimmed(request.instruction());
    AgentLaunch launch =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree agent = agents.findById(id);
                  applyFacts(agent, request.facts(), null);
                  if (found.revived()) {
                    if (instruction != null) {
                      appendTurn(agent, instruction, false);
                    }
                  } else if (agent.runState == AgentRunState.RUNNING && started.containsKey(id)) {
                    return AgentLaunch.SKIPPED_RUNNING;
                  } else if (instruction != null && agent.pendingText == null) {
                    agent.pendingText = instruction;
                  }
                  return agent.runState == AgentRunState.QUEUED
                      ? AgentLaunch.QUEUED
                      : AgentLaunch.SCHEDULED;
                });
    AgentWorktree agent = require(id);
    if (launch != AgentLaunch.SKIPPED_RUNNING) {
      bringUp(agent);
    }
    kick();
    return new Dispatch(view(agent), false, launch, agent.commissionedTokenSubject);
  }

  /**
   * <b>Say this to the agent working on the item</b>. Never creates an agent: an item nobody
   * dispatched has nobody to tell, and that is answered, not refused. A yielded agent is RUNNING
   * again at once to hear it (D16); a queued one hears it as part of its first turn.
   *
   * <p>An OBSOLETE agent is told too. A release of its wrapper branch marks it OBSOLETE, but the
   * item can go on — the next phase, another repository — and that agent is still the one working
   * on it, with its session.
   */
  public Delivery deliver(String workId, String text, boolean compactFirst) {
    String said = trimmed(text);
    if (said == null) {
      throw new BadRequestException("A turn needs text.");
    }
    Optional<AgentWorktree> found =
        QuarkusTransaction.requiringNew().call(() -> agents.findLiveByWorkId(workId));
    if (found.isEmpty()) {
      return new Delivery(
          null, false, false, false, "No agent works on work item " + workId + "; nothing was said.");
    }
    UUID id = found.get().id;
    AgentWorktree agent =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row = agents.findById(id);
                  appendTurn(row, said, compactFirst);
                  return row;
                });
    bringUp(agent);
    kick();
    String agentId = id.toString();
    return switch (agent.runState) {
      case RUNNING ->
          started.containsKey(id)
              ? new Delivery(
                  agentId, true, false, false, "Delivered at the agent's next turn boundary.")
              : new Delivery(
                  agentId,
                  false,
                  true,
                  false,
                  "The agent starts with this as its turn once its workspace's daemon answers.");
      case YIELDED ->
          new Delivery(
              agentId,
              false,
              false,
              true,
              "The agent had yielded its workspace slot; it hears this at its next turn"
                  + " boundary.");
      case QUEUED ->
          new Delivery(
              agentId,
              false,
              true,
              false,
              "The agent waits for a workspace slot; this is part of its first turn.");
    };
  }

  /**
   * {@code said} becomes part of what the agent has still to hear, after anything already pending.
   * The scheduler says it whatever the agent's run state: at the next turn boundary of a running
   * agent, as the resume of a yielded one (D16), as part of a queued one's first turn. Called inside
   * a transaction, on a managed row.
   */
  private void appendTurn(AgentWorktree row, String said, boolean compactFirst) {
    row.pendingText = row.pendingText == null ? said : row.pendingText + "\n\n" + said;
    row.pendingCompact = row.pendingCompact || (compactFirst && compactBeforeTurn);
  }

  /**
   * Store the work item's title, status word and blocked flag on its agent, and tell a running
   * harness live. The facts are what the daemon names the session after, and the status is what
   * the cleanup sweep reads (VERIFIED, DONE or DROPPED, D4).
   */
  public EntityMark markEntity(String workId, EntityFacts facts, String blockSource) {
    return mark(workId, facts, blockSource, false);
  }

  /** {@link #markEntity} for the blocked flag alone; title and status stay. */
  public EntityMark markBlocked(String workId, boolean blocked, String blockSource) {
    return mark(workId, new EntityFacts(null, null, blocked), blockSource, true);
  }

  /**
   * Applied to every non-REMOVED agent of the item (ACTIVE and every OBSOLETE one), not only the
   * one {@link AgentWorktreeRepository#findLiveByWorkId} would answer with: an item with two
   * OBSOLETE agents must not leave the older one's facts frozen. The answer and the live daemon
   * notification still name the primary row — ACTIVE, else the newest OBSOLETE — the same row
   * {@code findLiveByWorkId} picks.
   */
  private EntityMark mark(String workId, EntityFacts facts, String blockSource, boolean blockedOnly) {
    List<AgentWorktree> stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<AgentWorktree> live = agents.findLiveAllByWorkId(workId);
                  for (AgentWorktree agent : live) {
                    if (blockedOnly) {
                      agent.entityBlocked = facts.blocked();
                      agent.blockSource = trimmed(blockSource);
                    } else {
                      applyFacts(agent, facts, blockSource);
                    }
                  }
                  return live;
                });
    if (stored.isEmpty()) {
      return new EntityMark(null, false);
    }
    AgentWorktree agent = stored.get(0);
    boolean applied = false;
    if (agent.workspaceId != null && started.containsKey(agent.id) && daemon.isResolvable()) {
      applied =
          daemon
                  .get()
                  .entity(
                      agent.workspaceId,
                      agent.id.toString(),
                      new EntityFacts(
                          agent.entityTitle,
                          agent.entityStatus,
                          Boolean.TRUE.equals(agent.entityBlocked)),
                      agent.blockSource)
              == AgentDaemon.Outcome.OK;
    }
    kick();
    return new EntityMark(agent.id.toString(), applied);
  }

  /**
   * A release deleted {@code branch} of {@code repositoryId}: every ACTIVE agent whose wrapper
   * branch it is is marked OBSOLETE (D4). A branch an agent merely pushed, in a submodule, marks
   * nobody: only the wrapper branch's release ends an agent's work. Nothing is removed here; the
   * cleanup sweep does that once the item is closed (VERIFIED, DONE or DROPPED) and the agent idle.
   * A workspace of the old model standing on the branch is resolved as before (the drain, D20).
   */
  public Resolution resolveReleasedBranch(
      String repositoryId, String branch, String target, String commit, String result) {
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("A branch is required to resolve a released branch.");
    }
    List<AgentObsolete> obsolete =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Instant now = Instant.now();
                  List<AgentObsolete> out = new ArrayList<>();
                  for (AgentWorktree agent : agents.findActiveByWrapperBranch(repositoryId, branch)) {
                    agent.state = AgentState.OBSOLETE;
                    agent.obsoleteAt = now;
                    // The release's own words (`result`) are not on the wire: only its refs.
                    out.add(
                        new AgentObsolete(
                            agent.id.toString(),
                            agent.workId,
                            idOf(agent.workspaceId),
                            agent.repositoryId,
                            branch,
                            trimmed(target),
                            trimmed(commit),
                            now));
                  }
                  return out;
                });
    List<String> marked = obsolete.stream().map(AgentObsolete::agentId).toList();
    if (!marked.isEmpty()) {
      LOG.infof(
          "Release %s consumed branch '%s' of %s: agents %s are obsolete",
          target, branch, repositoryId, marked);
      obsolete.forEach(this::announce);
      kick();
      return new Resolution(true, marked);
    }
    boolean legacy =
        workspaces.resolveReleasedBranch(repositoryId, branch, target, commit, result).resolved();
    return new Resolution(legacy, List.of());
  }

  /**
   * A person removes an agent now (the agent page's discard). A busy harness refuses with 409
   * unless {@code force} (qits-1238); leftover work refuses with 409 and the cleanup check, unless
   * {@code force}; an unreachable daemon refuses too, unless {@code force}. A daemon that answers
   * it does not know the agent has nothing left to remove: no refusal (qits-1233).
   */
  public AgentDto discard(UUID id, boolean force) {
    AgentWorktree agent = require(id);
    if (agent.state == AgentState.REMOVED) {
      return view(agent);
    }
    boolean harnessStopped = false;
    if (agent.workspaceId != null && daemon.isResolvable()) {
      AgentDaemon port = daemon.get();
      AgentDaemon.CleanupAnswer answer =
          port.reachable(agent.workspaceId)
              ? port.cleanupCheck(agent.workspaceId, id.toString())
              : AgentDaemon.CleanupAnswer.unreachable("the daemon is not connected");
      if (!force) {
        if (!answer.isFound() && !answer.isUnknownAgent()) {
          throw new ConflictException(
              "AGENT_DAEMON_UNREACHABLE",
              "The agent's workspace daemon does not answer, so its worktrees cannot be checked;"
                  + " discard with force to remove the agent anyway.");
        }
        if (answer.isFound() && answer.check().busy()) {
          throw new ConflictException(
              "AGENT_BUSY",
              "The agent's harness is still working in the background (a subagent, a monitor or a"
                  + " background shell); discard with force to stop it anyway.");
        }
        if (answer.isFound() && !answer.check().clean()) {
          throw new ConflictException(
              "AGENT_HAS_LEFTOVER_WORK",
              "The agent's worktrees hold work that never reached the git host: "
                  + describe(answer.check())
                  + ". Discard with force to throw it away.");
        }
      }
      if (answer.isFound()) {
        harnessStopped =
            port.remove(agent.workspaceId, id.toString(), true) == AgentDaemon.Outcome.OK;
      }
    }
    markRemoved(id, AgentRemoved.DISCARDED, harnessStopped, "discarded by a person");
    kick();
    return view(require(id));
  }

  // --- the reads ----------------------------------------------------------------------------------

  public AgentDto get(UUID id) {
    return view(require(id));
  }

  public List<AgentDto> list(String workId, Long workspaceId, AgentState state) {
    List<AgentWorktree> rows =
        QuarkusTransaction.requiringNew().call(() -> agents.search(workId, workspaceId, state));
    return views(rows);
  }

  /** Every agent of these work items, whatever its state, newest first. */
  public List<AgentDto> referencing(Collection<String> workIds) {
    List<AgentWorktree> rows =
        QuarkusTransaction.requiringNew().call(() -> agents.findByWorkIds(workIds));
    return views(rows);
  }

  /** Every agent that is not REMOVED, for the work views. */
  public List<AgentWorktree> rowsByWorkIds(Collection<String> workIds) {
    return QuarkusTransaction.requiringNew().call(() -> agents.findByWorkIds(workIds));
  }

  /**
   * The placed ACTIVE agents, oldest first, in the work views' shape ({@code /work/workspaces}, the
   * landing app's pact): one entry per agent, carrying its workspace's row id and label.
   */
  public List<WorkItemWorkspaceDto> openWorkItemViews() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workItemViews(
                    agents.findPlacedLive().stream()
                        .filter(a -> a.state == AgentState.ACTIVE)
                        .toList()));
  }

  /** Every placed agent of one work item ({@code workRef}: its id or qualified id), newest first. */
  public List<WorkItemWorkspaceDto> workItemViews(String workRef) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workItemViews(agents.findPlacedByWorkRef(workRef)));
  }

  private List<WorkItemWorkspaceDto> workItemViews(List<AgentWorktree> rows) {
    List<WorkItemWorkspaceDto> out = new ArrayList<>();
    for (AgentWorktree agent : rows) {
      Workspace workspace = workspaceRepository.findById(agent.workspaceId);
      if (workspace == null) {
        continue;
      }
      String status =
          switch (agent.state) {
            case ACTIVE -> WorkspaceStatus.ACTIVE.name();
            case OBSOLETE -> WorkspaceStatus.INTEGRATED.name();
            case REMOVED ->
                agent.obsoleteAt != null
                    ? WorkspaceStatus.INTEGRATED.name()
                    : WorkspaceStatus.ABANDONED.name();
          };
      Instant resolvedAt = agent.state == AgentState.ACTIVE ? null : (agent.removedAt != null ? agent.removedAt : agent.obsoleteAt);
      out.add(
          new WorkItemWorkspaceDto(
              workspace.id,
              agent.workId,
              agent.entityId,
              agent.repositoryId,
              workspace.workspaceId,
              agent.wrapperBranch,
              status,
              workspace.runtimeStatus == null ? null : workspace.runtimeStatus.name(),
              agent.createdAt,
              resolvedAt));
    }
    return out;
  }

  // --- what the daemons report --------------------------------------------------------------------

  /**
   * An activity frame for one agent: the activity time, the waiting state and the session. A flip
   * of the waiting state is relayed to qits-projects (qits-895), which derives the item's BLOCKED
   * from it; the relay is now per agent and stored, so a reconnect does not repeat it. A YIELDED
   * agent that works again (a person typed in its terminal) is RUNNING again, so it counts as
   * holding its slot.
   *
   * <p>A frame counts only from the daemon of the workspace the agent is placed in. Another
   * workspace's daemon cannot change this agent's row.
   */
  public void onActivity(
      Long workspaceId,
      String agentId,
      Boolean awaitingInput,
      String sessionId,
      String hookEvent,
      long at) {
    UUID id = parse(agentId);
    if (id == null) {
      return;
    }
    Instant when = at > 0 ? Instant.ofEpochMilli(at) : Instant.now();
    AtomicBoolean busyAgain = new AtomicBoolean();
    AgentResumed[] wokeUp = new AgentResumed[1];
    String[] relay =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree agent = agents.findById(id);
                  if (agent == null
                      || agent.state == AgentState.REMOVED
                      || !Objects.equals(agent.workspaceId, workspaceId)) {
                    return null;
                  }
                  agent.lastActivityAt = when;
                  if (Boolean.FALSE.equals(awaitingInput)
                      && agent.runState == AgentRunState.YIELDED
                      && agent.workspaceId != null) {
                    agent.runState = AgentRunState.RUNNING;
                    busyAgain.set(true);
                    wokeUp[0] = resumed(agent, AgentResumed.WORKS_AGAIN, when);
                    LOG.infof("Agent %s works again in workspace %s", agent.id, agent.workspaceId);
                  }
                  if (sessionId != null && !sessionId.isBlank()) {
                    agent.sessionId = sessionId;
                  }
                  if (awaitingInput == null) {
                    return null;
                  }
                  boolean was = agent.waitingSince != null;
                  if (awaitingInput.booleanValue() == was) {
                    return null;
                  }
                  agent.waitingSince = awaitingInput ? when : null;
                  return new String[] {agent.workId};
                });
    if (wokeUp[0] != null) {
      announce(wokeUp[0]);
    }
    if (relay != null) {
      // With an OPEN wait, a waiting agent is reported as waiting on it (qits-1153, W5).
      waits.relayActivity(relay[0], id, awaitingInput, hookEvent, sessionId, when);
    }
    if (Boolean.TRUE.equals(awaitingInput) || busyAgain.get()) {
      kick();
    }
  }

  /**
   * An agent's harness ended in {@code workspaceId}'s container, as its daemon reported it:
   * announced as {@link AgentHarnessExited}, and nothing else — the row is not written. An agent
   * this service does not know is not announced. {@code stoppedBy} is one of that event's words.
   */
  public void onHarnessExited(
      Long workspaceId,
      String agentId,
      String commandId,
      String status,
      Integer exitCode,
      String stoppedBy,
      long at) {
    UUID id = parse(agentId);
    if (id == null) {
      return;
    }
    AgentWorktree agent = QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
    if (agent == null) {
      return;
    }
    announce(
        new AgentHarnessExited(
            agent.id.toString(),
            agent.workId,
            idOf(workspaceId),
            agent.repositoryId,
            commandId,
            status,
            exitCode,
            stoppedBy,
            at > 0 ? Instant.ofEpochMilli(at) : Instant.now()));
  }

  /**
   * The daemon pushed {@code branch} of the repository it names {@code repositoryName} (the git
   * host's name, D22): mapped to the repository id through the agent's project and recorded.
   */
  public void onBranchPushed(String agentId, String repositoryName, String branch, String sha) {
    UUID id = parse(agentId);
    if (id == null || repositoryName == null || branch == null || branch.isBlank()) {
      return;
    }
    PendingPush push = new PendingPush(id, repositoryName, branch, sha, Instant.now());
    if (!recordPush(push)) {
      unresolvedPushes.add(push);
    }
  }

  /**
   * Record one push. False when qits-projects did not answer the name lookup: the push is then tried
   * again on the next pass, because a lost push is a branch missing from the agent's listed
   * branches.
   */
  private boolean recordPush(PendingPush push) {
    AgentWorktree agent = QuarkusTransaction.requiringNew().call(() -> agents.findById(push.agentId()));
    if (agent == null) {
      LOG.debugf("A push was reported for unknown agent %s", push.agentId());
      return true;
    }
    String repositoryId;
    try {
      repositoryId = repositoryIdOf(agent.repositoryId, push.repositoryName());
    } catch (RuntimeException registryDidNotAnswer) {
      LOG.infof(
          "Could not map repository %s of agent %s's push of '%s' yet (%s); trying again",
          push.repositoryName(), push.agentId(), push.branch(), registryDidNotAnswer.getMessage());
      return false;
    }
    if (repositoryId == null) {
      LOG.warnf(
          "Agent %s pushed '%s' of a repository named %s, which its project does not list; not"
              + " recorded",
          push.agentId(), push.branch(), push.repositoryName());
      return true;
    }
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                agentBranches.upsert(
                    push.agentId(), repositoryId, push.branch(), push.sha(), push.pushedAt()));
    changePublisher.fire(agent.repositoryId, agent.workspaceId, WorkspaceChangeHint.Topic.AGENTS);
    return true;
  }

  /** Step 0: the pushes whose repository qits-projects could not name before, tried again. */
  void retryPushes() {
    int pending = unresolvedPushes.size();
    Instant giveUpBefore = Instant.now().minus(PUSH_RETRY_LIMIT);
    for (int i = 0; i < pending; i++) {
      PendingPush push = unresolvedPushes.poll();
      if (push == null) {
        return;
      }
      if (recordPush(push)) {
        continue;
      }
      if (push.pushedAt().isBefore(giveUpBefore)) {
        LOG.warnf(
            "Gave up mapping repository %s of agent %s's push of '%s' after %s; not recorded",
            push.repositoryName(), push.agentId(), push.branch(), PUSH_RETRY_LIMIT);
      } else {
        unresolvedPushes.add(push);
      }
    }
  }

  /**
   * A workspace's daemon connected or went: its placed agents are started again on the next pass,
   * the RUNNING ones and the YIELDED ones alike (qits-1212), a YIELDED one with no turn.
   */
  public void onDaemonConnection(Long workspaceRowId) {
    started.values().removeIf(row -> Objects.equals(row, workspaceRowId));
    kick();
  }

  /**
   * A workspace's start ended other than by a launch — among others a runner workspace whose daemon
   * never dialed home is FAILED (qits-1233). A pass now, so {@link #drive} takes its agents out of
   * it on the next pass rather than on the next timer. After the firing transaction committed.
   */
  void onUnqueued(@Observes(during = TransactionPhase.AFTER_SUCCESS) WorkspaceUnqueued unqueued) {
    kick();
  }

  /** What a fresh process knows: no agent started anywhere. Tests stand for a service restart with it. */
  void forgetStarted() {
    started.clear();
  }

  /**
   * The id of repository {@code name} in the wrapper's project, or null when it lists none. Throws
   * when qits-projects does not answer.
   */
  private String repositoryIdOf(String wrapperId, String name) {
    RepositoryLookup.RepositoryView wrapper = repositories.find(wrapperId).orElse(null);
    if (wrapper == null) {
      return null;
    }
    if (name.equals(wrapper.name())) {
      return wrapper.id();
    }
    if (wrapper.projectId() == null) {
      return null;
    }
    return repositories.listByProject(wrapper.projectId()).stream()
        .filter(r -> name.equals(r.name()))
        .map(RepositoryLookup.RepositoryView::id)
        .findFirst()
        .orElse(null);
  }

  // --- the scheduler ------------------------------------------------------------------------------

  /** Ask for a pass soon; passes coalesce. */
  public void kick() {
    if (!kicked.compareAndSet(false, true)) {
      return;
    }
    try {
      pass.submit(
          () -> {
            kicked.set(false);
            try {
              reconcile();
            } catch (RuntimeException e) {
              LOG.warnf(e, "agent scheduler pass failed");
            }
          });
    } catch (RejectedExecutionException shuttingDown) {
      kicked.set(false);
    }
  }

  /** One scheduler pass; see the class javadoc. Serialised: one pass at a time. */
  public void reconcile() {
    synchronized (passLock) {
      retryPushes();
      try {
        waits.sweep();
      } catch (RuntimeException e) {
        LOG.warnf(e, "declared-wait sweep failed");
      }
      resumeYielded();
      placeQueued();
      yieldBlocked();
      driveRunning();
      cleanUp();
      removeEmptyWorkspaces();
    }
  }

  /**
   * Step 1: every yielded agent with something to hear is RUNNING again at once, before anyone new
   * is placed. Another RUNNING agent in its workspace does not hold it back: its harness never
   * stopped, so it hears the turn at its next turn boundary ({@link #drive}).
   */
  void resumeYielded() {
    synchronized (placeLock) {
      Instant now = Instant.now();
      List<AgentResumed> resumed =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      agents.findPlacedLive().stream()
                          .filter(a -> a.runState == AgentRunState.YIELDED && a.pendingText != null)
                          .map(
                              a -> {
                                a.runState = AgentRunState.RUNNING;
                                LOG.infof("Agent %s resumes in workspace %s", a.id, a.workspaceId);
                                return resumed(a, AgentResumed.TURN_PENDING, now);
                              })
                          .toList());
      resumed.forEach(this::announce);
    }
  }

  /** Step 2: each QUEUED agent to a slot (D17). */
  void placeQueued() {
    synchronized (placeLock) {
      List<AgentWorktree> queued = QuarkusTransaction.requiringNew().call(() -> agents.findQueued());
      waitingForCapacity.retainAll(queued.stream().map(a -> a.id).toList());
      Instant now = Instant.now();
      for (AgentWorktree agent : queued) {
        Requeued held = requeued.get(agent.id);
        if (held != null && held.notBefore().isAfter(now)) {
          continue;
        }
        Long slot = QuarkusTransaction.requiringNew().call(() -> emptySlotFor(agent));
        RunnerCapacity capacity = null;
        if (slot == null) {
          capacity = agent.admin ? null : runnerCapacity();
          if (capacity == null || capacity.free()) {
            try {
              slot = workspaces.createSlot(agent.repositoryId, agent.admin).id;
            } catch (RuntimeException refused) {
              LOG.warnf(
                  refused, "Could not create a workspace for agent %s; it stays queued", agent.id);
            }
          }
        }
        if (slot == null) {
          slot = QuarkusTransaction.requiringNew().call(() -> yieldedSlotFor(agent));
        }
        if (slot == null) {
          if (capacity != null && !capacity.free() && waitingForCapacity.add(agent.id)) {
            LOG.infof(
                "Agent %s stays queued for a runner slot: runner slots %d, in use or queued %d",
                agent.id,
                capacity.slots(),
                capacity.inUse());
          }
          continue;
        }
        Long chosen = slot;
        boolean placed =
            QuarkusTransaction.requiringNew()
                .call(
                    () -> {
                      AgentWorktree row = agents.findById(agent.id);
                      if (row.state != AgentState.ACTIVE || row.runState != AgentRunState.QUEUED) {
                        return false;
                      }
                      // Checked again where it is written: an agent of the slot may have woken
                      // since it was chosen. The next pass tries again.
                      if (!free(chosen, row)) {
                        return false;
                      }
                      row.workspaceId = chosen;
                      row.runState = AgentRunState.RUNNING;
                      agents.flush();
                      return true;
                    });
        if (!placed) {
          LOG.debugf("Workspace %s was taken before agent %s got it", chosen, agent.id);
          continue;
        }
        waitingForCapacity.remove(agent.id);
        LOG.infof("Agent %s placed in workspace %s", agent.id, chosen);
        announce(
            new AgentPlaced(
                agent.id.toString(), agent.workId, idOf(chosen), agent.repositoryId, Instant.now()));
        changePublisher.fire(agent.repositoryId, chosen, WorkspaceChangeHint.Topic.AGENTS);
      }
    }
  }

  /**
   * An ACTIVE, not FAILED workspace of the agent's wrapper and posture that hosts no live agent — so none that
   * holds its wrapper branch either ({@link #holdsBranchOf}).
   */
  private Long emptySlotFor(AgentWorktree agent) {
    for (Workspace workspace : slotsOf(agent)) {
      if (agents.findLiveByWorkspace(workspace.id).isEmpty()) {
        return workspace.id;
      }
    }
    return null;
  }

  /** A workspace of the agent's wrapper and posture whose agents have all yielded, none waiting to resume. */
  private Long yieldedSlotFor(AgentWorktree agent) {
    for (Workspace workspace : slotsOf(agent)) {
      List<AgentWorktree> live = agents.findLiveByWorkspace(workspace.id);
      if (!live.isEmpty() && allYielded(live) && !holdsBranchOf(live, agent)) {
        return workspace.id;
      }
    }
    return null;
  }

  /**
   * Whether a queued agent may take the workspace: it hosts no live agent, or all have yielded, and
   * none of them holds the agent's wrapper branch.
   */
  private boolean free(Long workspaceId, AgentWorktree agent) {
    List<AgentWorktree> live = agents.findLiveByWorkspace(workspaceId);
    return allYielded(live) && !holdsBranchOf(live, agent);
  }

  private static boolean allYielded(List<AgentWorktree> live) {
    return live.stream().allMatch(a -> a.runState == AgentRunState.YIELDED && a.pendingText == null);
  }

  /**
   * Whether another live agent (ACTIVE or OBSOLETE: an OBSOLETE one keeps its worktree until it is
   * REMOVED) holds the agent's wrapper branch of the same repository there (qits-1217). Git checks a
   * branch out in one worktree of a clone at a time, so the daemon would refuse the start with
   * "already checked out", every pass.
   */
  private static boolean holdsBranchOf(List<AgentWorktree> live, AgentWorktree agent) {
    return live.stream()
        .anyMatch(
            a ->
                !a.id.equals(agent.id)
                    && a.state != AgentState.REMOVED
                    && Objects.equals(a.repositoryId, agent.repositoryId)
                    && Objects.equals(a.wrapperBranch, agent.wrapperBranch));
  }

  /**
   * The ACTIVE slot workspaces (no branch of their own) of an agent's wrapper and posture. A FAILED
   * one is none (qits-1233): nothing restarts it, so an agent placed there would never start; it
   * is retired once empty.
   */
  private List<Workspace> slotsOf(AgentWorktree agent) {
    return workspaceRepository.findActiveByRepositoryId(agent.repositoryId).stream()
        .filter(
            w ->
                w.branch == null
                    && w.admin == agent.admin
                    && w.runtimeStatus != WorkspaceRuntimeStatus.FAILED)
        .toList();
  }

  /**
   * The eligible runners' slots against the RUNNER workspaces in use or queued for one ({@link
   * WorkspaceRepository#countHoldingRunnerSlots}), read together. A new regular workspace may be
   * created while it is {@link RunnerCapacity#free}; an admin one needs none (DIRECT, on the
   * platform host).
   */
  RunnerCapacity runnerCapacity() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                new RunnerCapacity(
                    runnerRepository.eligibleSlots(),
                    workspaceRepository.countHoldingRunnerSlots()));
  }

  /** The runner slots there are, and how many of them are in use or queued for. */
  record RunnerCapacity(long slots, long inUse) {
    boolean free() {
      return slots > inUse;
    }
  }

  /**
   * Step 3: a RUNNING agent waiting for input yields to an agent that wants its slot (D14, D16). A
   * yield writes the row and nothing else: the harness keeps running and the agent stays started,
   * so its terminal, turns and entity updates keep working (qits-1212).
   */
  void yieldBlocked() {
    synchronized (passLock) {
      Instant before = Instant.now().minus(yieldAfter);
      List<AgentWorktree> candidates =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<AgentWorktree> queued = agents.findQueued();
                    List<AgentWorktree> out = new ArrayList<>();
                    Map<Long, List<AgentWorktree>> byWorkspace = placedByWorkspace();
                    // An agent waiting for input on a declared wait yields at once: it said what it
                    // waits on, so there is nothing to debounce (qits-1153, W5).
                    java.util.Set<UUID> onAWait = new java.util.HashSet<>();
                    waitRows
                        .findOpenByAgents(
                            byWorkspace.values().stream().flatMap(List::stream).map(a -> a.id).toList())
                        .forEach(w -> onAWait.add(w.agentId));
                    for (List<AgentWorktree> live : byWorkspace.values()) {
                      for (AgentWorktree agent : live) {
                        if (agent.runState != AgentRunState.RUNNING
                            || agent.waitingSince == null
                            || (agent.waitingSince.isAfter(before) && !onAWait.contains(agent.id))
                            || agent.pendingText != null) {
                          continue;
                        }
                        boolean resumeWaits =
                            live.stream()
                                .anyMatch(
                                    a -> a.runState == AgentRunState.YIELDED && a.pendingText != null);
                        boolean newWaits =
                            queued.stream()
                                .anyMatch(
                                    q ->
                                        q.repositoryId.equals(agent.repositoryId)
                                            && q.admin == agent.admin);
                        if (resumeWaits || newWaits) {
                          out.add(agent);
                        }
                      }
                    }
                    return out;
                  });
      for (AgentWorktree agent : candidates) {
        if (!writeYield(agent.id)) {
          continue;
        }
        LOG.infof("Agent %s yielded workspace %s", agent.id, agent.workspaceId);
        announce(
            new AgentYielded(
                agent.id.toString(),
                agent.workId,
                idOf(agent.workspaceId),
                agent.repositoryId,
                Instant.now()));
        resumeYielded();
        placeQueued();
      }
    }
  }

  /**
   * Write the yield of one candidate: RUNNING becomes YIELDED. Only a write is a yield (qits-1212):
   * an agent that is no longer RUNNING when its row is read again (it works again, or a wait or a
   * turn woke it and it ran) yielded nothing, so the caller logs nothing and places nobody on its
   * account.
   *
   * @return whether the row was written
   */
  boolean writeYield(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              AgentWorktree row = agents.findById(id);
              if (row == null || row.runState != AgentRunState.RUNNING) {
                return false;
              }
              // waitingSince stays: a yielded agent still waits for a person, and the hook of the
              // turn it resumes on is the flip that clears it.
              row.runState = AgentRunState.YIELDED;
              return true;
            });
  }

  /**
   * Step 4: start the RUNNING agents and tell them what they have still to hear; start the placed
   * YIELDED agents this process has not started on their daemon's current connection (qits-1212).
   *
   * <p>A yielded agent keeps its harness, so it has to be started like any other placed agent after
   * its daemon connects again or this service restarts: the daemon keeps the agent credential in
   * memory only, and {@link #started} begins empty. Without that its harness is gone after a daemon
   * restart, and {@link #mark} relays nothing to it until it is woken. It is only started ({@link
   * #ensureStarted}): its run state stays YIELDED and its pending turn stays on the row, for
   * {@link #resumeYielded} to make it RUNNING and {@link #drive} to say it. A start of an agent
   * whose harness still runs launches nothing in the daemon, so no agent gets a second harness; a
   * RUNNING agent's pending turn then stays on the row and is said as a turn on the next pass
   * (qits-1225).
   */
  void driveRunning() {
    synchronized (passLock) {
      List<AgentWorktree> placed =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      agents.findPlacedLive().stream()
                          .filter(
                              a ->
                                  a.runState == AgentRunState.RUNNING
                                      || (a.runState == AgentRunState.YIELDED
                                          && !started.containsKey(a.id)))
                          .toList());
      for (AgentWorktree agent : placed) {
        try {
          if (agent.runState == AgentRunState.RUNNING) {
            drive(agent);
          } else {
            ensureStarted(agent);
          }
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not drive agent %s", agent.id);
        }
      }
    }
  }

  /**
   * A placed YIELDED agent's harness, started on its daemon's current connection with no
   * instruction (qits-1212). Nothing else: no turn, the pending text untouched, the run state left
   * YIELDED, and a workspace that is gone is left to the resume, which makes it RUNNING and lets
   * {@link #drive} queue it for a new one.
   */
  private void ensureStarted(AgentWorktree agent) {
    Workspace workspace =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findByIdOptional(agent.workspaceId).orElse(null));
    if (workspace == null
        || workspace.status != WorkspaceStatus.ACTIVE
        || !daemonLive(workspace.id)
        || !daemon.isResolvable()) {
      return;
    }
    start(agent, workspace, daemon.get(), false);
  }

  private void drive(AgentWorktree agent) {
    Workspace workspace =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findByIdOptional(agent.workspaceId).orElse(null));
    if (workspace == null || workspace.status != WorkspaceStatus.ACTIVE) {
      // The slot is gone, and its worktree with it: the agent waits for a new one.
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                AgentWorktree row = agents.findById(agent.id);
                row.workspaceId = null;
                row.runState = AgentRunState.QUEUED;
              });
      started.remove(agent.id);
      announce(
          new AgentRequeued(
              agent.id.toString(),
              agent.workId,
              idOf(agent.workspaceId),
              agent.repositoryId,
              AgentRequeued.WORKSPACE_GONE,
              "Workspace "
                  + agent.workspaceId
                  + " is gone; the agent is queued for another workspace.",
              Instant.now()));
      return;
    }
    if (workspace.runtimeStatus == WorkspaceRuntimeStatus.FAILED && !daemonLive(workspace.id)) {
      if (mayHoldWorktree(agent, workspace)) {
        // Its worktree is in that volume: moving the agent would let the empty slot be discarded
        // with it. It waits for the workspace, which a person or a restart brings back.
        recordRefusal(
            workspace.id,
            agent,
            "Workspace "
                + workspace.id
                + " failed to start again; the agent's worktree is there, so it waits for that"
                + " workspace.");
      } else {
        requeueFromFailed(agent, workspace);
      }
      return;
    }
    if (!daemonLive(workspace.id) || !daemon.isResolvable()) {
      return;
    }
    AgentDaemon port = daemon.get();
    if (!started.containsKey(agent.id)) {
      start(agent, workspace, port, true);
      return;
    }
    AgentWorktree fresh = require(agent.id);
    if (fresh.pendingText == null) {
      pendingSince.remove(agent.id);
      return;
    }
    Instant waitingSince = pendingSince.computeIfAbsent(agent.id, id -> Instant.now());
    boolean busy =
        activity.isResolvable()
            && activity.get().activityOf(agent.id.toString()).orElse(null) == AgentActivityState.BUSY;
    if (busy && waitingSince.plusMillis(turnWindowMs).isAfter(Instant.now())) {
      return;
    }
    String text = fresh.pendingText;
    if (fresh.pendingCompact) {
      port.turn(workspace.id, agent.id.toString(), "/compact");
    }
    AgentDaemon.Outcome outcome = port.turn(workspace.id, agent.id.toString(), text);
    if (outcome == AgentDaemon.Outcome.UNKNOWN_AGENT) {
      // A daemon restart the socket did not show: start it again, with the text as its turn.
      started.remove(agent.id);
      return;
    }
    if (outcome != AgentDaemon.Outcome.OK) {
      return;
    }
    pendingSince.remove(agent.id);
    Instant now = Instant.now();
    AgentWorktree told =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row = agents.findById(agent.id);
                  if (Objects.equals(row.pendingText, text)) {
                    row.pendingText = null;
                  }
                  row.pendingCompact = false;
                  boolean was = row.waitingSince != null;
                  row.waitingSince = null;
                  row.lastActivityAt = now;
                  return was ? row : null;
                });
    if (told != null) {
      // The agent was waiting and now has a turn. qits-projects heard the waiting flip, so it
      // hears this one too: the hook that follows the turn finds nothing left to flip.
      waits.relayActivity(told.workId, told.id, false, CAUSE_TURN, told.sessionId, now);
    }
  }

  /**
   * Whether the agent's worktree may live in {@code workspace}'s volume (qits-1233): a start was
   * accepted there last, or — a row from before {@code started_workspace_id} — it was started
   * somewhere (it holds a command id) and no start was recorded since.
   */
  private static boolean mayHoldWorktree(AgentWorktree agent, Workspace workspace) {
    return agent.startedWorkspaceId == null
        ? agent.commandId != null
        : agent.startedWorkspaceId.equals(workspace.id);
  }

  /**
   * The agent's workspace never came up: FAILED, with no daemon, and the agent never started there
   * ({@link #mayHoldWorktree}), so nothing of it is in its volume (qits-1233).
   * Nothing will ever start the agent there — the scheduler does not restart a FAILED workspace,
   * and nobody else is told — so it is queued for another one, as for a workspace that is gone, and
   * the reason is stored as its start refusal for its view to show. The empty FAILED slot is then
   * retired by {@link #removeEmptyWorkspaces}, and {@link #slotsOf} never offers it again. A second
   * failure in a row waits before the agent is placed again ({@link #requeued}).
   */
  private void requeueFromFailed(AgentWorktree agent, Workspace workspace) {
    boolean taken =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row = agents.findById(agent.id);
                  if (row == null || !Objects.equals(row.workspaceId, workspace.id)) {
                    return false;
                  }
                  row.workspaceId = null;
                  row.runState = AgentRunState.QUEUED;
                  return true;
                });
    started.remove(agent.id);
    if (!taken) {
      return;
    }
    Instant now = Instant.now();
    Requeued again =
        requeued.merge(
            agent.id,
            new Requeued(1, now),
            (before, first) -> {
              int times = before.times() + 1;
              Duration wait = REQUEUE_BACKOFF.multipliedBy(1L << Math.min(times - 2, 10));
              return new Requeued(
                  times, now.plus(wait.compareTo(REQUEUE_BACKOFF_MAX) > 0 ? REQUEUE_BACKOFF_MAX : wait));
            });
    String why =
        workspace.runtimeError == null || workspace.runtimeError.isBlank()
            ? "it failed to start"
            : workspace.runtimeError;
    String detail =
        "Workspace "
            + workspace.id
            + " did not come up ("
            + why
            + "); the agent is queued for another workspace"
            + (again.times() > 1
                ? ", not before " + again.notBefore() + " (failure " + again.times() + " in a row)"
                : "")
            + ".";
    announce(
        new AgentRequeued(
            agent.id.toString(),
            agent.workId,
            idOf(workspace.id),
            agent.repositoryId,
            AgentRequeued.WORKSPACE_FAILED,
            detail,
            now));
    recordRefusal(workspace.id, agent, detail);
  }

  /**
   * Start the agent on {@code workspace}'s daemon and record it in {@link #started}. With {@code
   * withTurn}, a RUNNING agent's pending text is its instruction and is taken off the row once the
   * daemon launched a harness with it. A start that launched nothing (the harness still ran: a
   * restart of this service, a reconnect of the daemon) attached to it and ignored the instruction:
   * the pending text and activity time stay, and the next {@link #drive}, the agent now started,
   * says the text as a turn (qits-1225). Without {@code withTurn} (a YIELDED agent, qits-1212), the
   * start carries no instruction and leaves the row's pending text and activity time alone: the
   * agent is only made to hold a harness again, and its turn is said once it is RUNNING.
   */
  private void start(AgentWorktree agent, Workspace workspace, AgentDaemon port, boolean withTurn) {
    if (!agent.holdsToken()) {
      mintToken(agent.id);
    }
    AgentWorktree fresh = require(agent.id);
    String instruction = withTurn ? fresh.pendingText : null;
    AgentDaemon.Started answer =
        port.start(
            workspace.id,
            new AgentDaemon.Start(
                fresh.id.toString(),
                fresh.workId,
                fresh.entityId,
                fresh.wrapperBranch,
                fresh.harness.name(),
                fresh.sessionId,
                agentEnv(fresh),
                instruction,
                new EntityFacts(
                    fresh.entityTitle, fresh.entityStatus, Boolean.TRUE.equals(fresh.entityBlocked)),
                fresh.blockSource));
    if (answer.outcome() != AgentDaemon.Outcome.OK) {
      if (answer.outcome() == AgentDaemon.Outcome.REFUSED) {
        recordRefusal(workspace.id, agent, answer.detail());
      }
      return;
    }
    lastRefusal.remove(agent.id);
    requeued.remove(agent.id);
    started.put(agent.id, workspace.id);
    boolean told = withTurn && answer.launched();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(agent.id);
              row.commandId = answer.commandId() == null ? row.commandId : answer.commandId();
              row.sessionId = answer.sessionId() == null ? row.sessionId : answer.sessionId();
              row.startedWorkspaceId = workspace.id;
              row.startRefusal = null;
              row.startRefusedAt = null;
              if (told) {
                row.lastActivityAt = Instant.now();
                if (Objects.equals(row.pendingText, instruction)) {
                  row.pendingText = null;
                }
              }
            });
    LOG.infof(
        "Agent %s %s in workspace %s%s",
        agent.id,
        !answer.launched() ? "attached" : answer.resumed() ? "resumed" : "started",
        workspace.id,
        !withTurn
            ? ", still yielded"
            : told || instruction == null ? "" : ", its turn still pending");
    announce(
        new AgentStarted(
            fresh.id.toString(),
            fresh.workId,
            idOf(workspace.id),
            fresh.repositoryId,
            answer.commandId() == null ? fresh.commandId : answer.commandId(),
            answer.launched(),
            answer.resumed(),
            Instant.now()));
    changePublisher.fire(agent.repositoryId, workspace.id, WorkspaceChangeHint.Topic.AGENTS);
  }

  /**
   * A refused start (qits-1217): logged and stored on the row once per distinct detail, so the
   * agent's view says why it does not run while the scheduler keeps trying every pass. The next
   * accepted start clears it. A workspace that never came up is stored the same way (qits-1233).
   */
  private void recordRefusal(Long workspaceId, AgentWorktree agent, String detail) {
    String said =
        detail == null || detail.isBlank()
            ? "The workspace daemon refused to start the agent and gave no reason."
            : detail;
    if (Objects.equals(lastRefusal.put(agent.id, said), said)) {
      return;
    }
    LOG.warnf("Workspace %s refused to start agent %s: %s", workspaceId, agent.id, said);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(agent.id);
              if (row == null || Objects.equals(row.startRefusal, said)) {
                return;
              }
              row.startRefusal = said;
              row.startRefusedAt = Instant.now();
            });
    announce(
        new AgentStartRefused(
            agent.id.toString(),
            agent.workId,
            idOf(workspaceId),
            agent.repositoryId,
            said,
            Instant.now()));
    changePublisher.fire(agent.repositoryId, workspaceId, WorkspaceChangeHint.Topic.AGENTS);
  }

  /**
   * Step 5: OBSOLETE agents of closed items (VERIFIED, DONE or DROPPED), idle long enough, are
   * removed or prompted (D4–D7). One whose daemon reports background work in its harness ({@code
   * busy}) is neither (qits-1233); one its daemon no longer knows is REMOVED. Judged by the item's CURRENT status — the row {@code
   * findLiveByWorkId} returns, which the relay has always kept current — so a stale OBSOLETE row
   * whose own {@code entityStatus} never caught up still heals.
   */
  void cleanUp() {
    synchronized (passLock) {
      Instant idleBefore = Instant.now().minus(cleanupIdle);
      List<AgentWorktree> obsolete =
          QuarkusTransaction.requiringNew().call(() -> agents.findObsolete());
      Map<String, String> currentStatus = currentStatusByWorkId(obsolete);
      lastSkip.keySet().retainAll(obsolete.stream().map(a -> a.id).toList());
      for (AgentWorktree agent : obsolete) {
        try {
          cleanUp(agent, idleBefore, currentStatus);
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not clean up agent %s", agent.id);
        }
      }
    }
  }

  /** One {@code findLiveByWorkId} lookup per distinct workId of the pass's OBSOLETE agents. */
  private Map<String, String> currentStatusByWorkId(List<AgentWorktree> obsolete) {
    Map<String, String> out = new HashMap<>();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              for (AgentWorktree agent : obsolete) {
                String workId = agent.workId;
                if (workId == null || out.containsKey(workId)) {
                  continue;
                }
                out.put(
                    workId, agents.findLiveByWorkId(workId).map(a -> a.entityStatus).orElse(null));
              }
            });
    return out;
  }

  private static boolean isClosed(String status) {
    return status != null && CLOSING_STATUSES.contains(status.toUpperCase(Locale.ROOT));
  }

  private void cleanUp(AgentWorktree agent, Instant idleBefore, Map<String, String> currentStatus) {
    String status = currentStatus.get(agent.workId);
    if (status == null) {
      status = agent.entityStatus;
    }
    if (!isClosed(status)) {
      skip(
          agent,
          AgentCleanupDeferred.ITEM_NOT_CLOSED,
          status == null ? "its item has no status" : "its item is " + status + ", not closed",
          false);
      return;
    }
    if (agent.lastActivityAt != null && agent.lastActivityAt.isAfter(idleBefore)) {
      skip(agent, AgentCleanupDeferred.RECENTLY_ACTIVE, "it was active within " + cleanupIdle, false);
      return;
    }
    if (agent.pendingText != null) {
      skip(agent, AgentCleanupDeferred.TURN_PENDING, "a turn is still to be said to it", false);
      return;
    }
    if (activity.isResolvable()
        && activity.get().activityOf(agent.id.toString()).orElse(null) == AgentActivityState.BUSY) {
      skip(agent, AgentCleanupDeferred.HARNESS_BUSY, "its harness is busy with a turn", false);
      return;
    }
    if (agent.workspaceId == null) {
      markRemoved(
          agent.id, AgentRemoved.OBSOLETE_NEVER_PLACED, false, "obsolete, and never placed");
      return;
    }
    Workspace workspace =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findByIdOptional(agent.workspaceId).orElse(null));
    if (workspace == null || workspace.status != WorkspaceStatus.ACTIVE) {
      markRemoved(
          agent.id,
          AgentRemoved.OBSOLETE_WORKSPACE_GONE,
          false,
          "obsolete, and its workspace is gone");
      return;
    }
    if (!daemonLive(workspace.id)) {
      if (workspace.runtimeStatus == WorkspaceRuntimeStatus.STOPPED) {
        // The check needs the daemon: start the workspace to clean it up.
        startQuietly(workspace.id);
      }
      skip(
          agent,
          AgentCleanupDeferred.DAEMON_NOT_CONNECTED,
          "workspace "
              + workspace.id
              + "'s daemon is not connected ("
              + workspace.runtimeStatus
              + ")",
          false);
      return;
    }
    if (!daemon.isResolvable()) {
      skip(agent, AgentCleanupDeferred.NO_DAEMON_CLIENT, "no daemon client is configured", false);
      return;
    }
    AgentDaemon port = daemon.get();
    AgentDaemon.CleanupAnswer answer = port.cleanupCheck(workspace.id, agent.id.toString());
    if (answer.isUnknownAgent()) {
      // Removed already: a remove whose OK never arrived, or a daemon that lost it (qits-1233).
      markRemoved(agent.id, AgentRemoved.UNKNOWN_TO_DAEMON, false, "its daemon no longer knows it");
      return;
    }
    if (!answer.isFound()) {
      skip(
          agent,
          AgentCleanupDeferred.CHECK_UNANSWERED,
          "its cleanup check went unanswered: " + answer.detail(),
          true);
      return;
    }
    AgentDaemon.CleanupCheck check = answer.check();
    if (check.busy()) {
      // Background work in the harness after its main turn ended (qits-1233): neither removed,
      // which would kill it mid-call, nor prompted, which would type into a working agent.
      skip(agent, AgentCleanupDeferred.BUSY_IN_BACKGROUND, BUSY_IN_BACKGROUND, false);
      return;
    }
    if (check.clean()) {
      AgentDaemon.Outcome removed = port.remove(workspace.id, agent.id.toString(), false);
      if (removed == AgentDaemon.Outcome.OK || removed == AgentDaemon.Outcome.UNKNOWN_AGENT) {
        markRemoved(
            agent.id,
            AgentRemoved.RELEASED_IDLE_CLEAN,
            removed == AgentDaemon.Outcome.OK,
            "released, idle and clean");
        return;
      }
      if (removed == AgentDaemon.Outcome.BUSY) {
        // Became busy between the check and the removal: the daemon's refusal says so.
        skip(agent, AgentCleanupDeferred.BUSY_IN_BACKGROUND, BUSY_IN_BACKGROUND, false);
        return;
      }
      if (removed != AgentDaemon.Outcome.REFUSED) {
        skip(
            agent,
            AgentCleanupDeferred.REMOVAL_UNANSWERED,
            "its removal was answered " + removed,
            removed == AgentDaemon.Outcome.UNREACHABLE);
        return;
      }
    }
    // Leftover work: handed back to the agent, never deleted (D5). Prompted again only once the
    // agent has worked since the last prompt, so an agent that cannot fix it is not nagged.
    boolean promptAgain =
        agent.cleanupPromptedAt == null
            || (agent.lastActivityAt != null && agent.lastActivityAt.isAfter(agent.cleanupPromptedAt));
    if (!promptAgain) {
      skip(
          agent,
          AgentCleanupDeferred.LEFTOVER_WORK_PROMPTED,
          "it holds leftover work, was prompted to clean up and has not worked since",
          false);
      return;
    }
    String prompt = CLEANUP_PROMPT + "\n\nWhat the check found: " + describe(check) + ".";
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(agent.id);
              row.pendingText = prompt;
              row.cleanupPromptedAt = Instant.now();
            });
    LOG.infof("Agent %s has leftover work; it is prompted to clean up", agent.id);
  }

  /**
   * Why the sweep left an OBSOLETE agent alone this pass, logged and announced ({@link
   * AgentCleanupDeferred}) once per agent and distinct reason (qits-1233): the sweep runs every few
   * seconds, and a stalled agent used to be stalled without a line. Keyed on the reason WORD, not
   * on {@code detail} (qits-1236): a detail that moves under the same reason — a workspace's runtime
   * status, the item's status — is the same deferral, not a new line and a new event every time it
   * changes. {@code warn} for a daemon that does not answer; INFO otherwise.
   */
  private void skip(AgentWorktree agent, String reason, String detail, boolean warn) {
    if (Objects.equals(lastSkip.put(agent.id, reason), reason)) {
      return;
    }
    if (warn) {
      LOG.warnf("Cleanup leaves agent %s for now: %s", agent.id, detail);
    } else {
      LOG.infof("Cleanup leaves agent %s for now: %s", agent.id, detail);
    }
    announce(
        new AgentCleanupDeferred(
            agent.id.toString(),
            agent.workId,
            idOf(agent.workspaceId),
            agent.repositoryId,
            reason,
            detail,
            Instant.now()));
  }

  /** Step 6: a slot workspace with no live agent goes (D2), container, volume and credential. */
  void removeEmptyWorkspaces() {
    synchronized (passLock) {
      Instant orphanBefore = Instant.now().minus(ORPHAN_SLOT_GRACE);
      List<Workspace> empty =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<Workspace> out = new ArrayList<>();
                    for (Workspace workspace : workspaceRepository.findActiveSlots()) {
                      List<AgentWorktree> all = agents.findByWorkspace(workspace.id);
                      boolean anyLive = all.stream().anyMatch(a -> a.state != AgentState.REMOVED);
                      if (anyLive) {
                        continue;
                      }
                      boolean neverUsed = all.isEmpty();
                      if (neverUsed
                          && workspace.createdAt != null
                          && workspace.createdAt.isAfter(orphanBefore)) {
                        continue;
                      }
                      out.add(workspace);
                    }
                    return out;
                  });
      for (Workspace slot : empty) {
        Long id = slot.id;
        try {
          // A @Transactional method: it has committed once it returns.
          workspaces.discardWorkspace(id, "Removed: its last agent was removed (qits-1152, D2).", true);
          LOG.infof("Workspace %s removed: no agent left in it", id);
          announce(
              new WorkspaceRemoved(
                  idOf(id), slot.repositoryId, WorkspaceRemoved.NO_AGENT_LEFT, Instant.now()));
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not remove empty workspace %s", id);
        }
      }
    }
  }

  // --- helpers ------------------------------------------------------------------------------------

  /** A dispatch or a delivery wants the agent's workspace up: a stopped or failed one is started. */
  private void bringUp(AgentWorktree agent) {
    if (agent.workspaceId == null) {
      return;
    }
    Workspace workspace =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(agent.workspaceId).orElse(null));
    if (workspace == null || daemonLive(workspace.id)) {
      return;
    }
    if (workspace.runtimeStatus == WorkspaceRuntimeStatus.STOPPED
        || workspace.runtimeStatus == WorkspaceRuntimeStatus.FAILED) {
      startQuietly(workspace.id);
    }
  }

  private void startQuietly(Long workspaceId) {
    try {
      workspaces.beginEnsureContainer(workspaceId);
    } catch (RuntimeException refused) {
      LOG.debugf(refused, "Could not start workspace %s", workspaceId);
    }
  }

  private boolean daemonLive(Long workspaceId) {
    return liveness.isResolvable() && liveness.get().isDaemonLive(workspaceId);
  }

  /** {@code event} to the lifecycle port; call it only once the write it reports committed. */
  private void announce(QitsEvent event) {
    LifecycleAnnouncer.announce(lifecycle, event);
  }

  /** A workspace row id as the events carry it; null stays null, and is no key on the wire. */
  private static String idOf(Long id) {
    return id == null ? null : id.toString();
  }

  /** The resume of {@code agent}, built inside the transaction that wrote it, announced after. */
  private static AgentResumed resumed(AgentWorktree agent, String reason, Instant at) {
    return new AgentResumed(
        agent.id.toString(), agent.workId, idOf(agent.workspaceId), agent.repositoryId, reason, at);
  }

  /** The placed, live agents by workspace, in one read. Called inside a transaction. */
  private Map<Long, List<AgentWorktree>> placedByWorkspace() {
    Map<Long, List<AgentWorktree>> byWorkspace = new LinkedHashMap<>();
    for (AgentWorktree agent : agents.findPlacedLive()) {
      byWorkspace.computeIfAbsent(agent.workspaceId, id -> new ArrayList<>()).add(agent);
    }
    return byWorkspace;
  }

  /** What a removal that flipped the row hands on: the token to delete, and the event. */
  private record Removal(String tokenId, AgentRemoved event) {}

  /**
   * REMOVED: the row stays as history; the credential and the prompt draft go. Announced ({@link
   * AgentRemoved}) only by the call that flipped the row, after its commit.
   *
   * @param reason one of {@link AgentRemoved}'s reason words
   * @param harnessStopped whether the daemon answered this removal OK
   * @param detail the sentence logged and announced
   */
  void markRemoved(UUID id, String reason, boolean harnessStopped, String detail) {
    Removal removal =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row = agents.findById(id);
                  if (row == null || row.state == AgentState.REMOVED) {
                    return null;
                  }
                  String held = row.commissionedTokenId;
                  row.state = AgentState.REMOVED;
                  row.removedAt = Instant.now();
                  if (row.runState == AgentRunState.RUNNING) {
                    row.runState = AgentRunState.YIELDED;
                  }
                  row.commissionedTokenId = null;
                  row.commissionedTokenSubject = null;
                  row.commissionedToken = null;
                  row.pendingText = null;
                  attachments.deleteByAgentId(id);
                  drafts.deleteByAgentId(id);
                  return new Removal(
                      held == null ? "" : held,
                      new AgentRemoved(
                          id.toString(),
                          row.workId,
                          idOf(row.workspaceId),
                          row.repositoryId,
                          reason,
                          harnessStopped,
                          detail,
                          row.removedAt));
                });
    started.remove(id);
    pendingSince.remove(id);
    requeued.remove(id);
    lastSkip.remove(id);
    if (removal == null) {
      return;
    }
    LOG.infof("Agent %s removed: %s", id, detail);
    announce(removal.event());
    deleteToken(removal.tokenId());
  }

  /**
   * The agent's own credential (D3, D18): a workspace-kind token whose Git refs are the agent's
   * wrapper branch and every branch that starts with it and a dash. Minted only where a token can
   * be spent — through an edge plane — and stored on the row, so a daemon restart can be handed the
   * same one. Best-effort: an agent with no token of its own runs on its workspace's credential.
   */
  void mintToken(UUID id) {
    if (!commissioner.isResolvable()) {
      return;
    }
    try {
      addressPlanes.plane();
    } catch (EdgePlaneUnconfigured noEdge) {
      LOG.debugf("No edge plane: agent %s is minted no credential of its own", id);
      return;
    }
    AgentWorktree agent = require(id);
    if (agent.holdsToken() || agent.state == AgentState.REMOVED) {
      return;
    }
    String projectId = projectOf(agent.repositoryId);
    Optional<WorkspaceToken> minted;
    try {
      minted =
          commissioner
              .get()
              .commissionAgentToken(id.toString(), projectId, GitRefs.read(agent.gitRefs), agent.admin);
    } catch (RuntimeException failed) {
      LOG.warnf("Could not mint agent %s's credential; it is tried again: %s", id, failed.getMessage());
      return;
    }
    if (minted.isEmpty()) {
      return;
    }
    WorkspaceToken token = minted.get();
    boolean stored =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree row = agents.findById(id);
                  if (row == null || row.holdsToken() || row.state == AgentState.REMOVED) {
                    return false;
                  }
                  row.commissionedTokenId = token.tokenId();
                  row.commissionedTokenSubject = token.subject();
                  row.commissionedToken = token.token();
                  return true;
                });
    if (!stored) {
      deleteToken(token.tokenId());
    }
  }

  private void deleteToken(String tokenId) {
    if (tokenId == null || tokenId.isBlank() || !commissioner.isResolvable()) {
      return;
    }
    try {
      commissioner.get().deleteToken(tokenId);
    } catch (RuntimeException e) {
      LOG.warnf("Could not delete agent token %s; the reconcile will reap it: %s", tokenId, e.toString());
    }
  }

  /** The harness environment the agent's credential rides in, and nowhere else (D18). */
  private static Map<String, String> agentEnv(AgentWorktree agent) {
    Map<String, String> env = new LinkedHashMap<>();
    if (agent.holdsToken() && agent.commissionedToken != null) {
      env.put("QITS_TOKEN", agent.commissionedToken);
      env.put("QITS_TOKEN_SUBJECT", agent.commissionedTokenSubject);
    }
    return env;
  }

  /** {@code [refs/heads/<wrapper>, refs/heads/<wrapper>-*]}, never the default branch (D22). */
  static List<String> gitRefsFor(String wrapperBranch, String defaultBranch) {
    return GitRefs.withoutDefaultBranch(
        GitRefs.validated(List.of(GitRefs.of(wrapperBranch), GitRefs.of(wrapperBranch) + "-*")),
        defaultBranch);
  }

  private String projectOf(String repositoryId) {
    try {
      return repositories.find(repositoryId).map(RepositoryLookup.RepositoryView::projectId).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      return null;
    }
  }

  private static String defaultBranchOf(RepositoryLookup.RepositoryView repository) {
    return repository.mainBranch() == null || repository.mainBranch().isBlank()
        ? "master"
        : repository.mainBranch();
  }

  private static void applyFacts(AgentWorktree agent, EntityFacts facts, String blockSource) {
    if (facts == null) {
      return;
    }
    EntityFacts normalized = facts.normalized();
    if (normalized.title() != null) {
      agent.entityTitle = normalized.title();
    }
    if (normalized.status() != null) {
      agent.entityStatus = normalized.status();
    }
    agent.entityBlocked = normalized.blocked();
    agent.blockSource = trimmed(blockSource);
  }

  static String describe(AgentDaemon.CleanupCheck check) {
    List<String> parts = new ArrayList<>();
    if (!check.dirty().isEmpty()) {
      parts.add("uncommitted changes in " + String.join(", ", check.dirty()));
    }
    for (AgentDaemon.CleanupCheck.Unpushed unpushed : check.unpushed()) {
      parts.add(
          unpushed.commits()
              + " unpushed commit(s) in "
              + unpushed.repository()
              + (unpushed.branch() == null ? " on a detached HEAD" : " on " + unpushed.branch()));
    }
    return parts.isEmpty() ? "nothing it could name" : String.join("; ", parts);
  }

  private AgentWorktree require(UUID id) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                agents
                    .findByIdOptional(id)
                    .orElseThrow(() -> new NotFoundException("Agent not found: " + id)));
  }

  private AgentDto view(AgentWorktree agent) {
    return views(List.of(agent)).get(0);
  }

  private List<AgentDto> views(List<AgentWorktree> rows) {
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<UUID, List<AgentDto.Branch>> pushed = new HashMap<>();
    QuarkusTransaction.requiringNew()
        .call(() -> agentBranches.findByAgents(rows.stream().map(a -> a.id).toList()))
        .forEach(
            (AgentBranch b) ->
                pushed
                    .computeIfAbsent(b.key.agentId, id -> new ArrayList<>())
                    .add(new AgentDto.Branch(b.key.repositoryId, b.key.branch, b.sha, b.pushedAt)));
    Map<UUID, AgentWaits.WaitingOn> waitingOn =
        QuarkusTransaction.requiringNew()
            .call(() -> waits.waitingOn(rows.stream().map(a -> a.id).toList()));
    return rows.stream()
        .map(
            a ->
                new AgentDto(
                    a.id.toString(),
                    a.workspaceId,
                    a.repositoryId,
                    a.workId,
                    a.entityId,
                    a.ticketId,
                    a.epicId,
                    a.wrapperBranch,
                    pushed.getOrDefault(a.id, List.of()),
                    a.state,
                    a.runState,
                    a.admin,
                    a.harness,
                    a.sessionId,
                    a.entityTitle,
                    a.entityStatus,
                    a.entityBlocked,
                    a.state == AgentState.REMOVED || !activity.isResolvable()
                        ? null
                        : activity.get().activityOf(a.id.toString()).orElse(null),
                    a.waitingSince,
                    waitingOn.containsKey(a.id) ? waitingOn.get(a.id).labels() : null,
                    waitingOn.containsKey(a.id) ? waitingOn.get(a.id).since() : null,
                    a.lastActivityAt,
                    a.createdAt,
                    a.obsoleteAt,
                    a.removedAt,
                    a.startRefusal,
                    a.startRefusedAt))
        .toList();
  }

  /** Whether this process has started the agent on its workspace's current daemon connection. */
  public boolean isStarted(UUID id) {
    return started.containsKey(id);
  }

  static UUID parse(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(id.trim());
    } catch (IllegalArgumentException notAnAgent) {
      return null;
    }
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
