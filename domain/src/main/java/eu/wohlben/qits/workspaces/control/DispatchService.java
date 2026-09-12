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
   * Workspaces with a launch already waiting. This is what keeps a polled door from queueing one
   * wait per press: the daemon's own RUNNING answer cannot cover the window between a launch being
   * scheduled and the agent's first session appearing, so the in-process fact has to.
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
   *     that
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
