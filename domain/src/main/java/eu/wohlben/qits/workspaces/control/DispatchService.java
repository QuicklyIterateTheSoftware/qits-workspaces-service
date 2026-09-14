package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The machine door's semantics: <b>there should be a workspace on this branch with an agent working
 * in it</b>, said idempotently and answered at once.
 *
 * <p>The sibling of {@link EditorService} — one call, poll it, it tells you what it did — and of
 * {@link CaptureService}, whose create this reuses verbatim. What is new is the third step: nothing
 * on the host has ever <em>started</em> a coding agent. Every agent in this system was launched by a
 * browser through {@code ContainerProxyRoute}; qits-projects has no browser, so the launch has to
 * originate here. {@link WorkspaceAgentLauncher} is the seam that makes that possible without
 * dragging a transport into {@code domain}.
 *
 * <h2>Three steps, and the third one outlives the request</h2>
 *
 * <ol>
 *   <li><b>Find or create</b> on {@code (repository, branch)}. An ACTIVE workspace already standing
 *       on the branch is the answer, with {@code fresh:false} — never a 409. The caller is a
 *       machine that presses this to <em>get to</em> a state, and will press it again; a conflict
 *       would make the second press an error instead of a no-op.
 *   <li><b>Ensure the container</b>, unless a technical process is already running for the workspace
 *       or its daemon is already answering. That guard is {@code EditorService.worthStarting}'s, for
 *       {@code EditorService.worthStarting}'s reason: this door is polled, and an ensure per poll is
 *       one provision per tick through a multi-gigabyte image pull.
 *   <li><b>Launch the agent when the daemon answers</b> — which can be minutes away, so it happens
 *       on a thread of this service's own, long after the response went out.
 * </ol>
 *
 * <h2>The scheduled launch is in memory, and a restart drops it</h2>
 *
 * <p>There is no launch queue, no row and no outbox. A process restart between the dispatch and the
 * daemon coming up loses the pending launch, and the workspace is simply left sitting there with
 * its goal and no agent in it. That is acceptable <em>because</em> the door is idempotent: a
 * re-press finds the workspace, finds no agent running, and launches — so recovery is the same call
 * the caller already knows how to make, and the caller (qits-projects, driving a ticket) is the one
 * holding the intent worth persisting. Durable scheduling here would be a second copy of that
 * intent, and two copies of an intent is how a ticket gets two agents.
 *
 * <p><b>The instruction is not stored.</b> It rides into the launch and nowhere else — not a column,
 * not an event, not the workspace. An instruction is the first turn of one conversation; keeping it
 * would make it look like the statement of the work.
 *
 * <h2>What a dispatched workspace says it is for</h2>
 *
 * <p>Its {@link WorkspaceSubject} — the ticket or epic id the caller named — and <b>not</b> a
 * preamble. qits-projects used to render the whole ticket into the goal, which was a stale copy of
 * something the agent is told in the same breath to read live, and it buried the one fact a person
 * scanning the workspace list wants. The caller may still send a preamble and it is still written
 * where it does; the dispatch doors at qits-projects send none.
 *
 * <h2>{@link #dispatch} puts an agent on a branch; {@link #deliver} talks to the one that is there</h2>
 *
 * <p>They are the same three steps with one difference, and the difference is the whole of it:
 * <b>{@code deliver} never creates a workspace.</b> A dispatch is "there should be a workspace on
 * this branch with an agent working in it", so a branch with none is a branch to make one on. A
 * delivery is "say this to the workspace's agent" — a sentence about a workspace that exists — so a
 * branch with none is answered with the fact and nothing happens. The first caller is a ticket whose
 * status moved: a ticket nobody ever dispatched has no workspace, and a status a person dragged
 * across a board must not conjure a container, a branch and an agent as a side effect of being
 * dragged.
 *
 * <p>The other difference is what a workspace with no agent running means. To {@code dispatch} it is
 * the ordinary case — launch. To {@code deliver} it is a <em>fallback</em>, and the same one:
 * whatever was to be said becomes the seed turn of a new session, because a text nobody hears is
 * worth less than a session that starts by hearing it. Which of the two happened is what the answer
 * reports; the caller does not choose.
 *
 * <p>Nothing here knows what a ticket is, deliberately. The phase machinery in qits-projects is this
 * verb's first caller and not its definition.
 *
 * <h2>A failed launch is a warning, and never anything else</h2>
 *
 * <p>By the time a launch can fail the response has been sent, so there is nothing to fail. It is
 * logged at WARN and left there. It is deliberately <b>not</b> a workspace event: {@code
 * WorkspaceEventType} is a five-value <em>lifecycle</em> vocabulary — created, merged, updated from
 * parent, integrated, abandoned — and every entry in it is a statement about the branch's history.
 * An agent that did not start is not a thing that happened to the branch, and widening the timeline
 * vocabulary for it would put a non-event in the history record every reader of a workspace reads.
 */
@ApplicationScoped
public class DispatchService {

  private static final Logger LOG = Logger.getLogger(DispatchService.class);

  /** What the door did about the agent. */
  public enum AgentLaunch {
    /** A launch is on its way — immediately, or as soon as the daemon answers. */
    SCHEDULED,
    /** An agent command was already running in the workspace, so nothing was started. */
    SKIPPED_RUNNING
  }

  /**
   * What the door answers.
   *
   * @param workspace the workspace the dispatch landed on, fresh or found — the full view, not the
   *     thin create shape, because the caller's whole reason to read it is the branch and the
   *     runtime status, and {@code WorkspaceMapper.toDto} deliberately carries neither
   * @param fresh whether this call created the workspace. False is the ordinary answer on a
   *     re-press and is not an error
   * @param agentLaunch what happened about the agent
   * @param technicalProcessId the container start this call joined or began, to watch at {@code
   *     /workspaces/api/technical-processes/{id}/events}; null when no start was needed
   */
  public record Dispatch(
      WorkspaceDto workspace, boolean fresh, AgentLaunch agentLaunch, String technicalProcessId) {}

  /**
   * What {@link #deliver} answers: which workspace was spoken to, what was done about it, and a
   * sentence a machine caller can log verbatim.
   *
   * <p><b>Both booleans are about the arm this call TOOK, read from the workspace's state at the
   * moment of the call — they are not receipts.</b> The daemon round trip happens on the wait thread
   * for {@code dispatch}'s reason (a container that is coming back from an idle stop is minutes
   * away, and no caller may be held for it), so by the time anything is confirmed this answer is
   * long gone. A caller that needs to know the turn landed watches the workspace, not this record.
   * Both false with a {@code workspaceId} means the container was not answering yet and the arm is
   * chosen when it does; both false with a null one means there was nobody to tell.
   *
   * @param workspaceId the ACTIVE workspace's row id — {@code Workspace.id}, the id every route and
   *     port here is keyed on, not the branch-derived label. Null when no workspace stands on the
   *     branch, which is a normal outcome and not an error
   * @param delivered an agent was running, so the text is on its way to it as a user turn
   * @param launched no agent was running, so one is being launched with the text as its seed turn —
   *     {@link #dispatch}'s path, reached without creating anything
   * @param detail one sentence, always present, saying which of those it was and why
   */
  public record Delivery(Long workspaceId, boolean delivered, boolean launched, String detail) {}

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceService workspaces;

  /** The daemon's agent surface. Absent is a supported configuration; see the port. */
  @Inject Instance<WorkspaceAgentLauncher> agents;

  /** Optional like everywhere else: absent answers "no process is running", which is an answer. */
  @Inject Instance<WorkspaceProcessTracker> processes;

  /**
   * How long a scheduled launch keeps waiting for the daemon to answer. Generous because the thing
   * being waited on is an image pull, which is measured in minutes on a cold host; bounded because a
   * container that is never coming up must not hold a thread forever.
   */
  @ConfigProperty(name = "qits.workspace.agent-dispatch.launch-window-ms", defaultValue = "900000")
  long launchWindowMs;

  /** How often the wait asks the daemon whether it is answering yet. */
  @ConfigProperty(name = "qits.workspace.agent-dispatch.poll-interval-ms", defaultValue = "2000")
  long pollIntervalMs;

  /**
   * Whether {@link #deliver} says {@code /compact} to the agent before the caller's text, when the
   * caller asked for it.
   *
   * <p><b>It ships OFF, and the reason is that nobody has watched it work.</b> Whether Claude Code
   * in ACP/chat mode treats a delivered {@code /compact} as the slash command a person typing it
   * gets, or simply echoes it back as the first line of a turn, is <em>not established</em> — the
   * daemon-side spike that would settle it is recorded as unrun in that route's javadoc, and this
   * host cannot tell the two apart from the outside: both answer {@code delivered:true}. A knob
   * defaulted on would therefore ship a turn whose effect nobody has seen, ahead of every phase
   * prompt, on the caller that matters most.
   *
   * <p><b>Off is not a degraded mode.</b> The epic's own position is that a phase prompt arriving as
   * the first turn after a reset is an acceptable substitute for compaction: the context the prompt
   * needs is in the prompt and in the ticket it names, not in the turns before it.
   *
   * <p><b>What would justify turning it on</b> is one observation, and it is a reading of a
   * transcript rather than a green test: a session whose transcript shows the delivered
   * {@code /compact} taking effect as a command — a compaction boundary in the harness's own log,
   * and the next turn answering with the summarised context — rather than an assistant turn quoting
   * the word back. Until somebody has read that, this stays false and the caller's {@code
   * compactFirst} is a request nothing acts on.
   */
  @ConfigProperty(
      name = "qits.workspace.agent-dispatch.compact-before-turn",
      defaultValue = "false")
  boolean compactBeforeTurn;

  /**
   * The waits. Cached rather than fixed: each one is a workspace's, they last as long as an image
   * pull, and there is no sensible number of concurrent dispatches to cap at — the same reasoning
   * (and the same daemon threads) as {@code WorkspaceService}'s provision executor.
   */
  private final ExecutorService launchExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-agent-dispatch");
            thread.setDaemon(true);
            return thread;
          });

  /**
   * Workspaces with a wait already running — a launch's or a delivery's, one set for both. This is
   * what keeps a polled door from queueing one wait per press: the daemon's own RUNNING answer
   * cannot cover the window between a launch being scheduled and the agent's first session
   * appearing, so the in-process fact has to. {@link #scheduleDelivery} says why a delivery claims
   * the same slot rather than one of its own.
   */
  private final Set<Long> pending = ConcurrentHashMap.newKeySet();

  @PreDestroy
  void shutdown() {
    launchExecutor.shutdownNow();
  }

  /**
   * Dispatch an agent onto {@code branch} of {@code repositoryId}.
   *
   * @param branch the branch the work is to happen on, e.g. {@code ticket/fix-login}. It may already
   *     have a workspace; that is the idempotent case, not a conflict
   * @param branchTree whether the workspace forks the whole submodule tree (an aggregate wrapper),
   *     passed through to the ordinary create
   * @param preamble the workspace's goal, in markdown — a person's prose, and normally absent here
   * @param subject what the workspace is for: the ticket or epic this dispatch is about
   * @param instruction the agent's first turn. Carried into the launch and stored nowhere
   * @throws eu.wohlben.qits.workspaces.error.NotFoundException no such repository — nothing is
   *     created, the same fail-closed answer {@link CaptureService} gives
   */
  public Dispatch dispatch(
      String repositoryId,
      String branch,
      boolean branchTree,
      String preamble,
      WorkspaceSubject subject,
      String instruction) {
    return dispatch(repositoryId, branch, branchTree, preamble, subject, instruction, null);
  }

  /**
   * The same dispatch, stating the Git refs the workspace's container may push (contract C4).
   *
   * @param gitRefs exact refs and trailing {@code /*} patterns; null means the workspace's own
   *     branch. <b>Only a fresh workspace takes it.</b> A re-press onto an existing workspace keeps
   *     the list that workspace has — it may have been narrowed since, and a re-press must not undo
   *     that. An entry that covers the repository's default branch is dropped and logged: that
   *     branch moves only through a release request
   * @throws eu.wohlben.qits.workspaces.error.BadRequestException a list that breaks the C1 rules,
   *     on every press, before anything is created
   */
  public Dispatch dispatch(
      String repositoryId,
      String branch,
      boolean branchTree,
      String preamble,
      WorkspaceSubject subject,
      String instruction,
      List<String> gitRefs) {
    List<String> stated = gitRefs == null ? null : GitRefs.validated(gitRefs);
    RepositoryLookup.RepositoryView repository = repositories.require(repositoryId);

    // Idempotence is checked on the branch the caller ASKED for before the fallback shape is even
    // computed. The two cannot normally both exist — git's ref namespace is filesystem-like, so a
    // literal `ticket` blocks every `ticket/*` — but a literal ref created after a `ticket/x`
    // workspace existed would make the fallback the only branch consulted, and this door would
    // answer a second workspace where it should have answered the first one's.
    Optional<Long> found = activeOn(repositoryId, branch);
    String target = found.isPresent() ? branch : dispatchBranch(repositoryId, branch);
    Long rowId = found.or(() -> activeOn(repositoryId, target)).orElse(null);

    boolean fresh = rowId == null;
    if (fresh) {
      // Exactly CaptureService.capture's create, with the caller's branch instead of a generated
      // one: the slug is derived from the branch by the same rule, the fork point is the
      // repository's main branch, and the goal becomes the preamble. Never adopting: a dispatch
      // onto a branch that exists but has no workspace should create the ref it was told to work
      // on, and a typo must fail loudly rather than silently attach to somebody else's branch.
      Workspace created =
          workspaces.createWorkspace(
              repositoryId,
              WorkspaceService.toWorkspaceSlug(target),
              repository.mainBranch(),
              target,
              preamble,
              false,
              branchTree,
              false,
              subject == null ? WorkspaceSubject.none() : subject,
              stated);
      rowId = created.id;
    }

    return withAgent(rowId, fresh, instruction);
  }

  /** Steps two and three: the container, and the agent that is to work in it. */
  private Dispatch withAgent(Long rowId, boolean fresh, String instruction) {
    WorkspaceAgentLauncher.AgentState state = agentState(rowId);
    if (state == WorkspaceAgentLauncher.AgentState.RUNNING) {
      // Somebody is already working here. Launching beside them would put two agents on one
      // checkout, which is a merge conflict with itself.
      return new Dispatch(
          workspaces.getWorkspace(rowId), fresh, AgentLaunch.SKIPPED_RUNNING, activeProcess(rowId));
    }

    String technicalProcessId = activeProcess(rowId);
    if (state == WorkspaceAgentLauncher.AgentState.UNREACHABLE && technicalProcessId == null) {
      // No daemon answering and nothing already bringing the container up: this is the start. A
      // daemon that IS answering needs no ensure at all — the same short-circuit EditorService
      // makes, and for the same reason: this door is polled.
      technicalProcessId = workspaces.beginEnsureContainer(rowId);
    }
    schedule(rowId, instruction);
    return new Dispatch(
        workspaces.getWorkspace(rowId), fresh, AgentLaunch.SCHEDULED, technicalProcessId);
  }

  /**
   * Say {@code text} to the agent working on {@code branch} of {@code repositoryId} — the same thing
   * a person would type into that workspace's chat tab, said by a machine.
   *
   * <p>Three arms, chosen by what is there and never by the caller:
   *
   * <ol>
   *   <li><b>No workspace stands on the branch</b> — nothing happens, and the answer says so with a
   *       null {@code workspaceId}. This is the one way this differs from {@link #dispatch}: it
   *       <b>never creates a workspace</b>. See the class javadoc for why a status moved by hand
   *       must not conjure one.
   *   <li><b>The container is not answering</b> — it is ensured exactly as a dispatch ensures it,
   *       behind the same {@code EditorService.worthStarting} guard, because an idle-stopped
   *       container is the ordinary state of a workspace between two phases and not an error. The
   *       arm below is then chosen on the wait thread, once the daemon answers.
   *   <li><b>Speak, or launch.</b> An agent is running → the text is delivered to it as a user turn.
   *       No agent is running (the session ended) → it becomes the seed turn of a launch, which is
   *       the path {@link #dispatch} already walks and which needs no waiting of its own.
   * </ol>
   *
   * <p><b>Why the wait in front of the turn is not optional.</b> The first caller transitions a
   * ticket <em>as the last act of a turn</em> — the agent's own tool call is what moves the status —
   * so the phase prompt this produces is aimed at a session that is, at that instant, still
   * finishing the turn that asked for it. Something has to stand between the two, or the text lands
   * mid-turn and is read as an interruption of the work it is supposed to follow. That wait is
   * bounded by the launch window and ticks at the poll interval, so a container that is never coming
   * back costs one thread for that window and then a WARN.
   *
   * <p><b>What the wait can and cannot see, which is worth knowing before trusting it.</b> The only
   * idleness the daemon exposes is command-level: {@link WorkspaceAgentLauncher#agentState} reads
   * {@code GET /commands?status=RUNNING}, and a chat-mode agent's command stays RUNNING for the
   * whole session — between turns as much as during one. So this waits for a daemon that answers,
   * and it cannot wait out a turn already in flight; there is no field on that wire that would let
   * it. Closing that gap means a per-session busy/idle signal from the daemon, and until one exists
   * the honest statement is that the turn is delivered to a live session and the harness queues it.
   * Do not read the wait below as more than it is.
   *
   * @param text the turn, verbatim. Blank is refused at the door before this is called
   * @param compactFirst whether to say {@code /compact} ahead of it. A request, not an instruction:
   *     it is honoured only when {@code qits.workspace.agent-dispatch.compact-before-turn} is on,
   *     which it is not by default — see that field for what would justify turning it on
   */
  public Delivery deliver(String repositoryId, String branch, String text, boolean compactFirst) {
    // Find only. The requested branch first, then the dash shape a dispatch would have fallen back
    // to — computed as a string and NOT by asking the git host whether the literal first segment
    // exists, unlike dispatchBranch: that read is a network call that throws when the host is down,
    // and this verb answers "nobody to tell" for a branch it cannot find rather than failing. The
    // two shapes carry the same workspace slug by construction, so a hit on either is the same
    // workspace the dispatch made.
    Long rowId =
        activeOn(repositoryId, branch)
            .or(() -> activeOn(repositoryId, dashShape(branch)))
            .orElse(null);
    if (rowId == null) {
      return new Delivery(
          null,
          false,
          false,
          "no workspace stands on "
              + branch
              + " in repository "
              + repositoryId
              + "; nothing was delivered and nothing was created");
    }

    WorkspaceAgentLauncher.AgentState state = agentState(rowId);
    if (state == WorkspaceAgentLauncher.AgentState.UNREACHABLE && activeProcess(rowId) == null) {
      // The dispatch door's ensure, verbatim and for its reason: a daemon that IS answering needs
      // none, and a start already under way is the start this call would have made.
      workspaces.beginEnsureContainer(rowId);
    }

    if (!scheduleDelivery(rowId, text, compactFirst)) {
      return new Delivery(
          rowId,
          false,
          false,
          "a launch or a delivery is already waiting for this workspace; nothing was queued behind"
              + " it");
    }
    return switch (state) {
      case RUNNING ->
          new Delivery(
              rowId, true, false, "an agent is running in this workspace and will be told");
      case IDLE ->
          new Delivery(
              rowId,
              false,
              true,
              "no agent is running in this workspace; one is being launched with this text as its"
                  + " first turn");
      case UNREACHABLE ->
          new Delivery(
              rowId,
              false,
              false,
              "this workspace's container is not answering yet; it is being started and the text"
                  + " will be delivered, or launched with, once it does");
    };
  }

  /** The branch a dispatch would have fallen back to; see {@link #dispatchBranch}. */
  private static String dashShape(String requested) {
    int slash = requested.indexOf('/');
    if (slash <= 0) {
      return requested;
    }
    return requested.substring(0, slash) + "-" + requested.substring(slash + 1);
  }

  /**
   * The branch this dispatch can actually use.
   *
   * <p>{@code CaptureService.branchPrefix}'s defense, aimed at the requested name instead of a fixed
   * one: git's ref namespace is filesystem-like, so a repository holding {@code refs/heads/ticket}
   * can hold no {@code refs/heads/ticket/*} at all, and a create there fails on the push rather
   * than on anything this service could explain. Such a repository gets the dash shape — {@code
   * ticket/fix-login} becomes {@code ticket-fix-login} — which is the same workspace slug either
   * way, so nothing downstream can tell which shape it got.
   *
   * <p>Only the FIRST segment is flipped. A dispatch is one level deep in practice, and flipping
   * more would silently rename a branch the caller chose in a way it could not predict.
   */
  private String dispatchBranch(String repoId, String requested) {
    int slash = requested.indexOf('/');
    if (slash <= 0) {
      return requested;
    }
    String first = requested.substring(0, slash);
    return workspaces.branchExists(repoId, first)
        ? first + "-" + requested.substring(slash + 1)
        : requested;
  }

  /**
   * The ACTIVE workspace on this branch, by row id, read in a transaction of its own — the caller is
   * a REST method with no {@code @Transactional} of its own, and this class writes nothing here.
   */
  private Optional<Long> activeOn(String repoId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaceRepository
                    .findActiveByRepositoryAndBranch(repoId, branch)
                    .map(workspace -> workspace.id));
  }

  /**
   * Put a launch on a thread of our own, unless one is already waiting for this workspace.
   *
   * <p>The wait runs whether or not the daemon is up yet: a daemon that answers immediately makes it
   * one probe and a POST, and one that is still pulling an image makes it a poll. One path, so the
   * fresh dispatch and the re-dispatch differ in nothing but how long they take.
   */
  private void schedule(Long rowId, String instruction) {
    if (!pending.add(rowId)) {
      LOG.debugf("a launch is already waiting for workspace %s; not queueing a second", rowId);
      return;
    }
    try {
      launchExecutor.submit(
          () -> {
            try {
              awaitAndLaunch(rowId, instruction);
            } finally {
              pending.remove(rowId);
            }
          });
    } catch (RejectedExecutionException shuttingDown) {
      pending.remove(rowId);
      LOG.warnf("could not schedule an agent launch for workspace %s: shutting down", rowId);
    }
  }

  /** Poll until the daemon answers, then launch — or give up loudly when the window closes. */
  private void awaitAndLaunch(Long rowId, String instruction) {
    long deadline = System.currentTimeMillis() + launchWindowMs;
    while (true) {
      WorkspaceAgentLauncher.AgentState state = agentState(rowId);
      if (state == WorkspaceAgentLauncher.AgentState.RUNNING) {
        // Somebody won the race while we waited — a browser, or a second dispatch that got there
        // first. Their agent is the one working here.
        LOG.infof("workspace %s already has an agent running; not launching a second", rowId);
        return;
      }
      if (state == WorkspaceAgentLauncher.AgentState.IDLE) {
        break;
      }
      if (System.currentTimeMillis() >= deadline) {
        LOG.warnf(
            "workspace %s never got a daemon that answers within %s ms; no agent was launched",
            rowId, Long.valueOf(launchWindowMs));
        return;
      }
      try {
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException stopping) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (!launch(rowId, instruction)) {
      LOG.warnf("workspace %s's daemon refused the agent launch; nothing is working on it", rowId);
    }
  }

  /**
   * {@link #schedule}'s twin for a delivery, sharing its one-wait-per-workspace claim.
   *
   * <p>The set is shared deliberately rather than kept per verb: the delivery's own fallback arm is
   * a <em>launch</em>, so two waits on one workspace is exactly the "two agents on one checkout"
   * this set exists to stop, whichever door queued them. The cost is that a second delivery arriving
   * inside the milliseconds a first one takes against a live daemon is dropped rather than queued
   * behind it — stated in the answer, never silent.
   *
   * @return false when a wait was already claimed, which the caller reports
   */
  private boolean scheduleDelivery(Long rowId, String text, boolean compactFirst) {
    if (!pending.add(rowId)) {
      LOG.infof(
          "a wait is already claimed for workspace %s; not queueing a delivery behind it", rowId);
      return false;
    }
    try {
      launchExecutor.submit(
          () -> {
            try {
              awaitAndDeliver(rowId, text, compactFirst);
            } finally {
              pending.remove(rowId);
            }
          });
      return true;
    } catch (RejectedExecutionException shuttingDown) {
      pending.remove(rowId);
      LOG.warnf("could not schedule a delivery for workspace %s: shutting down", rowId);
      return false;
    }
  }

  /** What a compaction is asked for as — the slash command, exactly as a person would type it. */
  private static final String COMPACT_TURN = "/compact";

  /** Poll until the daemon answers, then speak to the agent or launch one — or give up loudly. */
  private void awaitAndDeliver(Long rowId, String text, boolean compactFirst) {
    long deadline = System.currentTimeMillis() + launchWindowMs;
    WorkspaceAgentLauncher.AgentState state;
    while (true) {
      state = agentState(rowId);
      if (state != WorkspaceAgentLauncher.AgentState.UNREACHABLE) {
        break;
      }
      if (System.currentTimeMillis() >= deadline) {
        LOG.warnf(
            "workspace %s never got a daemon that answers within %s ms; nothing was delivered to it",
            rowId, Long.valueOf(launchWindowMs));
        return;
      }
      try {
        Thread.sleep(pollIntervalMs);
      } catch (InterruptedException stopping) {
        Thread.currentThread().interrupt();
        return;
      }
    }

    if (state == WorkspaceAgentLauncher.AgentState.IDLE) {
      // Nobody to say it to, so it is said first instead: the launch path, with the caller's text
      // as the seed turn. No compaction here — there is nothing to compact in a session that is
      // about to begin.
      if (!launch(rowId, text)) {
        LOG.warnf("workspace %s's daemon refused the agent launch; nothing was delivered", rowId);
      }
      return;
    }

    if (compactFirst && compactBeforeTurn) {
      // Best effort and deliberately not a gate: a compaction that did not land is a longer
      // context, while refusing the turn over it would lose the phase prompt entirely.
      if (deliver(rowId, COMPACT_TURN) != WorkspaceAgentLauncher.DeliveryOutcome.DELIVERED) {
        LOG.warnf(
            "workspace %s did not take the %s turn; delivering the text anyway",
            rowId, COMPACT_TURN);
      }
    }

    WorkspaceAgentLauncher.DeliveryOutcome outcome = deliver(rowId, text);
    if (outcome == WorkspaceAgentLauncher.DeliveryOutcome.DELIVERED) {
      return;
    }
    // The session can end between the probe and the turn — a long wait for a container makes that
    // window minutes wide. A daemon that now says nothing is running gets the fallback arm rather
    // than a warning about a turn nobody could have heard.
    if (agentState(rowId) == WorkspaceAgentLauncher.AgentState.IDLE) {
      if (!launch(rowId, text)) {
        LOG.warnf(
            "workspace %s's agent went away and its daemon refused a launch; nothing was delivered",
            rowId);
      }
      return;
    }
    LOG.warnf("workspace %s's daemon would not take the turn; nothing was delivered to it", rowId);
  }

  /** The port's answer, or UNREACHABLE — an absent port and a broken one deserve the same one. */
  private WorkspaceAgentLauncher.AgentState agentState(Long rowId) {
    if (!agents.isResolvable()) {
      return WorkspaceAgentLauncher.AgentState.UNREACHABLE;
    }
    try {
      return agents.get().agentState(rowId);
    } catch (RuntimeException e) {
      LOG.debugf(e, "could not ask workspace %s's daemon about its agents", rowId);
      return WorkspaceAgentLauncher.AgentState.UNREACHABLE;
    }
  }

  /** The port's answer, or UNREACHABLE — an absent port cannot deliver and cannot be asked why. */
  private WorkspaceAgentLauncher.DeliveryOutcome deliver(Long rowId, String text) {
    if (!agents.isResolvable()) {
      return WorkspaceAgentLauncher.DeliveryOutcome.UNREACHABLE;
    }
    try {
      return agents.get().deliver(rowId, text);
    } catch (RuntimeException e) {
      LOG.debugf(e, "the delivery to workspace %s failed", rowId);
      return WorkspaceAgentLauncher.DeliveryOutcome.UNREACHABLE;
    }
  }

  private boolean launch(Long rowId, String instruction) {
    if (!agents.isResolvable()) {
      return false;
    }
    try {
      return agents.get().launch(rowId, instruction);
    } catch (RuntimeException e) {
      LOG.debugf(e, "the agent launch for workspace %s failed", rowId);
      return false;
    }
  }

  private String activeProcess(Long rowId) {
    if (!processes.isResolvable()) {
      return null;
    }
    try {
      return processes.get().activeFor(rowId).orElse(null);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
