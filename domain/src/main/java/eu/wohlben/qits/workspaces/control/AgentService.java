package eu.wohlben.qits.workspaces.control;

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
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * agents but runs one harness at a time; an agent waiting for input yields the slot when another
 * agent wants it, and resumes later in the same workspace.
 *
 * <h2>The doors write rows; one scheduler acts on the daemons</h2>
 *
 * <p>Every door here — dispatch, delivery, entity, a person's create, a release's resolution —
 * writes the agent row and answers at once. The daemon calls (start, turn, yield, cleanup check,
 * remove) are made by {@link #reconcile}, one pass at a time on one thread, kicked after every door
 * and run on a timer by {@code wiring/AgentSweep}. So two calls about one agent never race, and
 * what is still to be done is always a row: a restart loses nothing.
 *
 * <p>A pass, in order:
 *
 * <ol>
 *   <li><b>Resume</b>: a workspace with no RUNNING agent gives its slot to a YIELDED agent that has
 *       something to hear — a resume goes before a new agent (D17).
 *   <li><b>Place</b>: each QUEUED agent goes to an empty workspace of its wrapper and posture, else
 *       to a new workspace while the runners have free slots (an admin agent: a new DIRECT admin
 *       workspace), else to a workspace whose agents have all yielded, else it stays QUEUED.
 *   <li><b>Yield</b>: a RUNNING agent that has waited for input longer than {@code
 *       qits.workspace.agent.yield-after} gives up its slot when another agent wants it.
 *   <li><b>Drive</b>: a RUNNING agent whose daemon answers is started (again after a daemon restart:
 *       its credential lives only in daemon memory) and is told what it has still to hear.
 *   <li><b>Clean up</b> (D4, D5, D7): an OBSOLETE agent whose work item is VERIFIED and that has
 *       been idle for {@code qits.workspace.agent.cleanup-idle} is removed if its worktrees are clean
 *       and pushed; otherwise it is handed the cleanup prompt instead.
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

  private static final String VERIFIED = "VERIFIED";

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
   * How long a RUNNING agent waits for input before it yields its slot to another agent that wants
   * it — the blocked-detection time ({@code qits.projects.agent-waiting.debounce}, 30 s).
   */
  @ConfigProperty(name = "qits.workspace.agent.yield-after", defaultValue = "PT30S")
  Duration yieldAfter;

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
   * keeps the agent credential in memory only, so after a restart every agent is started again.
   */
  private final Map<UUID, Long> started = new ConcurrentHashMap<>();

  /** Since when a pending turn waits for a busy agent, for {@link #turnWindowMs}. */
  private final Map<UUID, Instant> pendingSince = new ConcurrentHashMap<>();

  /** The last refusal logged per agent, so a refusing daemon is logged once and not every pass. */
  private final Map<UUID, String> lastRefusal = new ConcurrentHashMap<>();

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
   * idempotently. An ACTIVE agent of the item is the answer ({@code fresh:false}); otherwise one is
   * created and placed. Never a 409 for an existing agent: the caller presses this to reach a state.
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

    Optional<AgentWorktree> existing = QuarkusTransaction.requiringNew().call(() -> agents.findActiveByWorkId(workId));
    if (existing.isPresent()) {
      return found(existing.get().id, request);
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
      Optional<AgentWorktree> winner =
          QuarkusTransaction.requiringNew().call(() -> agents.findActiveByWorkId(workId));
      if (winner.isEmpty()) {
        throw raced;
      }
      return found(winner.get().id, request);
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

  /** A dispatch that found the item's agent: its facts refreshed, an idle one told the instruction. */
  private Dispatch found(UUID id, AgentRequest request) {
    String instruction = trimmed(request.instruction());
    AgentLaunch launch =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree agent = agents.findById(id);
                  applyFacts(agent, request.facts(), null);
                  if (agent.runState == AgentRunState.RUNNING && started.containsKey(id)) {
                    return AgentLaunch.SKIPPED_RUNNING;
                  }
                  if (instruction != null && agent.pendingText == null) {
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
   * dispatched has nobody to tell, and that is answered, not refused. A yielded agent resumes to
   * hear it (D16); a queued one hears it as part of its first turn.
   *
   * <p>An OBSOLETE agent is told too. A release of one of its branches marks it OBSOLETE, but the
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
                  row.pendingText = row.pendingText == null ? said : row.pendingText + "\n\n" + said;
                  row.pendingCompact = row.pendingCompact || (compactFirst && compactBeforeTurn);
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
              "The agent had yielded its workspace; it resumes with this turn once the slot is"
                  + " free.");
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
   * Store the work item's title, status word and blocked flag on its agent, and tell a running
   * harness live. The facts are what the daemon names the session after, and the status is what
   * the cleanup sweep reads (VERIFIED, D4).
   */
  public EntityMark markEntity(String workId, EntityFacts facts, String blockSource) {
    return mark(workId, facts, blockSource, false);
  }

  /** {@link #markEntity} for the blocked flag alone; title and status stay. */
  public EntityMark markBlocked(String workId, boolean blocked, String blockSource) {
    return mark(workId, new EntityFacts(null, null, blocked), blockSource, true);
  }

  private EntityMark mark(String workId, EntityFacts facts, String blockSource, boolean blockedOnly) {
    Optional<AgentWorktree> stored =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    agents
                        .findLiveByWorkId(workId)
                        .map(
                            agent -> {
                              if (blockedOnly) {
                                agent.entityBlocked = facts.blocked();
                                agent.blockSource = trimmed(blockSource);
                              } else {
                                applyFacts(agent, facts, blockSource);
                              }
                              return agent;
                            }));
    if (stored.isEmpty()) {
      return new EntityMark(null, false);
    }
    AgentWorktree agent = stored.get();
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
   * A release deleted {@code branch} of {@code repositoryId}: every ACTIVE agent that owns it — as
   * its wrapper branch, or as a branch it pushed — is marked OBSOLETE (D4). Nothing is removed
   * here; the cleanup sweep does that once the item is VERIFIED and the agent idle. A workspace of
   * the old model standing on the branch is resolved as before (the drain, D20).
   */
  public Resolution resolveReleasedBranch(
      String repositoryId, String branch, String target, String commit, String result) {
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("A branch is required to resolve a released branch.");
    }
    List<String> marked =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Map<UUID, AgentWorktree> owners = new LinkedHashMap<>();
                  agents.findActiveByWrapperBranch(repositoryId, branch).forEach(a -> owners.put(a.id, a));
                  for (UUID id : agentBranches.agentsOn(repositoryId, branch)) {
                    AgentWorktree agent = agents.findById(id);
                    if (agent != null && agent.state == AgentState.ACTIVE) {
                      owners.putIfAbsent(id, agent);
                    }
                  }
                  Instant now = Instant.now();
                  List<String> ids = new ArrayList<>();
                  for (AgentWorktree agent : owners.values()) {
                    agent.state = AgentState.OBSOLETE;
                    agent.obsoleteAt = now;
                    ids.add(agent.id.toString());
                  }
                  return ids;
                });
    if (!marked.isEmpty()) {
      LOG.infof(
          "Release %s consumed branch '%s' of %s: agents %s are obsolete",
          target, branch, repositoryId, marked);
      kick();
      return new Resolution(true, marked);
    }
    boolean legacy =
        workspaces.resolveReleasedBranch(repositoryId, branch, target, commit, result).resolved();
    return new Resolution(legacy, List.of());
  }

  /**
   * A person removes an agent now (the agent page's discard). Leftover work refuses with 409 and
   * the cleanup check, unless {@code force}; an unreachable daemon refuses too, unless {@code force}.
   */
  public AgentDto discard(UUID id, boolean force) {
    AgentWorktree agent = require(id);
    if (agent.state == AgentState.REMOVED) {
      return view(agent);
    }
    if (agent.workspaceId != null && daemon.isResolvable()) {
      AgentDaemon port = daemon.get();
      Optional<AgentDaemon.CleanupCheck> check =
          port.reachable(agent.workspaceId)
              ? port.cleanupCheck(agent.workspaceId, id.toString())
              : Optional.empty();
      if (!force) {
        if (check.isEmpty()) {
          throw new ConflictException(
              "AGENT_DAEMON_UNREACHABLE",
              "The agent's workspace daemon does not answer, so its worktrees cannot be checked;"
                  + " discard with force to remove the agent anyway.");
        }
        if (!check.get().clean()) {
          throw new ConflictException(
              "AGENT_HAS_LEFTOVER_WORK",
              "The agent's worktrees hold work that never reached the git host: "
                  + describe(check.get())
                  + ". Discard with force to throw it away.");
        }
      }
      if (check.isPresent()) {
        port.remove(agent.workspaceId, id.toString(), true);
      }
    }
    markRemoved(id, "discarded by a person");
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
   * from it; the relay is now per agent and stored, so a reconnect does not repeat it.
   */
  public void onActivity(
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
    String[] relay =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  AgentWorktree agent = agents.findById(id);
                  if (agent == null || agent.state == AgentState.REMOVED) {
                    return null;
                  }
                  agent.lastActivityAt = when;
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
    if (relay != null) {
      // With an OPEN wait, a waiting agent is reported as waiting on it (qits-1153, W5).
      waits.relayActivity(relay[0], id, awaitingInput, hookEvent, sessionId, when);
    }
    if (Boolean.TRUE.equals(awaitingInput)) {
      kick();
    }
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
    AgentWorktree agent = QuarkusTransaction.requiringNew().call(() -> agents.findById(id));
    if (agent == null) {
      LOG.debugf("A push was reported for unknown agent %s", agentId);
      return;
    }
    String repositoryId = repositoryIdOf(agent.repositoryId, repositoryName);
    if (repositoryId == null) {
      LOG.warnf(
          "Agent %s pushed '%s' of a repository named %s, which its project does not list; not"
              + " recorded",
          agentId, branch, repositoryName);
      return;
    }
    QuarkusTransaction.requiringNew()
        .run(() -> agentBranches.upsert(id, repositoryId, branch, sha, Instant.now()));
    changePublisher.fire(agent.repositoryId, agent.workspaceId, WorkspaceChangeHint.Topic.AGENTS);
  }

  /** A workspace's daemon connected or went: its agents are started again on the next pass. */
  public void onDaemonConnection(Long workspaceRowId) {
    started.values().removeIf(row -> Objects.equals(row, workspaceRowId));
    kick();
  }

  private String repositoryIdOf(String wrapperId, String name) {
    try {
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
    } catch (RuntimeException registryDidNotAnswer) {
      LOG.debugf("could not map repository name %s: %s", name, registryDidNotAnswer.toString());
      return null;
    }
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

  /** Step 1: a free workspace goes to a yielded agent with something to hear before anyone new. */
  void resumeYielded() {
    synchronized (placeLock) {
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                Map<Long, List<AgentWorktree>> byWorkspace = placedByWorkspace();
                for (Map.Entry<Long, List<AgentWorktree>> entry : byWorkspace.entrySet()) {
                  List<AgentWorktree> live = entry.getValue();
                  if (live.stream().anyMatch(a -> a.runState == AgentRunState.RUNNING)) {
                    continue;
                  }
                  live.stream()
                      .filter(a -> a.runState == AgentRunState.YIELDED && a.pendingText != null)
                      .findFirst()
                      .ifPresent(
                          a -> {
                            a.runState = AgentRunState.RUNNING;
                            LOG.infof("Agent %s resumes in workspace %s", a.id, a.workspaceId);
                          });
                }
              });
    }
  }

  /** Step 2: each QUEUED agent to a slot (D17). */
  void placeQueued() {
    synchronized (placeLock) {
      List<AgentWorktree> queued = QuarkusTransaction.requiringNew().call(() -> agents.findQueued());
      for (AgentWorktree agent : queued) {
        Long slot = QuarkusTransaction.requiringNew().call(() -> emptySlotFor(agent));
        if (slot == null && hasCapacity(agent.admin)) {
          try {
            slot = workspaces.createSlot(agent.repositoryId, agent.admin).id;
          } catch (RuntimeException refused) {
            LOG.warnf(
                refused, "Could not create a workspace for agent %s; it stays queued", agent.id);
          }
        }
        if (slot == null) {
          slot = QuarkusTransaction.requiringNew().call(() -> yieldedSlotFor(agent));
        }
        if (slot == null) {
          continue;
        }
        Long chosen = slot;
        try {
          QuarkusTransaction.requiringNew()
              .run(
                  () -> {
                    AgentWorktree row = agents.findById(agent.id);
                    if (row.state != AgentState.ACTIVE || row.runState != AgentRunState.QUEUED) {
                      return;
                    }
                    row.workspaceId = chosen;
                    row.runState = AgentRunState.RUNNING;
                    agents.flush();
                  });
          LOG.infof("Agent %s placed in workspace %s", agent.id, chosen);
          changePublisher.fire(agent.repositoryId, chosen, WorkspaceChangeHint.Topic.AGENTS);
        } catch (RuntimeException taken) {
          // uq_agent_worktree_running: another agent took the slot first. The next pass tries again.
          LOG.debugf("Workspace %s was taken before agent %s got it", chosen, agent.id);
        }
      }
    }
  }

  /** An ACTIVE workspace of the agent's wrapper and posture that hosts no live agent. */
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
      if (!live.isEmpty()
          && live.stream().allMatch(a -> a.runState == AgentRunState.YIELDED && a.pendingText == null)) {
        return workspace.id;
      }
    }
    return null;
  }

  /** The ACTIVE slot workspaces (no branch of their own) of an agent's wrapper and posture. */
  private List<Workspace> slotsOf(AgentWorktree agent) {
    return workspaceRepository.findActiveByRepositoryId(agent.repositoryId).stream()
        .filter(w -> w.branch == null && w.admin == agent.admin)
        .toList();
  }

  /**
   * Whether a new workspace may be created: always for an admin agent (DIRECT, on the platform
   * host), and while the runners' slots outnumber the runner-placed workspaces otherwise.
   */
  boolean hasCapacity(boolean admin) {
    if (admin) {
      return true;
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> runnerRepository.eligibleSlots() > workspaceRepository.countActiveOnRunners());
  }

  /** Step 3: a RUNNING agent waiting for input yields to an agent that wants its slot (D14, D16). */
  void yieldBlocked() {
    synchronized (passLock) {
      if (!daemon.isResolvable()) {
        return;
      }
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
        AgentDaemon.Outcome outcome =
            started.containsKey(agent.id)
                ? daemon.get().yield(agent.workspaceId, agent.id.toString())
                : AgentDaemon.Outcome.OK;
        if (outcome == AgentDaemon.Outcome.OK || outcome == AgentDaemon.Outcome.UNKNOWN_AGENT) {
          QuarkusTransaction.requiringNew()
              .run(
                  () -> {
                    AgentWorktree row = agents.findById(agent.id);
                    if (row.runState == AgentRunState.RUNNING) {
                      row.runState = AgentRunState.YIELDED;
                      row.waitingSince = null;
                    }
                  });
          started.remove(agent.id);
          LOG.infof("Agent %s yielded workspace %s", agent.id, agent.workspaceId);
          resumeYielded();
          placeQueued();
        }
      }
    }
  }

  /** Step 4: start the RUNNING agents and tell them what they have still to hear. */
  void driveRunning() {
    synchronized (passLock) {
      List<AgentWorktree> running =
          QuarkusTransaction.requiringNew()
              .call(
                  () ->
                      agents.findPlacedLive().stream()
                          .filter(a -> a.runState == AgentRunState.RUNNING)
                          .toList());
      for (AgentWorktree agent : running) {
        try {
          drive(agent);
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not drive agent %s", agent.id);
        }
      }
    }
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
      return;
    }
    if (!daemonLive(workspace.id) || !daemon.isResolvable()) {
      return;
    }
    AgentDaemon port = daemon.get();
    if (!started.containsKey(agent.id)) {
      if (!agent.holdsToken()) {
        mintToken(agent.id);
      }
      AgentWorktree fresh = require(agent.id);
      String instruction = fresh.pendingText;
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
        if (answer.outcome() == AgentDaemon.Outcome.REFUSED
            && !Objects.equals(lastRefusal.put(agent.id, String.valueOf(answer.detail())), answer.detail())) {
          LOG.warnf("Workspace %s refused to start agent %s: %s", workspace.id, agent.id, answer.detail());
        }
        return;
      }
      lastRefusal.remove(agent.id);
      started.put(agent.id, workspace.id);
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                AgentWorktree row = agents.findById(agent.id);
                row.commandId = answer.commandId() == null ? row.commandId : answer.commandId();
                row.sessionId = answer.sessionId() == null ? row.sessionId : answer.sessionId();
                row.lastActivityAt = Instant.now();
                if (Objects.equals(row.pendingText, instruction)) {
                  row.pendingText = null;
                }
              });
      LOG.infof(
          "Agent %s %s in workspace %s",
          agent.id, answer.resumed() ? "resumed" : "started", workspace.id);
      changePublisher.fire(agent.repositoryId, workspace.id, WorkspaceChangeHint.Topic.AGENTS);
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
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(agent.id);
              if (Objects.equals(row.pendingText, text)) {
                row.pendingText = null;
              }
              row.pendingCompact = false;
              row.waitingSince = null;
              row.lastActivityAt = Instant.now();
            });
  }

  /** Step 5: OBSOLETE agents of VERIFIED items, idle long enough, are removed or prompted (D4–D7). */
  void cleanUp() {
    synchronized (passLock) {
      Instant idleBefore = Instant.now().minus(cleanupIdle);
      List<AgentWorktree> obsolete =
          QuarkusTransaction.requiringNew().call(() -> agents.findObsolete());
      for (AgentWorktree agent : obsolete) {
        try {
          cleanUp(agent, idleBefore);
        } catch (RuntimeException e) {
          LOG.warnf(e, "Could not clean up agent %s", agent.id);
        }
      }
    }
  }

  private void cleanUp(AgentWorktree agent, Instant idleBefore) {
    if (!VERIFIED.equalsIgnoreCase(agent.entityStatus)) {
      return;
    }
    if (agent.lastActivityAt != null && agent.lastActivityAt.isAfter(idleBefore)) {
      return;
    }
    if (agent.pendingText != null
        || (activity.isResolvable()
            && activity.get().activityOf(agent.id.toString()).orElse(null)
                == AgentActivityState.BUSY)) {
      return;
    }
    if (agent.workspaceId == null) {
      markRemoved(agent.id, "obsolete, and never placed");
      return;
    }
    Workspace workspace =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findByIdOptional(agent.workspaceId).orElse(null));
    if (workspace == null || workspace.status != WorkspaceStatus.ACTIVE) {
      markRemoved(agent.id, "obsolete, and its workspace is gone");
      return;
    }
    if (!daemonLive(workspace.id)) {
      if (workspace.runtimeStatus == WorkspaceRuntimeStatus.STOPPED) {
        // The check needs the daemon: start the workspace to clean it up.
        startQuietly(workspace.id);
      }
      return;
    }
    if (!daemon.isResolvable()) {
      return;
    }
    AgentDaemon port = daemon.get();
    Optional<AgentDaemon.CleanupCheck> check = port.cleanupCheck(workspace.id, agent.id.toString());
    if (check.isEmpty()) {
      return;
    }
    if (check.get().clean()) {
      AgentDaemon.Outcome removed = port.remove(workspace.id, agent.id.toString(), false);
      if (removed == AgentDaemon.Outcome.OK || removed == AgentDaemon.Outcome.UNKNOWN_AGENT) {
        markRemoved(agent.id, "released, idle and clean");
        return;
      }
      if (removed != AgentDaemon.Outcome.REFUSED) {
        return;
      }
    }
    // Leftover work: handed back to the agent, never deleted (D5). Prompted again only once the
    // agent has worked since the last prompt, so an agent that cannot fix it is not nagged.
    boolean promptAgain =
        agent.cleanupPromptedAt == null
            || (agent.lastActivityAt != null && agent.lastActivityAt.isAfter(agent.cleanupPromptedAt));
    if (!promptAgain) {
      return;
    }
    String prompt = CLEANUP_PROMPT + "\n\nWhat the check found: " + describe(check.get()) + ".";
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              AgentWorktree row = agents.findById(agent.id);
              row.pendingText = prompt;
              row.cleanupPromptedAt = Instant.now();
            });
    LOG.infof("Agent %s has leftover work; it is prompted to clean up", agent.id);
  }

  /** Step 6: a slot workspace with no live agent goes (D2), container, volume and credential. */
  void removeEmptyWorkspaces() {
    synchronized (passLock) {
      Instant orphanBefore = Instant.now().minus(ORPHAN_SLOT_GRACE);
      List<Long> empty =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    List<Long> out = new ArrayList<>();
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
                      out.add(workspace.id);
                    }
                    return out;
                  });
      for (Long id : empty) {
        try {
          workspaces.discardWorkspace(id, "Removed: its last agent was removed (qits-1152, D2).", true);
          LOG.infof("Workspace %s removed: no agent left in it", id);
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

  /** The placed, live agents by workspace, in one read. Called inside a transaction. */
  private Map<Long, List<AgentWorktree>> placedByWorkspace() {
    Map<Long, List<AgentWorktree>> byWorkspace = new LinkedHashMap<>();
    for (AgentWorktree agent : agents.findPlacedLive()) {
      byWorkspace.computeIfAbsent(agent.workspaceId, id -> new ArrayList<>()).add(agent);
    }
    return byWorkspace;
  }

  /** REMOVED: the row stays as history; the credential and the prompt draft go. */
  void markRemoved(UUID id, String why) {
    String tokenId =
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
                  return held == null ? "" : held;
                });
    started.remove(id);
    pendingSince.remove(id);
    if (tokenId == null) {
      return;
    }
    LOG.infof("Agent %s removed: %s", id, why);
    deleteToken(tokenId);
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
    Map<UUID, String> waitingOn =
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
                    waitingOn.get(a.id),
                    a.lastActivityAt,
                    a.createdAt,
                    a.obsoleteAt,
                    a.removedAt))
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
