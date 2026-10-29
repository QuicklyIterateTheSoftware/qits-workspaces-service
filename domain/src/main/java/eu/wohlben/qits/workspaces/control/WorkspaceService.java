package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import eu.wohlben.qits.workspaces.error.InternalServerErrorException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.TargetRefusals;
import eu.wohlben.qits.workspaces.events.WorkspaceFailed;
import eu.wohlben.qits.workspaces.events.WorkspaceStarted;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.dto.WorkItemWorkspaceDto;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceEvent;
import eu.wohlben.qits.workspaces.entity.WorkspaceEventType;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.gitmirror.GitMirrorException;
import eu.wohlben.qits.workspaces.gitmirror.MergeOutcome;
import eu.wohlben.qits.workspaces.gitmirror.MirrorWorktree;
import eu.wohlben.qits.workspaces.gitmirror.PushOutcome;
import eu.wohlben.qits.workspaces.gitmirror.PushSpec;
import eu.wohlben.qits.workspaces.gitmirror.RepoMirror;
import eu.wohlben.qits.workspaces.persistence.AgentWorktreeRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceEventRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.LegacyPlacementRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkspaceService {

  private static final Logger LOG = Logger.getLogger(WorkspaceService.class);

  @Inject RepositoryLookup repositories;

  @Inject WorkspaceRepository workspaceRepository;

  /** The rows a retired workspaces runner held, adopted by their first lease (epic qits-1481). */
  @Inject LegacyPlacementRepository legacyPlacements;

  @Inject WorkspaceEventRepository workspaceEventRepository;

  @Inject WorkspaceMetadataStore workspaceMetadata;

  /**
   * Fired when a workspace resolves, so other contexts can drop the rows that hang off it. The
   * delete is soft, so their FK cascade never fires; before the extraction this class reached
   * across and hard-deleted prompt drafts and attachments itself.
   */
  @Inject Event<WorkspaceResolved> workspaceResolvedEvent;

  @Inject ContainerRuntime containers;

  /**
   * Optional: where a per-container workspace token is minted and given back. Absent — no
   * implementation, or one with no issuer configured — means no token is minted and no container
   * carries one. See {@link CredentialCommissioner}.
   */
  @Inject Instance<CredentialCommissioner> commissioner;

  @Inject WorkspaceContainerEventPublisher containerEvents;

  /**
   * Optional: qits-system's leases, for the TARGET-placed rows (epic qits-1481). Absent reads as no
   * target can be had — see {@link TargetPlacement}. A DIRECT row never reaches it.
   */
  @Inject Instance<TargetPlacement> targetPlacement;

  /**
   * {@code WorkspaceStarted} and {@code WorkspaceFailed} for a DIRECT row, and {@code
   * WorkspaceFailed} for a TARGET row refused before its lease (qits-1236): once per transition,
   * after the write committed. Absent: nothing is announced.
   */
  @Inject Instance<LifecycleAnnouncer> lifecycle;

  /** What a lease's state does to its row, and the start processes a lease's launch settles. */
  @Inject TargetLeaseStates leaseStates;

  /**
   * The lease spec a TARGET row's container runs from (qits-851, epic qits-1481): composed by the
   * start, after the token is minted and before the lease is asked for.
   */
  @Inject RunnerWorkspaceSpecs leaseSpecs;

  /**
   * The edge address plane a TARGET row is composed from (qits-625): built by the TARGET start
   * before it asks for a lease, so a deployment with no public domain fails the row instead.
   */
  @Inject WorkspaceAddressPlanes addressPlanes;

  @Inject WorkspaceChangePublisher changePublisher;

  /**
   * Optional: the technical-process framework is a cross-context streaming primitive owned by the
   * application. Absent means the same work runs, unnarrated, with a null process id.
   */
  @Inject Instance<WorkspaceProcessTracker> processes;

  /**
   * The in-container workspace-daemon's liveness, observed alongside the docker reconciliation
   * ladder (docs/epics/qits-workspace-daemon/). {@code Instance<>} because apps without the backend
   * impl (cli, tests) have no {@link WorkspaceDaemonLiveness} bean — there it is simply empty.
   * <b>Part 1 is observational only</b>: {@link #ensureContainer} logs this signal but never
   * branches on it.
   */
  @Inject Instance<WorkspaceDaemonLiveness> clientLiveness;

  /**
   * What the slot's agents would lose if its container went away (qits-1263): the per-agent
   * cleanup check every destructive verb here asks before it tears a container down. It replaced
   * the slot-wide clean/dirty a {@code GitStatus} frame carried, which no daemon sends since
   * protocol 10.
   */
  @Inject AgentWorktreeGuard agentWorktreeGuard;

  /**
   * The in-container workspace-daemon's last-reported coding-agent activity rollup, surfaced as
   * {@link WorkspaceDto#agentActivity}. {@code Instance<>} for the same reason as {@link
   * #clientLiveness}: apps without the backend impl (cli, tests) have no {@link
   * WorkspaceAgentActivity} bean and simply see it empty. Only consulted for RUNNING workspaces —
   * the daemon reports only while connected.
   */
  @Inject Instance<WorkspaceAgentActivity> agentActivity;

  /**
   * The workspace registry's live view of each workspace's daemon — connected-since + the daemon
   * binary's build identity, surfaced as {@link WorkspaceDto#daemonConnectedAt}/{@code
   * daemonVersion}/{@code daemonBuildTime} (docs/epics/qits-workspace-registry/). {@code
   * Instance<>} for the same reason as {@link #clientLiveness}: apps without the backend impl
   * (cli, tests) have no {@link WorkspaceDaemonInfo} bean and simply see it empty. Only consulted
   * for RUNNING workspaces.
   */
  @Inject Instance<WorkspaceDaemonInfo> daemonInfo;

  /**
   * Notifies a target workspace's in-container daemon to pull an incoming merge/integration this
   * host just pushed to its branch (docs/epics/qits-workspace-daemon/ bidirectional auto-sync).
   * {@code Instance<>} for the same reason as {@link #clientLiveness}: apps without the backend
   * impl (cli, tests) have no {@link WorkspaceGitSync} bean and simply skip the notification — the
   * checkout then syncs on its next host git op, so nothing is lost.
   */
  @Inject Instance<WorkspaceGitSync> gitSync;

  /**
   * Awaits the in-container workspace-daemon's autonomous self-provision (clone + submodules on
   * boot) — the <b>sole</b> provisioning path (docs/epics/qits-workspace-daemon/ Part 2). {@code
   * Instance<>} because the real backend impl lives in {@code service}; apps without it (cli,
   * tests) supply a {@link WorkspaceDaemonProvisioner} test double that clones through the {@code
   * ContainerRuntime}. An empty {@code Instance<>} (or a daemon that never connects) fails the
   * provision — there is no host-driven fallback.
   */
  @Inject Instance<WorkspaceDaemonProvisioner> daemonProvisioner;

  /**
   * How long a fresh provision waits for a daemon to dial home before declaring the provision
   * FAILED. The daemon is the sole provisioner (there is no host-clone fallback), so this is the
   * stale-image discriminator: a modern image's daemon connects within ~a second, so a generous
   * default reliably distinguishes "no daemon here (rebuild the image)" from "slow daemon" — and
   * the former now fails loudly rather than degrading.
   */
  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long provisionConnectTimeoutMs;

  /** How long, once a daemon is live, to wait for its terminal Provisioned/ProvisionFailed. */
  @ConfigProperty(name = "qits.workspace.provision.timeout-ms", defaultValue = "600000")
  long provisionTimeoutMs;

  /**
   * Runs {@link #beginEnsureContainer}'s provision work off the request thread — the HTTP call
   * returns the technical-process id immediately and the browser watches the work over SSE.
   */
  private final ExecutorService processExecutor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-provision");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    processExecutor.shutdownNow();
  }

  /**
   * How a stop learns that a transaction it joined has committed: the ending of an overtaken start
   * waits for it (see {@link #afterCommit}).
   */
  @Inject TransactionSynchronizationRegistry transactions;

  /**
   * THE STOP MARKER (qits-1076): per DIRECT row, how many stops and container deletes this process
   * has seen. A start reads it before it registers ({@link DirectStart#epoch}) and asks again at
   * every point it would write the row or touch the container; a different count means a stop
   * landed while it ran, and the start yields — it removes nothing, marks nothing FAILED and never
   * turns the row RUNNING over a stop.
   *
   * <p>In memory, and deliberately not the row's own status. The stop sets it first, before its
   * transaction writes STOPPED, so the provision thread knows of the stop whether or not that
   * write has committed yet; a re-read of {@code runtimeStatus} could still see PROVISIONING and
   * tear the container down the person just paused. It is the same single-process assumption the
   * {@link TechnicalProcessRegistry} already makes: a start and the stop that overtakes it run in
   * the one service process that registered the start.
   */
  private final ConcurrentHashMap<Long, Long> stopEpochs = new ConcurrentHashMap<>();

  /**
   * The latest DIRECT start registered per row, with the stop count it began under. It answers the
   * two questions the stop marker alone cannot: whether the process an ensure would join is a start
   * that is still good (begun since the last stop), and whether a newer start already owns the
   * container an overtaken one would otherwise stop again.
   */
  private final ConcurrentHashMap<Long, DirectStart> directStarts = new ConcurrentHashMap<>();

  /**
   * Per-row monitors. A stop counts itself under its row's, and a start holds it across every
   * check-then-act against a stop: the runtime writes, the teardown of a failed provision and the
   * re-stop of an overtaken one. So a stop either lands before the check (and the start sees it) or
   * after the act (and its own STOPPED write is the last word). Per row, not global, because the
   * acts include qits-containers and IdP calls, which must not hold up every other workspace.
   */
  private final ConcurrentHashMap<Long, Object> rowGuards = new ConcurrentHashMap<>();

  /** What the stream of a start a stop overtook ends on, and what a 409 below says happened. */
  static final String STOPPED_DURING_START = "Workspace was stopped while it was starting.";

  /**
   * One DIRECT start: the process narrating it ({@code null} when unnarrated, as {@link
   * #ensureContainer(Long)} is) and the stop count it began under.
   */
  private record DirectStart(String processId, long epoch) {}

  /**
   * Thrown out of the ensure ladder when a stop overtook the start: not a failure of the start, so
   * nothing of it is recorded on the row. Its message is the line the process ends on.
   */
  static final class StoppedDuringStart extends RuntimeException {
    StoppedDuringStart(String workspaceId) {
      super(
          STOPPED_DURING_START
              + " The start of '"
              + workspaceId
              + "' was abandoned; its container is left stopped, not removed.");
    }
  }

  private Object guard(Long rowId) {
    return rowGuards.computeIfAbsent(rowId, id -> new Object());
  }

  private long stopEpoch(Long rowId) {
    return stopEpochs.getOrDefault(rowId, 0L);
  }

  private boolean stoppedSince(Long rowId, DirectStart start) {
    return stopEpoch(rowId) != start.epoch();
  }

  /**
   * Register a DIRECT start. The count is read <em>before</em> the process is begun, so a stop racing
   * the registration makes the start stale rather than the stop blind: the start then yields at its
   * first check, and the stop ends its process.
   */
  private DirectStart registerStart(Long rowId, Supplier<String> beginProcess) {
    long epoch;
    synchronized (guard(rowId)) {
      epoch = stopEpoch(rowId);
    }
    String processId = beginProcess.get();
    DirectStart start = new DirectStart(processId, epoch);
    synchronized (guard(rowId)) {
      directStarts.put(rowId, start);
    }
    return start;
  }

  /** Whether {@code processId} is the row's latest start and no stop has landed since it began. */
  private boolean isLiveStart(Long rowId, String processId) {
    DirectStart latest = directStarts.get(rowId);
    return latest != null
        && processId != null
        && processId.equals(latest.processId())
        && latest.epoch() == stopEpoch(rowId);
  }

  /** Whether the row has a start begun since its last stop — one that owns the container now. */
  private boolean hasStartSinceLastStop(Long rowId) {
    DirectStart latest = directStarts.get(rowId);
    return latest != null && latest.epoch() == stopEpoch(rowId);
  }

  /**
   * {@link #markRuntime}, unless a stop overtook {@code start}: the check and the write share the
   * row's guard, so a stop counted after the check commits its STOPPED after this write.
   *
   * @return false, having written nothing, when the start was overtaken
   */
  private boolean markRuntimeUnlessStopped(
      String repoId,
      String workspaceId,
      Long rowId,
      DirectStart start,
      WorkspaceRuntimeStatus status,
      String error) {
    synchronized (guard(rowId)) {
      if (stoppedSince(rowId, start)) {
        return false;
      }
      RuntimeMark mark =
          QuarkusTransaction.requiringNew()
              .call(() -> markRuntime(repoId, workspaceId, status, error));
      // Committed: announce a transition, not a re-stamp (the already-running short-circuit
      // writes RUNNING over RUNNING).
      if (mark != null && mark.was() != status) {
        announceRuntime(mark.rowId(), repoId, status, error);
      }
      return true;
    }
  }

  /** A DIRECT row's runtime write: the row it landed on and the status it replaced. */
  private record RuntimeMark(Long rowId, WorkspaceRuntimeStatus was) {}

  /** {@code WorkspaceStarted} for RUNNING, {@code WorkspaceFailed} for FAILED, nothing otherwise. */
  private void announceRuntime(
      Long rowId, String repoId, WorkspaceRuntimeStatus status, String error) {
    if (status == WorkspaceRuntimeStatus.RUNNING) {
      LifecycleAnnouncer.announce(
          lifecycle, new WorkspaceStarted(rowId.toString(), repoId, Instant.now()));
    } else if (status == WorkspaceRuntimeStatus.FAILED) {
      LifecycleAnnouncer.announce(
          lifecycle, new WorkspaceFailed(rowId.toString(), repoId, error, Instant.now()));
    }
  }

  /**
   * The start yields to the stop that overtook it, and lands where that stop would have left it:
   * the container stopped in place (never removed — it holds the person's {@code /workspace}) and
   * the row STOPPED with no runtime error. The re-stop covers a stop that landed before the start
   * ran or restarted the container, which therefore found nothing to stop; the STOPPED write
   * covers a stop whose transaction has not committed yet, and is the same value it will write.
   *
   * <p>Neither happens once a newer start was registered after the stop: that start owns the
   * container and the row now, and stopping either would undo the very start the person asked for
   * next.
   */
  private StoppedDuringStart yieldToStop(String repoId, String workspaceId, Long rowId) {
    synchronized (guard(rowId)) {
      if (!hasStartSinceLastStop(rowId)) {
        stopBeforeRemove(containers.containerName(workspaceId, repoId));
        QuarkusTransaction.requiringNew()
            .run(
                () ->
                    markRuntime(repoId, workspaceId, WorkspaceRuntimeStatus.STOPPED, null));
      }
    }
    LOG.infof(
        "Workspace %s/%s was stopped while it was starting; the start yielded and removed nothing",
        repoId, workspaceId);
    return new StoppedDuringStart(workspaceId);
  }

  /**
   * A stop or a container delete of a DIRECT row ends the start it overtakes (qits-1076). Before
   * this, neither touched the start's process: it stayed {@code activeFor} the row — through the
   * clone window, and through the bootstrap window that follows with the row already RUNNING — so
   * the next ensure-container joined a start that would never bring the container up, and answered
   * 200 STOPPED with nothing starting. Worse, a clone-window start then timed out ten minutes later
   * and removed the container the person had paused.
   *
   * <p>Counted first, ended after. The count is taken now, before the stop touches anything, so the
   * provision thread knows of the stop whatever it sees next. The ending waits for the stop's
   * transaction to commit — stop and delete run joined to whatever transaction is open — so that
   * once the process is gone, STOPPED is what an ensure reads: the process is not ended under a row
   * that still says PROVISIONING. Ending it is two things: the pending provision await is released
   * with a stopped outcome, so the provision thread returns now rather than at its timeout, and the
   * process is ended as failed with the reason in its stream.
   *
   * <p>A start registered after the stop is spared both. It began under the new count, and it is
   * what the person asked for next.
   */
  private void interruptStart(String repoId, String workspaceId, Long rowId) {
    synchronized (guard(rowId)) {
      stopEpochs.merge(rowId, 1L, Long::sum);
    }
    afterCommit(
        () -> {
          synchronized (guard(rowId)) {
            if (daemonProvisioner.isResolvable() && !hasStartSinceLastStop(rowId)) {
              daemonProvisioner.get().abandonProvision(rowId, STOPPED_DURING_START);
            }
            if (processes.isResolvable()) {
              WorkspaceProcessTracker tracker = processes.get();
              tracker
                  .activeFor(repoId, workspaceId)
                  .filter(processId -> !isLiveStart(rowId, processId))
                  .ifPresent(
                      processId ->
                          tracker.end(
                              processId,
                              STOPPED_DURING_START
                                  + " The start was abandoned; start the workspace again to"
                                  + " bring its container up."));
            }
          }
        });
  }

  /**
   * {@code work} once the caller's transaction has committed, or now when there is none. A rolled-back stop ends nothing; the start it would have ended
   * then finds the count moved, yields, and stops the container itself.
   */
  private void afterCommit(Runnable work) {
    int status;
    try {
      status = transactions.getTransactionStatus();
    } catch (RuntimeException noContext) {
      status = Status.STATUS_NO_TRANSACTION;
    }
    if (status != Status.STATUS_ACTIVE) {
      work.run();
      return;
    }
    transactions.registerInterposedSynchronization(
        new Synchronization() {
          @Override
          public void beforeCompletion() {}

          @Override
          public void afterCompletion(int outcome) {
            if (outcome == Status.STATUS_COMMITTED) {
              try {
                work.run();
              } catch (RuntimeException e) {
                LOG.warnf(e, "Ending the start a stop overtook failed");
              }
            }
          }
        });
  }

  /**
   * The git substrate: a mirror per repository, the worktrees a merge runs in, and the pushes that
   * are now the only way a ref of a served repository moves.
   *
   * <p>Nothing in this class opens the shared volume of bare origins any more. Every branch create,
   * every branch delete and every merge used to write a ref there directly, and every one of them
   * fired no {@code post-receive} — so a workspace created, integrated or cleaned up produced no CI
   * run and no event. They are pushes now, and the chain downstream happens for the ordinary reason.
   */
  @Inject GitMirrorRegistry mirrors;

  @Inject GitIdentity gitIdentity;

  /**
   * The agent rows (qits-1152): a branch an ACTIVE agent holds as its {@code wrapperBranch} is never
   * cleaned up (qits-1232), although {@link #findWorkspaceByBranch} never sees it.
   */
  @Inject AgentWorktreeRepository agentWorktrees;

  /**
   * Creates {@code branch} from {@code parentBranch} — <b>as a push</b>, through the git host.
   *
   * <p>This was {@code git branch} in the bare origin on the shared volume, and it is the plainest
   * example of what that cost: a filesystem ref update fires no {@code post-receive}, so <b>no
   * workspace anyone has ever created produced a CI run</b>. A push does, because it is a push.
   *
   * <p>An existing ref is a <em>client</em> error, not a server one: this is the normal-path guard
   * for "each workspace gets its own branch", and asking for a branch that is already there is a
   * 409 the caller can act on — a typo'd "branch off" name, or a branch created outside qits that
   * the caller meant to adopt. It is checked up front rather than inferred from the push's refusal,
   * which cannot distinguish "ref exists" from a genuinely unreachable host; the latter still 500s.
   */
  private void createBranchOnHost(RepoMirror mirror, String branch, String parentBranch) {
    if (mirror.remoteHasBranch(branch)) {
      throw new ConflictException("Branch already exists: " + branch);
    }
    mirror.refreshNow();
    PushOutcome pushed;
    try {
      pushed = mirror.createBranch(branch, parentBranch);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Failed to create branch: " + e.getMessage());
    }
    if (!pushed.accepted()) {
      throw new InternalServerErrorException("Failed to create branch: " + pushed.output());
    }
  }

  /**
   * Materializes a workspace's container from its durable branch ref: runs the container and clones
   * {@code branch} into its {@code /workspace} (the commit identity arrives as container-level
   * {@code GIT_*} env via {@link WorkspaceContainerFactory}, so nothing is configured in the
   * clone). Removes the container again if the clone fails, so a retry can succeed. The branch ref
   * must already exist in the origin — this is the on-demand half of workspace creation, invoked
   * lazily by {@link #ensureContainer} for never-provisioned and pruned workspaces alike.
   *
   * <p>The clone is <b>autonomous and daemon-only</b>: the in-container workspace-daemon clones
   * {@code /workspace} and materializes submodules from its own boot-time env, then reports the
   * outcome up the control socket; qits only {@link #awaitDaemonProvision awaits} it (streaming the
   * daemon's output into the {@code clone} segment) and drives no git step. There is <b>no
   * host-driven fallback</b> (docs/epics/qits-workspace-daemon/ Part 2): a container with no live
   * daemon — a stale, pre-daemon image — fails to provision (rm + FAILED) rather than degrading.
   *
   * <p>A non-null {@code process} receives the provision as streamed segments: {@code container}
   * (the orchestrator putting the container at its place) and {@code clone} (the clone plus the
   * daemon's submodule materialization) — lines arrive live from the daemon over the socket, and
   * each segment settles when its step completes. A failure leaves the open segment for the caller
   * to settle {@code failed}.
   *
   * <p>Submodules resolve <b>natively</b> in the daemon's bounded {@code .gitmodules} walk: with
   * the repository addressed by its project-scoped name, a project's repos are siblings and a
   * committed relative submodule url ({@code ../<name>.git}) resolves against the origin to a
   * served sibling with no override (an absolute committed url is redirected explicitly). The
   * daemon sources the walk from the checkout's own {@code .gitmodules} (it has no DB), skipping
   * any submodule it can't resolve — so there is no import-scoping (an accepted trade-off; see
   * {@code Provisioner.materializeSubmodules}).
   *
   * <p><b>A stop is not a failed provision</b> (qits-1076). When {@code start} was overtaken by a
   * stop or a container delete, whatever the await answers — the stop's own release, a late
   * outcome from the daemon, the timeout — the container is <em>not</em> removed and its credential
   * not given back: the person paused this workspace, and the container holds its {@code
   * /workspace}. The check and the teardown share the row's guard, so a stop either lands before the
   * check and is seen, or after the teardown and finds a container already gone. The same check
   * runs before the container is run at all, so a stop that landed during the commission does not
   * cost a launch.
   */
  private void provisionContainer(
      String repoId,
      String workspaceId,
      Long rowId,
      String branch,
      String parentBranch,
      String entityId,
      WorkspaceProcessTracker.Handle process,
      DirectStart start) {
    // A fresh container gets a fresh credential, and it is minted BEFORE anything is started: a
    // failed mint — or a refusal for want of an edge plane — must cost a launch that has not
    // happened yet, never leave a container running with no identity. This is also the one place a recreate is covered — recreate rm's the
    // container and comes back through here — so no second seam has to remember.
    commissionFor(repoId, workspaceId, rowId);
    if (stoppedSince(rowId, start)) {
      throw yieldToStop(repoId, workspaceId, rowId);
    }
    if (process != null) {
      process.openSegment("container");
    }
    Consumer<String> runLines =
        process == null ? null : line -> process.appendLine("container", line);
    String container =
        containers.run(repoId, workspaceId, rowId, branch, parentBranch, entityId, runLines);
    if (process != null) {
      process.settleSegment("container", true);
      process.openSegment("clone");
    }
    Consumer<String> cloneLines =
        process == null ? null : line -> process.appendLine("clone", line);

    // Provisioning is the daemon's job (docs/epics/qits-workspace-daemon/ Part 2): the in-container
    // workspace-daemon clones /workspace and materializes submodules from its own boot-time env,
    // then
    // reports Provisioned/ProvisionFailed over the control socket. qits only AWAITS that outcome,
    // feeding the clone segment from the daemon's streamed output — it drives no git step and no
    // longer falls back to a host-driven clone. A container with no live daemon (a stale,
    // pre-daemon
    // image) therefore cannot be provisioned: it fails loudly (rm + FAILED), recoverable by
    // rebuilding the image so the daemon is present.
    ProvisionResult outcome = awaitDaemonProvision(rowId, cloneLines, start);
    if (!outcome.ok()) {
      synchronized (guard(rowId)) {
        if (stoppedSince(rowId, start)) {
          // Overtaken: the outcome is the stop's release or arrived after it. Either way it says
          // nothing about the container a person deliberately paused, so nothing is torn down.
          throw yieldToStop(repoId, workspaceId, rowId);
        }
        containers.rm(container);
        // The container this credential was minted for is gone again, so it goes back — the same
        // rule every teardown seam follows, applied to the teardown a failed provision is.
        decommissionFor(rowId);
      }
      throw new InternalServerErrorException(
          "workspace-daemon self-provision failed: " + outcome.message());
    }
    if (process != null) {
      process.settleSegment("clone", true);
    }
  }

  /**
   * Await the in-container daemon's autonomous self-provision. The daemon is the <b>sole</b>
   * provisioner (docs/epics/qits-workspace-daemon/ Part 2) — there is no host-driven fallback — so
   * a missing provisioner bean or a daemon that never dials home within the connect window is a
   * provision FAILURE, not a degradation. In apps without the backend impl (cli, tests with no real
   * container), a {@link WorkspaceDaemonProvisioner} test double stands in for the daemon and
   * clones the checkout through the {@code ContainerRuntime}.
   */
  private ProvisionResult awaitDaemonProvision(
      Long rowId, Consumer<String> onLine, DirectStart start) {
    if (!daemonProvisioner.isResolvable()) {
      return ProvisionResult.failed("no workspace-daemon provisioner is available");
    }
    // The await can be cut short by a stop (qits-1076): the stop releases it, and the question
    // passed here covers the stop that landed before the await's slot existed.
    return daemonProvisioner
        .get()
        .awaitProvision(
            rowId,
            Duration.ofMillis(provisionConnectTimeoutMs),
            Duration.ofMillis(provisionTimeoutMs),
            onLine,
            () -> stoppedSince(rowId, start))
        .orElseGet(
            () ->
                ProvisionResult.failed(
                    "no workspace-daemon dialed home within "
                        + provisionConnectTimeoutMs
                        + "ms — is the container running an image with the daemon?"));
  }

  /**
   * Mint the workspace token this workspace's next container will carry, and put it on the row so
   * every later ensure composes the same container spec.
   *
   * <p><b>It fails the provision.</b> A workspace launched with no identity would pull and push as
   * nobody, and the failure would only surface much later as a refused registry read. The launch
   * already surfaces ensure failures, so throwing here reports it where it happened — before {@code
   * containers.run}, so a refused or failed mint leaves no container, and {@link #ensureContainer}
   * marks the row FAILED with the reason. The implementation is patient first — see {@code
   * wiring/IdpCredentialCommissioner} — so what reaches this point is an issuer that stayed
   * unreachable, not a redeploy window.
   *
   * <p><b>The credential is a workspace token, and it needs the edge</b> (qits-1084). Only admin and
   * editor rows provision here, and they stay DIRECT for good; their container is minted the same
   * non-expiring {@code qits_tok_} a TARGET row holds — kind {@code workspace-admin} for an admin row,
   * {@code workspace} for the editor — stored on the row's token columns, and the factory addresses
   * it through the edge plane ({@link WorkspaceAddressPlanes#plane}), because a token only works
   * there. <b>With no edge plane — no public {@code QITS_DOMAIN} — and an issuer wired, the
   * provision is refused</b> ({@link #refuseWithoutEdge}): there is no credential such a container
   * could present, and launching it with none would hide a misconfiguration behind a workspace that
   * cannot reach its registry. With no issuer wired there is nothing to refuse, and the container
   * launches with no credential, as it does on every deployment with no idp.
   *
   * <p>Any token already on the row is given back first. That is the recreate case: the container it
   * belonged to has just been removed, and a row carries one at most.
   *
   * <p>Its own transactions, and not the caller's: {@link #provisionContainer} runs outside one (each
   * status transition commits separately), and the token must be committed before {@code
   * containers.run} asks the factory to read it back.
   *
   * <p><b>The mint says which project the token is for</b>, resolved from the repository the
   * workspace branches. That is the whole of per-context scoping on this side: the issuer turns it
   * into a {@code project} claim, and a resource service then judges this container's token on the
   * project rather than on its platform role alone. The project the container is told about ({@code
   * QITS_WORKSPACE_DAEMON_PROJECT_ID}, put there by {@link WorkspaceContainerFactory}) and the one
   * the token is scoped to are deliberately the same fact from the same registry.
   */
  private void commissionFor(String repoId, String workspaceId, Long rowId) {
    if (!commissioner.isResolvable()) {
      return;
    }
    decommissionFor(rowId);
    // AN ADMIN ROW MINTS ITS OWN KIND (qits-628 follow-up): workspace-admin, which qits-idp issues
    // qits:agent and qits:admin-agent — the container holds the host's docker socket, so its agent
    // is admitted wherever qits:admin is. Every other row stays `workspace`. The kind is the
    // issuer's record and never the container's environment, so the spec is unchanged by it.
    boolean admin =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveById(rowId).map(wt -> wt.admin).orElse(false));
    // The edge plane, BEFORE anything is minted: the token is only worth minting for a container
    // that can present it. No plane is refused when an issuer is wired, and nothing at all when
    // none is — asked of the issuer's configuration, so the refusal costs no call.
    try {
      addressPlanes.plane();
    } catch (EdgePlaneUnconfigured noEdge) {
      if (commissioner.get().isWired()) {
        throw refuseWithoutEdge(rowId, noEdge);
      }
      return;
    }
    RepositoryLookup.RepositoryView repository = repositoryOf(repoId);
    // The Git refs this container may push: none. The workspace credential never pushes — the
    // daemon pushes with each agent's own token, whose refs are the agent's (qits-1152) — so the
    // mint states an empty list rather than none, which would be unscoped.
    List<String> statedRefs = List.of();
    // A mint that fails throws out of here and fails the provision.
    Optional<WorkspaceToken> minted = mintToken(rowId, repository, statedRefs, admin);
    if (minted.isEmpty()) {
      // No issuer wired. Supported, and the same as no implementation at all.
      return;
    }
    WorkspaceToken token = minted.get();
    if (!storeToken(rowId, token)) {
      // A second provision stored its token first, or the row resolved meanwhile: this one is
      // nobody's.
      deleteToken(token.tokenId());
      return;
    }
    LOG.debugf(
        "Minted %s workspace token %s for DIRECT workspace %s/%s",
        CredentialCommissioner.contextKindFor(admin), token.tokenId(), repoId, workspaceId);
  }

  /**
   * The refusal of a DIRECT provision on a deployment with an issuer and no edge plane: 503 {@code
   * EDGE_PLANE_UNCONFIGURED}, the code a TARGET row refused for the same reason carries. A 503 and
   * not a 4xx because nothing the caller sends could change it — it is this deployment that lacks a
   * public {@code QITS_DOMAIN}, and the answer says so. Thrown before anything is minted or run, so
   * the row holds no credential and there is no container; {@link #ensureContainer} records the
   * message as the row's FAILED runtime error.
   */
  private static DomainException refuseWithoutEdge(Long rowId, EdgePlaneUnconfigured noEdge) {
    LOG.warnf("Refused to provision DIRECT workspace %s: %s", rowId, noEdge.getMessage());
    return new DomainException(
        503,
        EdgePlaneUnconfigured.CODE,
        noEdge.getMessage()
            + ". A DIRECT workspace's container holds a workspace token, which only works through"
            + " the public edge: set QITS_DOMAIN to the platform's public domain to provision it.");
  }

  /**
   * The mint both token paths make — a TARGET start ({@link #mintLeaseToken}) and a DIRECT
   * provision on an edge plane ({@link #commissionFor}): the row's project claim, the Git refs the
   * caller states as {@code statedRefs}, and the kind {@code admin} picks.
   */
  private Optional<WorkspaceToken> mintToken(
      Long rowId,
      RepositoryLookup.RepositoryView repository,
      List<String> statedRefs,
      boolean admin) {
    return commissioner.get().commissionToken(rowId, projectOf(repository), statedRefs, admin);
  }

  /**
   * Store a freshly minted {@code token} on the row's three token columns ({@code V13}), unless the
   * row already holds one or is no longer ACTIVE — answering whether it stored it, so the caller
   * deletes a token nobody holds. Shared by both token paths.
   */
  private boolean storeToken(Long rowId, WorkspaceToken token) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaceRepository
                    .findActiveById(rowId)
                    .map(
                        wt -> {
                          if (wt.holdsToken()) {
                            return false;
                          }
                          wt.commissionedTokenId = token.tokenId();
                          wt.commissionedTokenSubject = token.subject();
                          wt.commissionedToken = token.token();
                          return true;
                        })
                    .orElse(false));
  }

  /**
   * The project a repository belongs to, or null when the registry cannot say.
   *
   * <p><b>Null is not a failure here.</b> Every caller is a launch, and the registry answering
   * nothing is a moment rather than a verdict — the same reading {@link WorkspaceContainerFactory}
   * already takes for the label and the daemon's env, where a project it cannot resolve costs a
   * label and never a workspace. So an unresolved project costs the credential its scope: it is
   * commissioned exactly as workspace credentials were before scoping existed, which is wider than
   * intended and still narrower than not starting.
   */
  private static String projectOf(RepositoryLookup.RepositoryView repository) {
    if (repository == null || repository.projectId() == null || repository.projectId().isBlank()) {
      return null;
    }
    return repository.projectId();
  }

  /**
   * The registry's view of a repository for a commission, or null when it cannot say — one lookup
   * for both the project scope and the default branch. Null for the reason {@link #projectOf}
   * gives.
   */
  private RepositoryLookup.RepositoryView repositoryOf(String repoId) {
    if (repoId == null || repoId.isBlank()) {
      return null;
    }
    try {
      return repositories.find(repoId).orElse(null);
    } catch (RuntimeException registryDidNotAnswer) {
      LOG.debugf(
          "Could not resolve repository %s to scope its workspace credential: %s",
          repoId, registryDidNotAnswer.toString());
      return null;
    }
  }

  /**
   * Give back the workspace token a workspace's container held, and clear the row.
   *
   * <p><b>Best-effort, and never in the way of a teardown.</b> Every caller runs after something
   * irreversible — a container removed, a branch deleted — so a deletion that fails is logged and
   * the teardown continues. What that leaves behind is a token nothing can use to reach a container
   * that no longer exists, and the reconcile reaps it within the hour.
   *
   * <p>The row is cleared even when the deletion fails, and that order is deliberate: the token id
   * is on the row so a teardown can find it, and a row still naming a token this service has
   * stopped tracking would make the reconcile spare an orphan forever.
   *
   * <p>Callers on a transactional path (the resolution verbs, {@code deleteContainer}) hold the
   * managed row already, so the clear rides their transaction; the row write here is for the paths
   * that do not.
   */
  private void decommissionFor(Long rowId) {
    if (!commissioner.isResolvable() || rowId == null) {
      return;
    }
    // A TARGET row's token (qits-625) and a DIRECT row's on an edge plane (qits-1084) alike.
    String held =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findByIdOptional(rowId)
                        .map(WorkspaceService::clearToken)
                        .orElse(null));
    deleteToken(held);
  }

  /**
   * Empties a row's three token columns (qits-625), in the caller's transaction, and answers the
   * token id they held so the caller can delete it once that transaction is out of the way.
   */
  private static String clearToken(Workspace wt) {
    String held = wt.commissionedTokenId;
    wt.commissionedTokenId = null;
    wt.commissionedTokenSubject = null;
    wt.commissionedToken = null;
    return held;
  }

  /**
   * The workspace token's deletion, for callers that already cleared the row. Best-effort, for
   * {@link #decommissionFor}'s reason; null or blank is a row that held none and is silent.
   */
  private void deleteToken(String tokenId) {
    if (!commissioner.isResolvable() || tokenId == null || tokenId.isBlank()) {
      return;
    }
    try {
      commissioner.get().deleteToken(tokenId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not delete workspace token %s; the reconcile will reap it: %s",
          tokenId, e.toString());
    }
  }

  /** Appends a history event to a workspace's timeline. */
  private void recordEvent(
      Workspace workspace, WorkspaceEventType type, String branch, String target, String commit) {
    workspaceEventRepository.persist(
        WorkspaceEvent.builder()
            .workspace(workspace)
            .type(type)
            .branch(branch)
            .parent(workspace.parent)
            .target(target)
            .commit(commit)
            .at(Instant.now())
            .build());
  }

  public List<WorkspaceDto> listWorkspaces(String repoId) {
    // The existence check and the default branch are one answer: this call already had to be made,
    // and mainBranch rides on it. Carrying it into every row is what lets a client tell "Integrate"
    // from "Release" — only a release may write the default branch — without asking qits-projects
    // for one string.
    RepositoryLookup.RepositoryView repository = repositories.require(repoId);

    // One refresh for the whole listing, and a failure only costs the ahead/behind numbers: the
    // browser polls this route, so a git host that is briefly away must not 500 the page it is on.
    RepoMirror mirror = mirrors.of(repoId);
    refreshQuietly(mirror);
    // The branch tree shows only live workspaces; resolved ones live in the history view.
    List<Workspace> rows = workspaceRepository.findActiveByRepositoryId(repoId);
    // Live container set (one listing call to the orchestrator), so RUNNING stays accurate even
    // when container state changed out-of-band; the persisted column carries the
    // STOPPED/PROVISIONING/FAILED signal otherwise. Asked only when a row here is on the direct
    // path at all (qits-780: in practice an admin one) — a repository of TARGET rows, which is every
    // regular workspace, costs qits-containers nothing.
    Set<String> runningIds =
        rows.stream().noneMatch(wt -> wt.placement == WorkspacePlacement.DIRECT)
            ? Set.of()
            : containers.listWorkspaceContainers(repoId).stream()
                // ps -a lists stopped containers too; only genuinely-running ones count as RUNNING
                // (a deliberately stopped or Exited container is present but must read as STOPPED).
                .filter(ContainerRuntime.ContainerInfo::running)
                .map(ContainerRuntime.ContainerInfo::workspaceId)
                .collect(Collectors.toSet());
    // The newest daemon build connected anywhere is the registry-only "latest agent version"
    // (docs/epics/qits-workspace-registry/): a RUNNING workspace whose build is strictly older is
    // flagged daemonOutdated so the UI can offer a Recreate. Computed once per list, across all
    // repos' live daemons — the notion is registry-wide, not per-repository.
    WorkspaceDaemonInfo.Info latestDaemon =
        daemonInfo.isResolvable() ? latestDaemon(daemonInfo.get().all()) : null;
    return rows.stream()
        .map(
            wt -> {
              String branch = wt.branch;
              AheadBehind ab = aheadBehind(mirror, wt.parent, branch);
              // Only diverged branches (both ahead and behind) can't fast-forward and so risk a
              // conflict; everything else integrates cleanly, so skip the extra merge-tree probe.
              boolean conflicts =
                  ab.ahead() != null
                      && ab.behind() != null
                      && ab.ahead() > 0
                      && ab.behind() > 0
                      && wouldConflict(mirror, wt.parent, branch);
              // A TARGET row's container is not qits-containers', so that listing says nothing
              // about it: its persisted status is its lease's word (TargetLeaseStates).
              WorkspaceRuntimeStatus runtime =
                  wt.placement == WorkspacePlacement.TARGET
                      ? wt.runtimeStatus
                      : directRuntime(wt, runningIds);
              // Clean/dirty, agent activity and the registry facts below are known only while the
              // daemon's socket is live, which is RUNNING. The registry ports are self-evicting
              // (empty once the socket drops).
              boolean daemonReadable = runtime == WorkspaceRuntimeStatus.RUNNING;
              // Clean/dirty is always null (no badge): it was the slot-wide GitStatus frame, which
              // no daemon sends since protocol 10 (qits-1152). The field stays in the view for its
              // consumers; what a slot would lose is now asked per agent, on demand, by the
              // destructive verbs (AgentWorktreeGuard) — one daemon call per agent, too dear for a
              // listing (qits-1263).
              Boolean clean = null;
              // Agent activity is known only while the daemon's socket is live, and self-heals.
              AgentActivityState activity =
                  daemonReadable && agentActivity.isResolvable()
                      ? agentActivity.get().activityFor(wt.id).orElse(null)
                      : null;
              // Registry facts (connected-since + daemon build identity) share agent activity's
              // daemon-readable, in-memory contract: known only while the daemon's socket is live.
              WorkspaceDaemonInfo.Info info =
                  daemonReadable && daemonInfo.isResolvable()
                      ? daemonInfo.get().lookup(wt.id).orElse(null)
                      : null;
              return new WorkspaceDto(
                  wt.id,
                  wt.workspaceId,
                  wt.parent,
                  branch,
                  repository.mainBranch(),
                  ab.ahead(),
                  ab.behind(),
                  conflicts,
                  wt.status,
                  runtime,
                  wt.runtimeError,
                  clean,
                  activity,
                  wt.preamble,
                  wt.ticketId,
                  wt.epicId,
                  wt.result,
                  wt.createdAt,
                  wt.resolvedAt,
                  info != null ? info.connectedAt() : null,
                  info != null ? info.version() : null,
                  info != null ? info.buildTime() : null,
                  daemonOutdated(info, latestDaemon),
                  wt.admin,
                  wt.placement,
                  wt.leaseId,
                  wt.targetId,
                  wt.queuedAt,
                  null);
            })
        .toList();
  }

  /**
   * Every open workspace bound to a work item, oldest first (qits-112): ACTIVE only, so integrated
   * and abandoned ones are left out and a work item shows at most one here.
   */
  public List<WorkItemWorkspaceDto> openWorkspaces() {
    return workspaceRepository.findOpenBoundToWork().stream().map(WorkspaceService::workItemView).toList();
  }

  /**
   * Every workspace of one work item, whatever its status, newest first (qits-112). {@code workRef}
   * is the item's entity id or its qualified id; see {@code WorkspaceRepository.findByWorkRef}.
   */
  public List<WorkItemWorkspaceDto> workItemWorkspaces(String workRef) {
    return workspaceRepository.findByWorkRef(workRef).stream()
        .map(WorkspaceService::workItemView)
        .toList();
  }

  private static WorkItemWorkspaceDto workItemView(Workspace w) {
    return new WorkItemWorkspaceDto(
        w.id,
        w.workId,
        w.entityId,
        w.repositoryId,
        w.workspaceId,
        w.branch,
        w.status == null ? null : w.status.name(),
        w.runtimeStatus == null ? null : w.runtimeStatus.name(),
        w.createdAt,
        w.resolvedAt);
  }

  /**
   * Orders live daemon connections by build recency: build time first (the {@code -SNAPSHOT}
   * tiebreaker the registry epic is built on), then version as a stable last resort. Entries with
   * no reported build time are filtered out by {@link #latestDaemon} before this ever sees them.
   */
  private static final Comparator<WorkspaceDaemonInfo.Info> DAEMON_BUILD_ORDER =
      Comparator.comparing(WorkspaceDaemonInfo.Info::buildTime)
          .thenComparing(i -> i.version() == null ? "" : i.version());

  /**
   * The newest daemon build among live connections, or {@code null} when none reports a build time
   * (older images) — an unknowable build can't be "the latest", so it never wins.
   */
  private static WorkspaceDaemonInfo.Info latestDaemon(Collection<WorkspaceDaemonInfo.Info> all) {
    return all.stream().filter(i -> i.buildTime() != null).max(DAEMON_BUILD_ORDER).orElse(null);
  }

  /**
   * Whether {@code info}'s daemon build is strictly older than {@code latest} — {@code true} only
   * when both build times are known and this one precedes the newest. Returns {@code null} (not
   * {@code false}) whenever the two can't be compared (no live daemon, no reported build time on
   * either side, or no newer build exists), so an uncomparable or up-to-date workspace shows no
   * warning rather than a misleading verdict.
   */
  private static Boolean daemonOutdated(
      WorkspaceDaemonInfo.Info info, WorkspaceDaemonInfo.Info latest) {
    if (info == null || info.buildTime() == null || latest == null || latest.buildTime() == null) {
      return null;
    }
    return info.buildTime().isBefore(latest.buildTime()) ? Boolean.TRUE : null;
  }

  /**
   * The ids of the live (not REMOVED) agents placed in this workspace row, oldest first: the agents
   * a container verb affects, read before the container goes (qits-1745). Empty for an unknown row.
   */
  public List<String> liveAgentIds(Long workspaceRowId) {
    if (workspaceRowId == null) {
      return List.of();
    }
    return QuarkusTransaction.joiningExisting()
        .call(() -> agentWorktrees.findLiveByWorkspace(workspaceRowId))
        .stream()
        .map(a -> a.id.toString())
        .toList();
  }

  /** A single active workspace's current DTO (runtime status computed live), or 404. */
  public WorkspaceDto getWorkspace(Long id) {
    Workspace workspace = requireActive(id);
    return listWorkspaces(workspace.repositoryId).stream()
        .filter(w -> id.equals(w.id()))
        .findFirst()
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
  }

  /**
   * A DIRECT row's runtime status as listed: RUNNING when qits-containers' listing has it running,
   * a stale persisted RUNNING read as STOPPED, otherwise the persisted status. Behind the router's
   * refusal, like every other DIRECT read (qits-780).
   */
  private static WorkspaceRuntimeStatus directRuntime(Workspace wt, Set<String> runningIds) {
    WorkspacePlacements.requireDirectAllowed(wt);
    return runningIds.contains(wt.workspaceId)
        ? WorkspaceRuntimeStatus.RUNNING
        : wt.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
            ? WorkspaceRuntimeStatus.STOPPED
            : wt.runtimeStatus;
  }

  /**
   * The ACTIVE workspace with this id, or 404. One lookup: the id is the identity, so no repository
   * is needed to select the row — the repository is read <em>off</em> it, by the callers that build
   * container names and on-disk paths from the label.
   */
  private Workspace requireActive(Long id) {
    if (id == null) {
      throw new NotFoundException("Workspace not found: null");
    }
    return workspaceRepository
        .findActiveById(id)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + id));
  }

  /**
   * Whether {@code branch} — workspace-backed or plain — can be removed with no data loss: it is
   * not its own parent (the main branch can't be cleaned up), has no unmerged commits ({@code ahead
   * == 0} against its parent), a clean working tree when workspace-backed, no other workspace
   * forks from it, and it is not an ACTIVE agent's wrapper branch (qits-1232). A plain branch's
   * parent is the repository's main branch; a workspace's is its fork point. This is the single
   * criterion the UI, the cleanup endpoint and post-integrate cleanup all use.
   */
  public boolean canCleanupBranch(String repoId, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return false;
    }
    if (hasActiveAgentWrapperBranch(repoId, branch)) {
      return false;
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    // No usable parent, or the branch *is* its parent (e.g. main): nothing to merge into, never
    // safe.
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return false;
    }
    RepoMirror mirror = mirrors.of(repoId);
    // Forced, not the freshness window: this decides whether a branch is deleted, and an
    // ahead/behind computed against a mirror that missed the last push would delete unmerged work.
    // A refresh that fails leaves the counts UNKNOWN, and unknown refuses — which is the direction
    // this question has to fail in.
    refreshNowQuietly(mirror);
    // ahead == null means git couldn't compare; ahead > 0 means commits not yet in the parent.
    Integer ahead = aheadBehind(mirror, parent, branch).ahead();
    if (ahead == null || ahead != 0) {
      return false;
    }
    if (wt != null) {
      // The agents' worktrees live in the container; uncommitted changes or unpushed commits
      // there (which the origin-side ahead/behind above cannot see) both mean cleanup could
      // destroy work.
      if (!isWorkspaceClean(wt)) {
        return false;
      }
    }
    return !hasChildren(repoId, branch);
  }

  /**
   * Whether {@code branch} holds nothing {@code mainBranch} lacks (qits-1605): the closed item's
   * agent is OBSOLETE once this is true, though no release consumed its wrapper branch. True when
   * the git host has no such branch, or when the tip the git host holds (asked with {@code
   * ls-remote}, never the mirror) is reachable from the mirror's {@code mainBranch} after a forced
   * refresh — the {@code ahead == 0} that {@link #canCleanupBranch} judges by, read against the
   * repository of record. That method itself cannot answer this: it refuses every ACTIVE agent's
   * wrapper branch, which is exactly the branch asked about here.
   *
   * <p>Empty whenever it cannot tell: a git host or mirror that does not answer, a tip the mirror
   * has not fetched, a default branch it cannot resolve. The caller leaves the agent alone then,
   * and a stale mirror can only make this answer false or empty, never wrongly true.
   */
  public Optional<Boolean> branchHoldsNothingBeyond(
      String repoId, String branch, String mainBranch) {
    if (branch == null
        || branch.isBlank()
        || branch.startsWith("-")
        || mainBranch == null
        || mainBranch.isBlank()
        || mainBranch.startsWith("-")
        || mainBranch.equals(branch)) {
      return Optional.empty();
    }
    try {
      RepoMirror mirror = mirrors.of(repoId);
      Optional<String> tip = mirror.remoteBranchSha(branch);
      if (tip.isEmpty()) {
        return Optional.of(true);
      }
      refreshNowQuietly(mirror);
      return Optional.of(mirror.isAncestor(tip.get(), "refs/heads/" + mainBranch));
    } catch (RuntimeException e) {
      LOG.debugf(e, "could not tell whether '%s' of %s holds anything beyond '%s'", branch, repoId, mainBranch);
      return Optional.empty();
    }
  }

  /** The parent a branch is compared against and how far it is ahead/behind it. */
  public record BranchSummary(String parent, Integer ahead, Integer behind) {}

  /**
   * Resolves a branch's parent — its workspace's fork point when workspace-backed, otherwise the
   * repository's {@code mainBranch} — and how far it is ahead of and behind that parent. Returns a
   * {@code null} parent (and zero counts) for the main branch itself or when no parent resolves.
   * Used to drive the branch tree's ahead/behind connector and commits popover for every branch,
   * including those without a workspace.
   */
  public BranchSummary summarize(String repoId, String branch, String mainBranch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return new BranchSummary(null, 0, 0);
    }
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    String parent =
        (wt != null && wt.parent != null && !wt.parent.isBlank()) ? wt.parent : mainBranch;
    if (parent == null || parent.isBlank() || parent.equals(branch)) {
      return new BranchSummary(null, 0, 0);
    }
    RepoMirror mirror = mirrors.of(repoId);
    refreshQuietly(mirror);
    AheadBehind ab = aheadBehind(mirror, parent, branch);
    return new BranchSummary(parent, ab.ahead(), ab.behind());
  }

  /**
   * True when tearing the workspace's container down loses no agent's work: every live agent on it
   * answers its cleanup check clean (or is unknown to its daemon), or it hosts none ({@link
   * AgentWorktreeGuard}, qits-1263). No container means there is nothing left in one to destroy.
   * Fail-closed: a live agent whose daemon cannot be asked counts as holding work.
   */
  private boolean isWorkspaceClean(Workspace wt) {
    if (!directContainerExists(wt)) {
      return true;
    }
    return agentWorktreeGuard.nothingToLose(wt.id);
  }

  /**
   * Refuses an operation that would clobber or silently discard an agent's work: throws a 400 when
   * an agent on the workspace's container holds uncommitted or unpushed work, is busy, or cannot be
   * checked ({@link AgentWorktreeGuard}). The matching server-side guard, so a direct API call
   * can't bypass the UI. Symmetric with {@link #isWorkspaceClean} — an absent container is clean
   * (nothing left in one to lose), so a stopped workspace is never blocked here.
   */
  private void requireCleanWorkingTree(Workspace wt, String operation) {
    if (!directContainerExists(wt)) {
      return;
    }
    agentWorktreeGuard
        .refusal(wt.id, wt.workspaceId, operation)
        .ifPresent(
            refusal -> {
              throw new BadRequestException(refusal.code(), refusal.message());
            });
  }

  /**
   * Pre-flight guard of branch integration ({@link #mergeBranch}) — and therefore of the MCP {@code integrateBranch} tool, which routes
   * through {@code mergeBranch}. Integration merges the source branch's <em>origin</em> ref (inside
   * the target's workspace), so before that ref is read this makes it faithful to the live source
   * workspace:
   *
   * <ol>
   *   <li>refuses a dirty working tree with a 400 — the origin-side merge would silently leave the
   *       workspace's uncommitted work behind; and
   *   <li>pushes the container's branch so every commit made inside the container reaches the
   *       origin ref the merge reads. The push is <em>not</em> swallowed: a failure aborts the
   *       whole integration, because a silently-skipped push would integrate a stale ref.
   * </ol>
   *
   * A {@code null} source workspace or one with no live container — a plain branch or a stopped
   * workspace — has nothing uncommitted to lose and a provably complete origin ref (nothing ever
   * ran to advance it beyond origin), so it is a no-op. This deliberately does <em>not</em> require
   * the source to be up to date with the target: integrating a diverged but cleanly-mergeable
   * branch (yielding a merge commit, or a reported conflict) is a supported flow.
   *
   * <p>Takes the already-resolved {@link Workspace} rather than re-looking-it-up by branch: {@code
   * mergeWorkspace} identifies its source by {@code workspaceId}, and two active workspaces could
   * in principle share a branch — re-resolving by branch could push/clean-check the wrong
   * container.
   */
  private void requireSyncedSourceForIntegration(String repoId, Workspace sourceWorkspace) {
    if (sourceWorkspace == null || !directContainerExists(sourceWorkspace)) {
      return;
    }
    // No push from here: requireCleanWorkingTree establishes that no agent on the source holds
    // uncommitted or unpushed work, and the daemon auto-pushes committed work as it goes, so origin
    // already has the source branch. The `docker exec git push` this used to run was a host-side
    // second opinion on state the daemon owns.
    requireCleanWorkingTree(sourceWorkspace, "integrate");
  }

  /**
   * Whether a row's container exists on the platform host, asked of qits-containers only behind the
   * router's refusal (qits-780). A TARGET row's container is in a slot of a target, never there, so it
   * answers false with no call — what the by-name lookup on the platform host always answered for it
   * — and the absent-means-nothing-to-lose readings above carry on as they did.
   */
  private boolean directContainerExists(Workspace wt) {
    if (wt.placement == WorkspacePlacement.TARGET) {
      return false;
    }
    WorkspacePlacements.requireDirectAllowed(wt);
    return containers.exists(containers.containerName(wt.workspaceId, wt.repositoryId));
  }

  /** True when another workspace forks from {@code branch} (i.e. lists it as its parent). */
  private boolean hasChildren(String repoId, String branch) {
    for (Workspace other : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(other.parent)) {
        return true;
      }
    }
    return false;
  }

  /**
   * After a host-side integration/merge advanced {@code targetBranch}'s origin ref, tell the
   * workspace that owns it (if any) to pull the update into its container — so its checkout catches
   * up right away instead of lagging until the next host git op (docs/epics/qits-workspace-daemon/
   * bidirectional auto-sync). Best-effort and fire-and-forget: no target workspace, no backend impl
   * (cli/tests), or no live daemon all short-circuit to a no-op. The daemon only fast-forwards, so
   * a target tree that turned dirty in the race window is left intact.
   */
  private void notifyIncomingMerge(String repoId, String targetBranch) {
    Workspace target = findWorkspaceByBranch(repoId, targetBranch);
    if (target == null || gitSync.isUnsatisfied()) {
      return;
    }
    gitSync.get().pullFromOrigin(target.id, targetBranch);
  }

  /** The workspace that owns {@code branch}, or null when none matches. */
  private Workspace findWorkspaceByBranch(String repoId, String branch) {
    if (branch == null) {
      return null;
    }
    for (Workspace wt : workspaceRepository.findActiveByRepositoryId(repoId)) {
      if (branch.equals(wt.branch)) {
        return wt;
      }
    }
    return null;
  }

  /**
   * Whether an ACTIVE agent's worktree (qits-1152) stands on {@code branch} as its {@code
   * wrapperBranch}. Agents live in {@code agent_worktree}, a table {@link #findWorkspaceByBranch}
   * never reads, so a wrapper branch created at main's head for a dispatched agent would otherwise
   * read as merged and unclaimed the moment it is pushed, before the agent has had any chance to
   * release it. Only ACTIVE protects: an OBSOLETE or REMOVED agent's branch is exactly the kind of
   * leftover {@link #sweepMergedBranches} exists to take.
   */
  private boolean hasActiveAgentWrapperBranch(String repoId, String branch) {
    return branch != null && !agentWorktrees.findActiveByWrapperBranch(repoId, branch).isEmpty();
  }

  /**
   * Counts how far {@code branch} is ahead of and behind its {@code parent} branch. Runs in the
   * <b>mirror</b>, which holds every branch of the repository as a ref because it is a mirror.
   * Returns {@code (0, 0)} when the two names are the same or either is missing, and {@code (null,
   * null)} if git can't resolve a ref there — the caller refreshes first, so an unresolvable ref
   * means the git host does not have it either.
   */
  private AheadBehind aheadBehind(RepoMirror mirror, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)) {
      return new AheadBehind(0, 0);
    }
    try {
      var counts = mirror.aheadBehind("refs/heads/" + parent, "refs/heads/" + branch);
      return new AheadBehind(counts.ahead(), counts.behind());
    } catch (GitMirrorException e) {
      return new AheadBehind(null, null);
    }
  }

  private record AheadBehind(Integer ahead, Integer behind) {}

  /**
   * Whether merging {@code parent} into {@code branch} would produce conflicts, decided by a real
   * three-way merge in the mirror's object store via {@code git merge-tree --write-tree} (no working
   * tree touched). An unresolvable ref or any other error is treated as "no conflict" so we never
   * raise a false warning.
   */
  private boolean wouldConflict(RepoMirror mirror, String parent, String branch) {
    if (parent == null
        || branch == null
        || parent.isBlank()
        || branch.isBlank()
        || parent.equals(branch)
        || parent.startsWith("-")
        || branch.startsWith("-")) {
      return false;
    }
    try {
      return !mirror.previewMerge("refs/heads/" + branch, "refs/heads/" + parent).clean();
    } catch (GitMirrorException e) {
      return false;
    }
  }

  /**
   * Refresh a mirror inside the freshness window, and treat a git host that is briefly away as a
   * slightly stale number rather than as an error. Every caller of this reads counts for a screen;
   * nothing that <i>decides</i> anything comes through here.
   */
  private void refreshQuietly(RepoMirror mirror) {
    try {
      mirror.refresh();
    } catch (GitMirrorException e) {
      LOG.debugf(e, "could not refresh the mirror of %s", mirror.repoId());
    }
  }

  /** {@link #refreshQuietly} ignoring the freshness window — for the decisions, not the screens. */
  private void refreshNowQuietly(RepoMirror mirror) {
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      LOG.debugf(e, "could not refresh the mirror of %s", mirror.repoId());
    }
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch) {
    return createWorkspace(repoId, workspaceId, parent, branch, null, false);
  }

  public Workspace createWorkspace(
      String repoId, String workspaceId, String parent, String branch, String preamble) {
    return createWorkspace(repoId, workspaceId, parent, branch, preamble, false);
  }


  /**
   * Creates a workspace for a branch. Normally {@code branch} is a <em>new</em> branch this
   * workspace owns, forked off {@code parent} — a fresh ref is created in the origin. When {@code
   * adoptExisting} is set and {@code branch} already exists in the origin, the workspace instead
   * <em>adopts</em> that existing branch in place: no ref is created, the row is simply recorded
   * over it (the branch-list "Create workspace" button on a branch that has no workspace yet). The
   * container is provisioned lazily from the branch ref on first use either way.
   *
   * <p>An ordinary workspace, which is what every caller that says nothing about a posture gets:
   * {@code admin} is false, so its container is launched with no docker socket. The admin posture
   * arrives through the seven-argument form below and, from there, through {@link #recordWorkspace}
   * — there is deliberately no overload of this shape that takes it, because two adjacent booleans
   * a caller passes positionally is how {@code branchTree} would one day be read as {@code admin}.
   */
  @Transactional
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting) {
    return recordWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        false,
        WorkspaceSubject.none(),
        null);
  }

  /**
   * The row-writing core of every create: the guards, the branch push and the row itself, with the
   * posture the caller asked for.
   *
   * <p>Private, and it is the only writer of {@code Workspace.admin}. A public overload carrying the
   * flag would be one whose call sites are read positionally; keeping it here means the two callers
   * that can set it are both in this file and both visible in one screen.
   *
   * <p>It is also the only writer of the {@link WorkspaceSubject}, for the milder version of the
   * same reason: the two ids are same-typed and adjacent, so they travel as a record rather than as
   * a pair of positional strings.
   */
  private Workspace recordWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean admin,
      WorkspaceSubject subject,
      WorkspacePlacement placement) {
    var repo = repositories.require(repoId);

    // `workspaceId` becomes a path segment under the repo's workspaces dir, so it must be a strict
    // slug: no slashes/dots/dashes-leading that could traverse out of the dir or smuggle a git
    // flag.
    if (!workspaceId.matches("[A-Za-z0-9_-]{1,64}") || workspaceId.startsWith("-")) {
      throw new BadRequestException("Invalid workspace id: " + workspaceId);
    }

    // Where the container runs, decided once and here (qits-837, qits-774): a regular workspace
    // always runs in a workspace slot of a target and admin stays DIRECT; whether a slot is free is
    // not asked — with none, the TARGET row's lease simply waits QUEUED. A stated placement that contradicts the rule is
    // refused before the branch is pushed, so it costs nothing. The editor is written by its own door.
    refuseUnplaceable(placement, admin);
    WorkspacePlacement placed = WorkspacePlacements.forNewRow(admin);
    LOG.infof("Workspace %s/%s placement=%s", repoId, workspaceId, placed);

    RepoMirror mirror = mirrors.of(repoId);

    // Resolved rows linger (soft delete), so only an ACTIVE workspace blocks the id — a resolved
    // one
    // can be reused.
    if (workspaceRepository.existsActiveByRepositoryAndWorkspaceId(repoId, workspaceId)) {
      throw new BadRequestException("Workspace already exists: " + workspaceId);
    }

    // `parent` is the branch to fork from; `branch` is the new branch the workspace owns.
    // Each workspace gets its own branch so two workspaces never commit to the same branch.
    String parentBranch = (parent == null || parent.isBlank()) ? defaultMainBranch(repo) : parent;
    String newBranch = (branch == null || branch.isBlank()) ? workspaceId : branch;
    // Both are user-supplied and passed to git: reject dash-leading names so they can't be smuggled
    // in as flags (argv flag injection).
    if (parentBranch.startsWith("-") || newBranch.startsWith("-")) {
      throw new BadRequestException("Invalid branch name");
    }

    // The branch is the resource being claimed, so the check belongs here — after `branch` and
    // `workspaceId` have been reconciled, not before. The id guard above is about the surrogate
    // label; it says nothing about the branch, because `branch` is an independent request field
    // that merely *defaults* to the id. Under the ordinary usage where the two coincide the two
    // guards agree, which is why the missing one went unnoticed; they stop coinciding the moment a
    // caller sets both, and `adoptExisting` then skips the ref creation that was the only thing
    // standing in the way. UQ_workspace_active_branch (V3) enforces the same rule structurally.
    if (workspaceRepository.existsActiveByRepositoryAndBranch(repoId, newBranch)) {
      throw new ConflictException("Branch already has an active workspace: " + newBranch);
    }
    // At most one ACTIVE workspace per work item (uq_workspace_active_work, V11). Checked here,
    // before the branch is pushed, so a refused create leaves no branch behind. The index is what
    // holds under a race.
    WorkspaceSubject named = subject == null ? WorkspaceSubject.none() : subject.normalized();
    refuseSecondActiveForWork(named.workId());

    // Only the durable state is created here: the branch, PUSHED to the git host (so ahead/behind
    // and the merge-tree conflict probe both have a ref to read, and so the ordinary post-receive
    // fires for it like every other push) plus the row below. No container, no clone — provisioning
    // is lazy: first use goes through ensureContainer, which materializes the container from this
    // branch. That keeps creation free of docker.
    //
    // Adoption: when asked to adopt and the branch already exists (e.g. a branch pushed or created
    // outside qits), skip the creation and record the workspace over the existing branch. The
    // normal path still creates it — and errors loudly if it is already there — so a typo'd
    // "branch off" name is never silently swallowed.
    if (!(adoptExisting && mirror.remoteHasBranch(newBranch))) {
      createBranchOnHost(mirror, newBranch, parentBranch);
    }

    Workspace workspace = new Workspace();
    workspace.workspaceId = workspaceId;
    workspace.repositoryId = repoId;
    workspace.parent = parentBranch;
    workspace.branch = newBranch;
    workspace.status = WorkspaceStatus.ACTIVE;
    // Every row is written STOPPED. A TARGET row is still started by its create, by the TARGET start
    // once this transaction committed (queuedOnTarget): that start is the one path to a lease, and it
    // builds the edge plane and mints the lease's token first (qits-625), so no lease is ever asked
    // for without one. A DIRECT row is started by whoever created it; see createAndStartWorkspace.
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.preamble = preamble;
    // The posture, written once and never again: no verb promotes a workspace to admin later, so
    // the socket a container gets is the one the request that created it asked for. See
    // Workspace.admin.
    workspace.admin = admin;
    // Written once and never changed, like the posture above (Workspace.placement). A TARGET row has
    // no lease yet: its first start asks for one.
    workspace.placement = placed;
    // What this workspace is for, where a dispatch said so. Blanks normalise to null: an empty id
    // is not a subject, and a row claiming one would render a link to nothing.
    workspace.ticketId = named.ticketId();
    workspace.epicId = named.epicId();
    workspace.entityId = named.entityId();
    workspace.workId = named.workId();
    workspaceRepository.persist(workspace);
    recordEvent(workspace, WorkspaceEventType.CREATED, newBranch, parentBranch, null);

    WorkspaceMetadata metadata = new WorkspaceMetadata();
    metadata.workspaceId = workspaceId;
    metadata.parent = parentBranch;
    workspaceMetadata.write(repoId, metadata);

    return workspace;
  }

  /**
   * Refuses a stated placement that contradicts {@link WorkspacePlacements#forNewRow}: 400 for an
   * admin workspace asked onto a target (it holds the host's docker socket and always runs DIRECT,
   * {@code ck_workspace_runner_posture}), and 400 {@code DIRECT_PLACEMENT_REFUSED} for a regular
   * workspace asked onto the direct path (qits-774: a regular workspace runs on a target). The
   * agreeing statements — DIRECT for admin, TARGET for a regular workspace — and a null placement
   * (nothing stated) pass. The editor is written by its own door, never through here.
   */
  private void refuseUnplaceable(WorkspacePlacement placement, boolean admin) {
    if (placement == WorkspacePlacement.TARGET && admin) {
      throw new BadRequestException(
          "An admin workspace holds the host's docker socket and always runs DIRECT; it cannot be"
              + " placed on a target");
    }
    if (placement == WorkspacePlacement.DIRECT && !admin) {
      throw TargetRefusals.directPlacementRefused();
    }
  }

  /** 409 when the work item already has an ACTIVE workspace; nothing for a null work id. */
  private void refuseSecondActiveForWork(String workId) {
    if (workspaceRepository.existsActiveByWorkId(workId)) {
      throw new ConflictException("Work item already has an active workspace: " + workId);
    }
  }

  /**
   * Extended create form for aggregate repositories. A branch-tree workspace forks the wrapper and
   * every registered repository reachable from its committed submodule declarations, then adds the
   * workspace hand-off document to the wrapper before the ordinary workspace row is recorded.
   *
   * <p>Deliberately NOT one {@code @Transactional}, for the reason {@link #landWorkspace} spells
   * out: the tree is a fetch, a worktree and a push <em>per repository</em>, and a wrapper of twenty
   * submodules outlasts Narayana's transaction timeout — which would surface as a transaction
   * failure rather than as anything a caller could read. The guards read in one short transaction,
   * the git work runs outside every transaction, and the row is written in another.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree) {
    return createWorkspace(
        repoId, workspaceId, parent, branch, preamble, adoptExisting, branchTree, false);
  }

  /**
   * The same create, told what the workspace is <b>for</b> — the ticket or epic an agent dispatch
   * was about ({@link WorkspaceSubject}).
   *
   * <p>An overload of its own rather than a ninth positional argument on the admin form below, and
   * the two do not meet: a dispatch never asks for the docker socket, and the record's type is what
   * keeps this signature apart from the {@code boolean}-tailed one at every call site. The subject
   * is written once, by {@link #recordWorkspace}, exactly as the posture is.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      WorkspaceSubject subject) {
    return createWorkspace(
        repoId, workspaceId, parent, branch, preamble, adoptExisting, branchTree, false, subject);
  }

  /**
   * The same create, told the <b>posture</b> the workspace is to run in.
   *
   * <p>{@code admin} is the request to bind the host's docker socket into this workspace's
   * container, so that platform administration can be done from inside it — the one privilege a
   * workspace can be granted, and a container holding it is root-equivalent on the host. It is a
   * property of the workspace from here on: it is written to the row (the only writer is {@link
   * #recordWorkspace}), read back at every ensure by {@link WorkspacePostures}, and no verb
   * promotes a workspace to it afterwards. Everything else about an admin workspace — its image,
   * its user, its limits, its mounts — is what every other workspace gets.
   *
   * <p>The eighth argument rather than a widened seventh: the seven-argument form above is what
   * every existing caller means, and it keeps meaning it. Who is <em>allowed</em> to ask for admin
   * is the API boundary's question, not this method's — {@code WorkspaceController} answers it.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin) {
    return createWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        branchTree,
        admin,
        WorkspaceSubject.none());
  }

  /** The posture and the subject together, placed by the rule. */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject) {
    return createWorkspace(
        repoId,
        workspaceId,
        parent,
        branch,
        preamble,
        adoptExisting,
        branchTree,
        admin,
        subject,
        null);
  }

  /**
   * The widest form, and the only one taking a stated {@code placement}: where the container runs,
   * {@code DIRECT} or {@code TARGET} (epic qits-624, qits-1481). It is never a choice — admin is
   * DIRECT and a regular workspace TARGET ({@link WorkspacePlacements#forNewRow}), null meaning "the
   * rule" — so a statement can only agree or be refused: TARGET with {@code admin} is a 400, and
   * DIRECT without it a 400 {@code DIRECT_PLACEMENT_REFUSED}, both before any ref is pushed. No free
   * slot is not a refusal: the TARGET row's lease waits QUEUED. Every other overload delegates here
   * with null.
   *
   * <p>A TARGET row is started by its create once the row committed, through the TARGET start: the
   * edge plane is built and the lease's token minted first (qits-625), then the lease is asked for
   * and the row goes QUEUED with {@code queued_at}. A start that cannot do that leaves the row FAILED
   * saying why (or STOPPED when qits-system did not answer), and the create still stands. A DIRECT
   * row is written STOPPED.
   */
  public Workspace createWorkspace(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      WorkspacePlacement placement) {
    return queuedOnTarget(
        recordPlaced(
            repoId,
            workspaceId,
            parent,
            branch,
            preamble,
            adoptExisting,
            branchTree,
            admin,
            subject,
            placement));
  }

  /**
   * Starts a TARGET row its create just committed, through the TARGET start and nothing beside it
   * ({@link #beginTargetStart}, unnarrated: the row's status is its progress), so the create path
   * gets the same edge-plane check and the same token mint every start gets (qits-625). Answers the
   * row as the start left it: QUEUED, or FAILED with the reason.
   *
   * <p><b>A start refused after the row exists does not undo the create</b>, for the reason {@link
   * #createAndStartWorkspace} gives; it is logged here, and the row says what happened.
   */
  private Workspace queuedOnTarget(Workspace created) {
    if (created.placement != WorkspacePlacement.TARGET) {
      return created;
    }
    try {
      beginTargetStart(created, false);
    } catch (RuntimeException refused) {
      LOG.warnf(
          refused,
          "Workspace %s/%s was created but no lease could be asked for it",
          created.repositoryId,
          created.workspaceId);
    }
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findByIdOptional(created.id).orElse(created));
  }

  /** {@link #createWorkspace}'s widest form up to the commit: the row as written, not yet signalled. */
  private Workspace recordPlaced(
      String repoId,
      String workspaceId,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin,
      WorkspaceSubject subject,
      WorkspacePlacement placement) {
    // A call on `this` never reaches the interceptor, so each delegation below opens its own
    // transaction explicitly rather than relying on the annotation of the method it calls.
    if (!branchTree) {
      return QuarkusTransaction.requiringNew()
          .call(
              () ->
                  recordWorkspace(
                      repoId,
                      workspaceId,
                      parent,
                      branch,
                      preamble,
                      adoptExisting,
                      admin,
                      subject,
                      placement));
    }
    // The branch tree (a branch in every submodule, plus a WORKSPACE.md commit) went with
    // qits-1152: an agent names its own submodule branches, and nothing is created up front (D9).
    throw new BadRequestException("Branch-tree workspaces are gone; dispatch an agent instead.");
  }

  /**
   * <b>A workspace slot</b> (qits-1152, D2): one container on an execution target — or, for an admin, on the
   * platform host — scoped to a project's wrapper, created when an agent needs a slot and removed
   * when its last agent is. It names no branch, parent or work item: those are its agents'. It
   * pushes nothing either ({@code gitRefs []}); every push is an agent's, with the agent's own
   * credential.
   *
   * <p>Started at once: a TARGET row asks for its lease through the TARGET start (edge plane, the
   * lease's token), a DIRECT admin row through the ensure ladder.
   */
  public Workspace createSlot(String repoId, boolean admin) {
    RepositoryLookup.RepositoryView repo = repositories.require(repoId);
    WorkspacePlacement placed = WorkspacePlacements.forNewRow(admin);
    Workspace created =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  // Named after its own row id once it has one: unique, and stable for a reader.
                  workspace.workspaceId = "ws-pending-" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.repositoryId = repo.id();
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                  workspace.admin = admin;
                  workspace.placement = placed;
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  workspace.workspaceId = "ws-" + workspace.id;
                  recordEvent(workspace, WorkspaceEventType.CREATED, null, null, null);
                  return workspace;
                });
    LOG.infof("Workspace %s/%s created as a slot (placement=%s)", repoId, created.workspaceId, placed);
    if (created.placement == WorkspacePlacement.TARGET) {
      return queuedOnTarget(created);
    }
    try {
      beginEnsureContainer(created.id);
    } catch (RuntimeException refused) {
      LOG.warnf(refused, "Workspace %s was created but its start was refused", created.id);
    }
    return created;
  }

  /**
   * Sanitizes a branch name into a workspace-id slug ([A-Za-z0-9_-], ≤64 chars, not dash-leading).
   * Public because the capture ingest derives workspace ids from its generated branch names through
   * the same rule.
   */
  public static String toWorkspaceSlug(String branch) {
    String slug = branch.replaceAll("[^A-Za-z0-9_-]", "-");
    if (slug.length() > 64) {
      slug = slug.substring(0, 64);
    }
    if (slug.isBlank() || slug.startsWith("-")) {
      slug = "main";
    }
    return slug;
  }

  /**
   * The branch a blank parent/target defaults to: the repository's configured main branch (set at
   * clone time from the remote's default), with "master" as the last-resort fallback for a row
   * whose {@code mainBranch} was never populated.
   */
  private static String defaultMainBranch(RepositoryLookup.RepositoryView repo) {
    return (repo.mainBranch() == null || repo.mainBranch().isBlank())
        ? "master"
        : repo.mainBranch();
  }

  /**
   * What an ensure needs off the row: the branch to check and provision from, the parent, whether
   * this is the editor's row — the one workspace that has no branch and must not be read as having
   * lost one — and the subject's entity id, carried along so the daemon's env is reproducible on the
   * resume path exactly as {@code branch}/{@code parent} already are. See {@link
   * #ensureContainer(String, String, Long, WorkspaceProcessTracker.Handle)}.
   */
  private record BranchParent(String branch, String parent, String entityId) {}

  /**
   * Guarantees a running container for an ACTIVE workspace whose branch still exists, provisioning
   * one on demand — the container is a recreatable cache of the durable branch, so losing it is a
   * non-event. Idempotent:
   *
   * <ul>
   *   <li>container already running → stamp {@code RUNNING}, no-op. A live container is
   *       <em>never</em> re-cloned over, so unpushed {@code /workspace} commits are safe.
   *   <li>container present but stopped (e.g. a host/docker restart left it {@code Exited}) →
   *       {@code docker start} it in place. This keeps the {@code /workspace} clone and any
   *       unpushed commits — the lossless recovery a re-clone can't give — so it wins over
   *       re-provisioning.
   *   <li>container absent but the branch ref survives in origin → materialize a fresh container
   *       from that branch via {@link #provisionContainer} — the single provisioning path, for
   *       never-provisioned and pruned workspaces alike.
   *   <li>branch ref gone from origin → the work no longer exists anywhere: the workspace is
   *       ABANDONED here (now the <em>only</em> path to abandonment) and a 404 is thrown.
   * </ul>
   *
   * <p><strong>Loss window:</strong> recreation restores <em>origin</em> state only. Commits made
   * in a container but never pushed die with it; the live-container guard protects the graceful
   * case, but an unexpected container death is still lossy (see {@link #stopContainer} for the
   * lossless stop). Not {@code @Transactional}: each status transition commits in its own
   * transaction so a FAILED/ABANDONED outcome is persisted even though the method then throws, and
   * so it is safe to call from non-request threads (like {@code CommandService.prepare}).
   */
  public void ensureContainer(Long id) {
    // Its own transaction: this is called from worker threads (the bootstrap runner's manual-run
    // executor) where no session is open, and the rest of ensureContainer already brackets each of
    // its own reads the same way.
    Workspace row = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    // A target-placed row's container is its lease's, never the direct ladder's (qits-780): the
    // bootstrap's manual run needs it up, so a RUNNING one goes ahead and anything else is asked to
    // start first rather than provisioned on the platform host.
    if (row.placement == WorkspacePlacement.TARGET) {
      if (row.runtimeStatus != WorkspaceRuntimeStatus.RUNNING) {
        throw new BadRequestException(
            "Workspace '"
                + row.workspaceId
                + "' runs on an execution target and is not running: start it, then run again.");
      }
      return;
    }
    ensureContainer(row.repositoryId, row.workspaceId, id, null, registerStart(id, () -> null));
  }

  /**
   * The streaming Start: registers a {@link WorkspaceProcessTracker.Handle} for the workspace <em>before</em> any
   * work runs (so the very first {@code docker run} line is captured), spawns {@link
   * #ensureContainer(String, String, WorkspaceProcessTracker.Handle)} on a worker thread, and returns the process
   * id immediately. The browser watches the work — including the asynchronous bootstrap phase —
   * over the process's SSE stream; failures surface there (and in {@code
   * workspace.runtimeError}), not as an HTTP error. Throws 404 in-request when the workspace
   * doesn't exist, so a bad id still fails fast.
   *
   * <p><b>It joins a start only while that start can still bring the container up</b> (qits-853,
   * qits-1076) — see {@link #joinableStart}. A stop ends the start it overtakes, so the usual case
   * after a stop is no process at all and a fresh start here; the 409 is for the instant in between.
   */
  public String beginEnsureContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    if (resolved.placement == WorkspacePlacement.TARGET) {
      return beginTargetStart(resolved);
    }
    WorkspacePlacements.requireDirectAllowed(resolved);
    Optional<String> joined = joinableStart(resolved);
    if (joined.isPresent()) {
      return joined.get();
    }
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    WorkspaceProcessTracker.Handle[] begun = new WorkspaceProcessTracker.Handle[1];
    DirectStart start =
        registerStart(
            rowId,
            () -> {
              begun[0] = tracker(repoId, workspaceId, rowId);
              return begun[0] == null ? null : begun[0].id();
            });
    WorkspaceProcessTracker.Handle process = begun[0];
    processExecutor.submit(
        () -> {
          try {
            ensureContainer(repoId, workspaceId, rowId, process, start);
          } catch (RuntimeException e) {
            // Surface the failure in the stream: settle the open segment failed (appending the
            // message) and emit done. Idempotent — a no-op if the process already ended.
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed ensure-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * The start an ensure-container joins instead of making a second one, or empty to start afresh.
   *
   * <p><b>Why it joins at all</b> (qits-853): creating a workspace starts it, so a client that still
   * presses ensure-container straight after the create lands here while that start is cloning —
   * before its container exists, when the ladder would provision a second one beside it.
   *
   * <p><b>Why it must not join just anything</b> (qits-1076): the workspace's active process used to
   * be joined whatever the row said, so after a stop overtook a start — in its clone window, or in
   * the bootstrap window with the row already RUNNING — the next ensure handed back that dead start
   * and answered 200 STOPPED with nothing starting. The rule is now:
   *
   * <ul>
   *   <li>A FAILED row starts afresh: its process is the one that failed.
   *   <li>A start this service registered is joined exactly while no stop has landed since it
   *       began — whatever the row says, because the create's start reads STOPPED until its
   *       thread writes PROVISIONING, and that is the very ensure qits-853 joins.
   *   <li>Any other process is joined only while the row is PROVISIONING or RUNNING, the states in
   *       which a start is actually bringing the container up.
   *   <li>Anything else is a start a stop overtook whose ending has not landed yet — the stop ends
   *       it once its own transaction commits. That is a 409 naming the process, never a 200 with
   *       a process that will not start the container.
   * </ul>
   */
  private Optional<String> joinableStart(Workspace row) {
    if (row.runtimeStatus == WorkspaceRuntimeStatus.FAILED || !processes.isResolvable()) {
      return Optional.empty();
    }
    Optional<String> active = processes.get().activeFor(row.id);
    if (active.isEmpty()) {
      return Optional.empty();
    }
    String processId = active.get();
    DirectStart latest = directStarts.get(row.id);
    boolean registeredHere = latest != null && processId.equals(latest.processId());
    boolean joinable =
        registeredHere
            ? isLiveStart(row.id, processId)
            : row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING
                || row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING;
    if (joinable) {
      return active;
    }
    throw new ConflictException(
        "WORKSPACE_STOPPED_DURING_START",
        "Workspace '"
            + row.workspaceId
            + "' was stopped while it was starting, and that start's technical process "
            + processId
            + " is still open. It will not bring the container up; retry once process "
            + processId
            + " has ended.");
  }

  /**
   * Recreate a workspace's container on the current image — the way to roll a workspace onto a
   * newer {@code workspace-daemon} build (docs/epics/qits-workspace-registry/). Unlike {@link
   * #beginEnsureContainer} (which resumes an existing container in place), this deliberately tears
   * the old container down and provisions a fresh one, so a {@code docker run} picks up whatever
   * {@code qits.workspace.image-repo}:{@code qits.workspace.image-version} now resolves to.
   *
   * <p><b>That now resolves to a pinned, published release</b> rather than a local {@code :latest}
   * tag, which narrows what a recreate can reach. It rolls a workspace forward exactly as far as
   * the version this deployment carries, and no further — and what that version is has moved: it is
   * {@code WorkspaceImage.VERSION}, the version of the {@code qits-workspace-daemon-protocol}
   * dependency this build was compiled against, not an environment entry qits-configuration rewrote
   * underneath the running process. So reaching a newer daemon takes a bump of that pom line, a
   * release of this service and a deploy — three reviewed steps where there used to be a listener.
   * Recreate is still the operation that applies a new image; it just applies the one this release
   * was tested with.
   *
   * <p><b>Requires that no agent's work can be lost</b> ({@link #requireCleanForRecreate},
   * qits-1263). What a recreate can destroy is the slot's agent worktrees, so each live agent on it
   * is checked with its daemon: leftover work, a busy harness, or a live agent whose daemon cannot
   * be asked each refuse with a 400 naming the agent. A slot with no live agent is recreated
   * whatever its daemon's state — the wrapper checkout is nobody's work. The gate runs
   * synchronously so a bad request fails fast; the teardown+reprovision then streams like {@link
   * #beginEnsureContainer}: settle services gracefully → {@code rm} the old container → {@link
   * #ensureContainer} provisions a fresh one from the durable branch (whose absent-container path
   * re-runs {@link #provisionContainer}).
   */
  public String beginRecreateContainer(Long id) {
    Workspace resolved = QuarkusTransaction.requiringNew().call(() -> requireActive(id));
    if (resolved.placement == WorkspacePlacement.TARGET) {
      return beginTargetRecreate(resolved);
    }
    WorkspacePlacements.requireDirectAllowed(resolved);
    String repoId = resolved.repositoryId;
    String workspaceId = resolved.workspaceId;
    Long rowId = resolved.id;
    requireCleanForRecreate(workspaceId, rowId);
    // Read before the container goes (qits-1745): the agents this recreate affects.
    List<String> agentIds = liveAgentIds(rowId);
    // Registered as a start like any other, so a stop during the recreate overtakes it the same
    // way (qits-1076) — and so a recreate after a stop is simply the newest start, owing the stop
    // nothing.
    WorkspaceProcessTracker.Handle[] begun = new WorkspaceProcessTracker.Handle[1];
    DirectStart start =
        registerStart(
            rowId,
            () -> {
              begun[0] = tracker(repoId, workspaceId, rowId);
              return begun[0] == null ? null : begun[0].id();
            });
    WorkspaceProcessTracker.Handle process = begun[0];
    processExecutor.submit(
        () -> {
          try {
            // No backup push: requireCleanForRecreate established that every agent's worktrees are
            // clean and pushed (or that the slot hosts no agent), and the daemon pushes committed
            // work as it lands, so there is nothing for the host to preserve.
            // Settle live services gracefully so their disappearance reads as deliberate, not a
            // crash
            // the restart policy would resurrect — the same courtesy stopContainer/discard extend.
            containerEvents.fireStopping(repoId, workspaceId, rowId, true, agentIds);
            stopBeforeRemove(containers.containerName(workspaceId, repoId));
            containers.rm(containers.containerName(workspaceId, repoId));
            // Container now absent → ensureContainer's provision path re-clones on the current
            // image.
            ensureContainer(repoId, workspaceId, rowId, process, start);
          } catch (RuntimeException e) {
            if (process != null) {
              process.failProvision(e.getMessage());
            }
            LOG.debugf(
                e, "Streamed recreate-container failed for workspace %s/%s", repoId, workspaceId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Recreate's gate, on both placements (qits-1263): every live agent on the slot must answer its
   * cleanup check clean, or be unknown to its daemon ({@link AgentWorktreeGuard}). Unlike {@link
   * #requireCleanWorkingTree} it does not ask whether a container exists — a TARGET row's container
   * is not on this host, and its volume goes with the recreate too — so a slot whose agents cannot
   * be checked is refused whatever its placement. A refusal is a 400 — the status recreate has
   * always refused with, and the one qits-landing-app's pact pins — carrying {@code
   * AGENT_HAS_LEFTOVER_WORK}, {@code AGENT_BUSY} or {@code AGENT_DAEMON_UNREACHABLE}, the codes an
   * agent's own discard answers with (as 409s). A slot with no live agent passes even with no
   * daemon connected: nothing of anybody's would be lost.
   */
  private void requireCleanForRecreate(String workspaceId, Long rowId) {
    agentWorktreeGuard
        .refusal(rowId, workspaceId, "recreate")
        .ifPresent(
            refusal -> {
              throw new BadRequestException(refusal.code(), refusal.message());
            });
  }

  /**
   * {@link #ensureContainer(String, String)} with an optional {@link WorkspaceProcessTracker.Handle} receiving
   * the work as streamed segments. With a process attached, every outcome also ends the process:
   * the already-running short-circuit completes it as a no-op, a provision failure fails it, and a
   * successful start hands the process id to the async bootstrap phase via {@link
   * WorkspaceContainerEventPublisher#fireStarted(String, String, String, boolean)} — the process
   * then reaches {@code done} once {@code WorkspaceBootstrapRunner} reports that phase over.
   *
   * <p><b>A stop overtakes it</b> (qits-1076). {@code start} carries the stop count the start began
   * under, and every runtime write below — PROVISIONING, RUNNING, FAILED — is made only while that
   * count still holds ({@link #markRuntimeUnlessStopped}). Once a stop or a container delete has
   * landed, the start {@link #yieldToStop yields}: it removes nothing, marks nothing FAILED, never
   * turns the row RUNNING and fires no {@code started}, and throws {@link StoppedDuringStart}, whose
   * message is the line its process ends on. That holds on all three rungs: the already-running
   * short-circuit, the {@code exists → start} resume (whose {@code start} may have restarted a
   * container the stop had just paused, which is why yielding stops it again) and the provision.
   */
  private void ensureContainer(
      String repoId,
      String workspaceId,
      Long rowId,
      WorkspaceProcessTracker.Handle process,
      DirectStart start) {
    String container = containers.containerName(workspaceId, repoId);
    if (stoppedSince(rowId, start)) {
      // Stopped between the request and this worker picking the start up.
      throw yieldToStop(repoId, workspaceId, rowId);
    }

    // Load branch/parent and short-circuit a live container, in its own transaction.
    BranchParent snapshot =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace wt =
                      workspaceRepository
                          .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                          .orElseThrow(
                              () -> new NotFoundException("Workspace not found: " + workspaceId));
                  // The ladder's one gate (qits-780): every qits-containers call below — isRunning,
                  // exists/start, the provision's run and its rm, the branch-gone volume reap — is
                  // behind it. It is also what refuses a TARGET row that reached this ladder through
                  // ensureContainer(Long), which does not branch on placement.
                  WorkspacePlacements.requireDirectAllowed(wt);
                  if (containers.isRunning(container)) {
                    return null; // already running — nothing to provision
                  }
                  return new BranchParent(wt.branch, wt.parent, wt.entityId);
                });
    if (snapshot == null) {
      // The RUNNING stamp is written here rather than in the snapshot, so it is checked against a
      // stop like every other write of this ladder.
      if (!markRuntimeUnlessStopped(
          repoId, workspaceId, rowId, start, WorkspaceRuntimeStatus.RUNNING, null)) {
        throw yieldToStop(repoId, workspaceId, rowId);
      }
      observeClientLiveness(repoId, workspaceId, rowId);
      if (process != null) {
        process.completeNoOp("container-start", "Container is already running — nothing to do.");
      }
      return;
    }

    // Present but not running — a container that died out-of-band (classically a host/docker
    // restart leaving it stopped). `isRunning` is false but the container and its /workspace volume
    // still exist, so start it back up rather than re-cloning: this keeps unpushed commits, the
    // lossless recovery the graceful-stop path can't offer once the container died unexpectedly.
    // The branch-gone abandonment below deliberately doesn't apply here — the work lives on the
    // volume, not just origin.
    //
    // `start` takes the workspace's identity rather than the container name, and the snapshot is
    // where branch and parent come from: the orchestrator has no start verb, so a stopped place is
    // started by asking for it again with its spec. See ContainerRuntime.start.
    if (containers.exists(container)) {
      if (!markRuntimeUnlessStopped(
          repoId, workspaceId, rowId, start, WorkspaceRuntimeStatus.PROVISIONING, null)) {
        throw yieldToStop(repoId, workspaceId, rowId);
      }
      try {
        if (process != null) {
          process.openSegment("container-start");
        }
        containers.start(
            repoId, workspaceId, rowId, snapshot.branch(), snapshot.parent(), snapshot.entityId());
        // RUNNING is written before the provision phase reports, so a stop that landed during the
        // start is found while the process can still fail with it.
        if (!markRuntimeUnlessStopped(
            repoId, workspaceId, rowId, start, WorkspaceRuntimeStatus.RUNNING, null)) {
          throw yieldToStop(repoId, workspaceId, rowId);
        }
        if (process != null) {
          process.appendLine(
              "container-start",
              "Started the existing container again (its /workspace volume is preserved).");
          process.settleSegment("container-start", true);
          process.finishProvision(true);
        }
        // Cold -> RUNNING, but not a fresh provision: the clone (and its bootstrap state) survived,
        // so the bootstrap runner passes straight through and ends the process (async).
        containerEvents.fireStarted(
            repoId, workspaceId, rowId, process == null ? null : process.id(), false);
        observeClientLiveness(repoId, workspaceId, rowId);
        return;
      } catch (StoppedDuringStart e) {
        throw e;
      } catch (RuntimeException e) {
        // A start that failed under a stop is the stop's outcome, not a FAILED row.
        if (!markRuntimeUnlessStopped(
            repoId,
            workspaceId,
            rowId,
            start,
            WorkspaceRuntimeStatus.FAILED,
            truncate(e.getMessage()))) {
          throw yieldToStop(repoId, workspaceId, rowId);
        }
        throw e;
      }
    }

    // A slot (qits-1152) names no branch and is never abandoned for lacking one; an old-model row
    // whose durable branch is gone is.
    if (snapshot.branch() != null
        && !snapshot.branch().isBlank()
        && !branchExists(repoId, snapshot.branch())) {
      // The durable branch is gone: this is genuine death, so abandon (persisted before we throw).
      QuarkusTransaction.requiringNew()
          .run(
              () -> {
                Workspace wt =
                    workspaceRepository
                        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
                        .orElseThrow(
                            () -> new NotFoundException("Workspace not found: " + workspaceId));
                wt.status = WorkspaceStatus.ABANDONED;
                wt.resolvedAt = Instant.now();
                wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                recordEvent(wt, WorkspaceEventType.ABANDONED, wt.branch, null, null);
                // The second termination path (beside doDiscard). The workspace is only
                // soft-deleted, so no FK cascade fires for the rows other contexts hang off it —
                // they clean up on this event, inside this transaction.
                workspaceResolvedEvent.fire(
                    new WorkspaceResolved(
                        repoId, workspaceId, wt.id, WorkspaceStatus.ABANDONED));
              });
      // The branch is gone, so any persisted /workspace volume is orphaned work — reap it. The
      // container is already absent on this path (we passed the isRunning/exists branches), so no
      // prior rm is needed. Best-effort.
      containers.removeWorkspaceVolume(workspaceId);
      // And so is the credential that container held. Outside the transaction above, beside the
      // volume reap, for the reason doDiscard states at length: the resolution transaction is where
      // observers join and is not where an HTTP call belongs.
      decommissionFor(rowId);
      throw new NotFoundException(
          "Workspace '" + workspaceId + "' has no branch to recreate from; abandoned");
    }

    if (!markRuntimeUnlessStopped(
        repoId, workspaceId, rowId, start, WorkspaceRuntimeStatus.PROVISIONING, null)) {
      throw yieldToStop(repoId, workspaceId, rowId);
    }
    try {
      provisionContainer(
          repoId,
          workspaceId,
          rowId,
          snapshot.branch(),
          snapshot.parent(),
          snapshot.entityId(),
          process,
          start);
      // A clone that succeeded under a stop leaves the row STOPPED: RUNNING is written only while
      // no stop has landed, and before the provision phase reports, so the process still ends on
      // the stop rather than going on into a bootstrap chain for a container that is paused.
      if (!markRuntimeUnlessStopped(
          repoId, workspaceId, rowId, start, WorkspaceRuntimeStatus.RUNNING, null)) {
        throw yieldToStop(repoId, workspaceId, rowId);
      }
      if (process != null) {
        process.finishProvision(true);
      }
      // Cold -> RUNNING off a fresh provision (bare clone): await the bootstrap chain, which ends the
      // process (async; the runner passes straight through when the chain is empty).
      containerEvents.fireStarted(
          repoId, workspaceId, rowId, process == null ? null : process.id(), true);
      observeClientLiveness(repoId, workspaceId, rowId);
    } catch (StoppedDuringStart e) {
      throw e;
    } catch (RuntimeException e) {
      if (!markRuntimeUnlessStopped(
          repoId,
          workspaceId,
          rowId,
          start,
          WorkspaceRuntimeStatus.FAILED,
          truncate(e.getMessage()))) {
        throw yieldToStop(repoId, workspaceId, rowId);
      }
      throw e;
    }
  }

  /**
   * Record the in-container workspace-daemon's handshake liveness alongside the reconciliation
   * ladder (docs/epics/qits-workspace-daemon/) — <b>informational only</b>. It never gates a status
   * transition: a running container is decided by docker run-state and the branch ref exactly as
   * before, so a missing/broken socket (older images, a crashed binary, cli/tests with no backend
   * impl) degrades to today's behaviour. Later parts consult this once the socket drives behaviour.
   */
  private void observeClientLiveness(String repoId, String workspaceId, Long rowId) {
    if (clientLiveness.isResolvable()) {
      boolean live = clientLiveness.get().isDaemonLive(rowId);
      LOG.debugf(
          "workspace-daemon control socket for %s/%s: %s (informational; reconciliation unaffected)",
          repoId, workspaceId, live ? "present" : "not yet observed");
    }
  }

  /** @return the row written and the status it held, or null when no ACTIVE row matched */
  private RuntimeMark markRuntime(
      String repoId, String workspaceId, WorkspaceRuntimeStatus status, String error) {
    return workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .map(
            wt -> {
              RuntimeMark mark = new RuntimeMark(wt.id, wt.runtimeStatus);
              wt.runtimeStatus = status;
              wt.runtimeError = error;
              return mark;
            })
        .orElse(null);
  }

  private static String truncate(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }

  /**
   * Whether {@code branch} still exists in the repository — asked of the <b>git host</b>, with
   * {@code ls-remote}, never of the mirror.
   *
   * <p>That distinction is load-bearing rather than tidy. This answer is what {@link
   * #ensureContainer} abandons a workspace on, and a cache that is one fetch behind would report a
   * live branch as gone and destroy a workspace over it. A read against the repository of record
   * costs one round trip and cannot be wrong.
   *
   * <p>An unreachable git host <b>throws</b> rather than answering "gone", for the same reason: "I
   * could not ask" and "it is not there" were one value while the origin was a local directory, and
   * over the wire they must not be. Abandoning a workspace because a service was restarting would be
   * the worst possible reading of a transient failure.
   */
  public boolean branchExists(String repoId, String branch) {
    if (branch == null || branch.isBlank() || branch.startsWith("-")) {
      return false;
    }
    try {
      return mirrors.of(repoId).remoteHasBranch(branch);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not ask the git host about '" + branch + "': " + e.getMessage());
    }
  }


  /**
   * Gracefully pauses a workspace's container: pushes its branch to origin first (a durability
   * backstop for committed work), {@code docker stop}s the container <em>in place</em> — keeping it
   * and its {@code /workspace} clone — and marks the workspace {@code STOPPED} while leaving it
   * ACTIVE. On next access {@link #ensureContainer} resumes the same container via {@code
   * containers.start} (its {@code exists()} → {@code start()} branch), so the working tree survives
   * intact: uncommitted/untracked files and unpushed commits alike. This is a true pause, not a
   * teardown — the lossy {@link #rm} is reserved for discard (which deletes the branch afterward).
   *
   * <p><b>A stop ends the start it overtakes</b> (qits-1076): a DIRECT start still open — cloning,
   * or past its clone and running the bootstrap chain — is ended as failed once the stop has
   * committed, its provision await released, and the start yields rather than tearing down the
   * container or marking the row FAILED. See {@link #interruptStart}. The next ensure-container
   * then starts the paused container again instead of joining a start that never would.
   */
  public void stopContainer(Long id) {
    // Not @Transactional any more, and only so the TARGET branch can call qits-system outside a
    // transaction: the DIRECT body below runs in the transaction it always ran in, joined or begun.
    Workspace placed = QuarkusTransaction.joiningExisting().call(() -> requireActive(id));
    if (placed.placement == WorkspacePlacement.TARGET) {
      stopOnTarget(placed);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(placed);
    QuarkusTransaction.joiningExisting().run(() -> stopDirectContainer(id));
  }

  /** {@link #stopContainer} for a DIRECT row: the method body as it was before placement. */
  private void stopDirectContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    // Counted before anything is touched, so a start still running sees the stop whatever it reads
    // next; its process is ended once this transaction commits (qits-1076).
    interruptStart(repoId, workspaceId, workspace.id);
    // No durability push before the stop: the daemon pushes committed work as it lands, so origin
    // is current by the time we get here. A stop is a pause anyway — the container and its
    // /workspace clone survive it, so uncommitted work is not at risk either.
    // Settle the workspace's services before the container stops, so a live service's disappearance
    // reads as a deliberate STOPPED (graceful: signal + grace) instead of a crash the restart
    // policy
    // would resurrect. Synchronous — completes while the container is still running.
    containerEvents.fireStopping(
        repoId, workspaceId, workspace.id, true, liveAgentIds(workspace.id));
    containers.stop(containers.containerName(workspaceId, repoId));
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
  }

  /**
   * Best-effort graceful stop ahead of a {@link ContainerRuntime#rm}, used at every call site that
   * tears a container down. {@code containers.stop} is documented best-effort and never throws on
   * its own, but a fake or a future implementation might, and removal must never get stuck or fail
   * on account of it — so any {@link RuntimeException} is logged and swallowed here, and the caller
   * always proceeds to {@code rm}. The point is giving whatever is running inside (notably a coding
   * agent started with {@code claude --remote-control}) a SIGTERM instead of the SIGKILL a straight
   * {@code rm} delivers, since that agent only archives its claude.ai session on SIGTERM.
   */
  private void stopBeforeRemove(String container) {
    try {
      containers.stop(container);
    } catch (RuntimeException e) {
      LOG.debugf(e, "Graceful stop before removal failed for container %s; removing anyway",
          container);
    }
  }

  /**
   * Deletes a workspace's container outright ({@code docker rm}) while keeping its durable branch
   * and the ACTIVE workspace row. Where {@link #stopContainer} pauses in place (keeping the
   * container and its {@code /workspace} volume for a lossless resume) and a plain recreate now
   * <em>preserves</em> that volume, this is the one deliberate reset: it tears the container down
   * <em>and removes the persistent {@code /workspace} volume</em>, so the next {@link
   * #ensureContainer} re-creates an empty volume and re-clones a fresh checkout from the branch —
   * losing every uncommitted working-tree change and any unpushed commit, as its Shift-guarded
   * "loses uncommitted changes" contract promises. Distinct from {@link #discardWorkspace}
   * (Abandon), which additionally deletes the branch and soft-deletes the row. Settles any live
   * services first (immediate — the container is being torn down) and leaves the workspace {@code
   * STOPPED} with no runtime error. No-op-safe if the container/volume are already gone (both
   * best-effort). The container is removed before the volume (docker refuses an in-use volume).
   */
  public void deleteContainer(Long id) {
    // Not @Transactional any more, for stopContainer's reason; the DIRECT body is unchanged.
    Workspace placed = QuarkusTransaction.joiningExisting().call(() -> requireActive(id));
    if (placed.placement == WorkspacePlacement.TARGET) {
      deleteOnTarget(placed);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(placed);
    QuarkusTransaction.joiningExisting().run(() -> deleteDirectContainer(id));
  }

  /**
   * The DIRECT container's teardown, delete-container's order: settle its services (immediate), a
   * graceful stop, the {@code rm}, then the per-workspace volume. The container is removed before
   * the volume (docker refuses an in-use volume).
   */
  private void tearDownDirect(String repoId, String workspaceId, Long rowId) {
    String container = containers.containerName(workspaceId, repoId);
    containerEvents.fireStopping(repoId, workspaceId, rowId, false, liveAgentIds(rowId));
    stopBeforeRemove(container);
    containers.rm(container);
    containers.removeWorkspaceVolume(workspaceId);
  }

  /**
   * {@link #deleteContainer} for a DIRECT row: the method body as it was before placement, behind
   * the stop's {@link #interruptStart} — a start the delete overtakes is ended the same way, and
   * yields rather than marking FAILED a row the delete leaves STOPPED (qits-1076).
   */
  private void deleteDirectContainer(Long id) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    String workspaceId = workspace.workspaceId;
    interruptStart(repoId, workspaceId, workspace.id);
    tearDownDirect(repoId, workspaceId, workspace.id);
    // The row stays ACTIVE and fires no WorkspaceResolved, but the CONTAINER is gone — and the
    // token's lifetime is the container's, not the row's (qits-1084). So it goes back here too, and
    // the next ensure mints a fresh one for the container it provisions. This is the path an
    // observer on the resolution event could never have covered.
    deleteToken(clearToken(workspace));
    workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
    workspace.runtimeError = null;
  }

  /**
   * The rule that makes "this service does not write the default branch" true in the API and not
   * only at the git host.
   *
   * <p>{@code merge} and {@code integrate} keep working for every other target — landing on a
   * <em>parent</em> branch is what stacked workspaces do all day — but any of them whose target
   * resolves to the default branch is refused here, naming the flow that does write it. The default
   * branch is qits-projects' now: a release request folds its sources, releases a tag, and {@code
   * main} is finalized after the deployment. Without this guard the claim would be false in the API
   * even while true at the git host, and the git host's own hook would then refuse the write anyway:
   * a worse error, later, instead of a clear one now.
   *
   * <p>It carries {@code RELEASE_REQUIRED} rather than a bare 409, so a client can offer the right
   * button instead of word-matching prose for an endpoint name. Nothing was attempted, which is what
   * separates this from every other value in the enum.
   */
  private void refuseMainAsMergeTarget(
      RepositoryLookup.RepositoryView repo, String resolvedTarget, Long workspaceId) {
    if (!defaultMainBranch(repo).equals(resolvedTarget)) {
      return;
    }
    throw new IntegrateConflictException(
        IntegrateConflictException.Reason.RELEASE_REQUIRED,
        "'"
            + resolvedTarget
            + "' is the repository's default branch and is written by the release flow alone. Ask"
            + " qits-projects for a release request naming this branch; it lands the release as a"
            + " tag and merges the default branch once the deployment is live.");
  }

  /** The id of the ACTIVE workspace owning {@code branch}, or null — for an error message only. */
  private Long findWorkspaceIdForBranch(String repoId, String branch) {
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    return wt == null ? null : wt.id;
  }

  /**
   * Integrates an arbitrary branch into a target branch, defaulting to the repository's configured
   * main branch when {@code target} is blank. Unlike {@code mergeWorkspace} (gone since qits-1152), the source needs no
   * workspace of its own — its branch ref is merged into the target's workspace (a temporary one is
   * created and removed when the target isn't checked out anywhere).
   */
  public MergeResult mergeBranch(String repoId, String source, String target) {
    return mergeBranch(repoId, source, target, null);
  }

  @Transactional
  public MergeResult mergeBranch(String repoId, String source, String target, String result) {
    // `source`/`target` are user-supplied: reject blank or dash-leading names so a value like
    // "-D" can't be smuggled to git as a flag (argv flag injection).
    if (source == null || source.isBlank() || source.startsWith("-")) {
      throw new BadRequestException("Invalid source branch: " + source);
    }

    var repo = repositories.require(repoId);

    String resolvedTarget = (target == null || target.isBlank()) ? repo.mainBranch() : target;
    if (resolvedTarget == null || resolvedTarget.isBlank() || resolvedTarget.startsWith("-")) {
      throw new BadRequestException("Invalid target branch: " + target);
    }
    if (source.equals(resolvedTarget)) {
      throw new BadRequestException("Cannot integrate '" + source + "' into itself");
    }
    // Checked after the self-merge guard, which is a plainer error about the same request: merging
    // a branch into itself is malformed whatever the branch is, while the rule below is about which
    // door writes the default branch.
    refuseMainAsMergeTarget(repo, resolvedTarget, findWorkspaceIdForBranch(repoId, source));
    // Guard and complete the source before the origin-side merge reads its ref: refuse a dirty
    // working tree (a plain branch has none, so it is never blocked) and push any commits that live
    // only inside the source container so they aren't silently dropped from the integration.
    requireSyncedSourceForIntegration(repoId, findWorkspaceByBranch(repoId, source));

    MergeResult merged = mergeIntoTarget(repoId, source, resolvedTarget);

    // After a clean integration the source branch's commits live in the target, so when the source
    // is now safe to remove (fully merged, clean if workspace-backed, no dependents) we clean it up
    // —
    // whether it is a workspace or a plain branch.
    boolean cleanedUp = false;
    if (!merged.hasConflicts()) {
      // The integration advanced the target's origin ref; if a live workspace owns it, pull it in
      // now (before any source cleanup — target and source are distinct branches).
      notifyIncomingMerge(repoId, resolvedTarget);
      if (canCleanupBranch(repoId, source, repo.mainBranch())) {
        doCleanupBranch(repoId, source, result);
        cleanedUp = true;
      }
    }

    return new MergeResult(merged.commitHash(), merged.hasConflicts(), merged.output(), cleanedUp);
  }

  /**
   * Removes a branch (and its workspace, if any) only when it is safe to do so — fully merged,
   * clean working tree when workspace-backed, no dependent workspaces (see {@link
   * #canCleanupBranch}). Because the UI performs this without a confirmation, the safety is
   * enforced here: an ineligible branch yields a 400 and is left untouched.
   */
  public void cleanupBranch(String repoId, String branch) {
    cleanupBranch(repoId, branch, null);
  }

  @Transactional
  public void cleanupBranch(String repoId, String branch, String result) {
    var repo = repositories.require(repoId);

    if (!canCleanupBranch(repoId, branch, repo.mainBranch())) {
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' cannot be cleaned up: it has uncommitted changes, unmerged commits, or dependent"
              + " workspaces");
    }

    doCleanupBranch(repoId, branch, result);
  }

  /**
   * Deletes a branch: resolves its workspace as INTEGRATED when one is checked out (reusing the
   * discard mechanics), otherwise pushes the deletion to the git host. Callers gate on {@link
   * #canCleanupBranch}.
   */
  private void doCleanupBranch(String repoId, String branch, String result) {
    Workspace wt = findWorkspaceByBranch(repoId, branch);
    if (wt != null) {
      doDiscard(repoId, wt, WorkspaceStatus.INTEGRATED, result);
      return;
    }
    try {
      PushOutcome deleted = mirrors.of(repoId).deleteBranch(branch);
      if (!deleted.accepted()) {
        throw new InternalServerErrorException(
            "Failed to delete branch '" + branch + "': " + deleted.output());
      }
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Failed to delete branch '" + branch + "': " + e.getMessage());
    }
  }

  /** One repository the sweep is asked to examine — the caller's catalogue row, not a lookup. */
  public record SweepRepository(String id, String name, String mainBranch) {}

  /** One branch the sweep removed (or, on a dry run, would remove). */
  public record SweptBranch(String repositoryId, String repositoryName, String branch) {}

  /** One branch (or whole repository) the sweep could not judge; the sweep went on without it. */
  public record SweepError(String repositoryId, String branch, String error) {}

  /**
   * @param removed what was deleted — or, on a dry run, what would have been
   * @param errors what could not be judged or deleted; never a reason to stop the rest
   */
  public record BranchSweepReport(
      boolean dryRun,
      int repositoriesExamined,
      int branchesExamined,
      List<SweptBranch> removed,
      List<SweepError> errors) {}

  /**
   * The nightly half of branch cleanup: remove every <b>plain</b> branch that is fully merged —
   * the refs the branch dropdowns collect after a release deletes a workspace but an ad-hoc push,
   * an abandoned epic or a typo left its branch behind.
   *
   * <p><b>What it never touches, and why each refusal is hard-coded here</b> rather than left to
   * configuration:
   *
   * <ul>
   *   <li><b>The repository's main branch</b> — {@link #canCleanupBranch} refuses it too; belt and
   *       braces for the ref everything else forks from.
   *   <li><b>Workspace-backed branches</b> — {@link #cleanupBranch} deletes the workspace along
   *       with the branch, which is right under a human's click and wrong overnight: a freshly
   *       provisioned workspace with nothing committed yet is "fully merged", and its owner would
   *       find their container gone by morning. An automated run removes only what no active
   *       workspace stands on.
   *   <li><b>An ACTIVE agent's wrapper branch</b> (qits-1232) — the same reasoning, for the table
   *       that replaced it: a dispatched agent's wrapper branch is pushed at main's head and reads
   *       as fully merged the instant it exists, long before the agent has done anything to
   *       release it. {@link #hasActiveAgentWrapperBranch} is read beside {@link
   *       #findWorkspaceByBranch} because agents live in {@code agent_worktree}, a table that
   *       lookup never sees.
   * </ul>
   *
   * <p>Everything that survives those refusals still goes through {@link #canCleanupBranch} — the
   * same single criterion the UI and the endpoint use, mirror-refreshed per question — and then
   * {@link #cleanupBranch}, which re-checks under its transaction. A branch or repository this
   * cannot judge (an unreachable mirror, a race with a concurrent delete) is recorded and skipped:
   * a sweep is a best-effort pass over the whole estate, and one broken repository must not keep
   * every other one's refs.
   */
  public BranchSweepReport sweepMergedBranches(
      List<SweepRepository> repositories, Set<String> keepPrefixes, boolean dryRun) {
    List<SweptBranch> removed = new ArrayList<>();
    List<SweepError> errors = new ArrayList<>();
    // Every prefix kept is the caller's now. `environment/` used to be hard-coded here because a
    // release promoted its sha onto the tier's deploy ref, which made those branches fully merged
    // by construction — exactly the shape this sweep condemns. Nothing writes them any more:
    // deployment follows the release event, not a ref, so a surviving environment/* branch is a
    // leftover and the sweep may take it. A deployment that still wants one kept passes it.
    Set<String> protectedPrefixes = new java.util.HashSet<>(keepPrefixes == null ? Set.of() : keepPrefixes);
    int repositoriesExamined = 0;
    int branchesExamined = 0;

    for (SweepRepository repository : repositories == null ? List.<SweepRepository>of() : repositories) {
      if (repository == null || repository.id() == null || repository.id().isBlank()) {
        continue;
      }
      List<String> branches;
      try {
        branches = mirrors.of(repository.id()).remoteBranches();
      } catch (RuntimeException e) {
        errors.add(new SweepError(repository.id(), null, e.getMessage()));
        continue;
      }
      repositoriesExamined += 1;
      for (String branch : branches) {
        branchesExamined += 1;
        if (branch.isBlank()
            || branch.equals(repository.mainBranch())
            || protectedPrefixes.stream().anyMatch(branch::startsWith)
            || findWorkspaceByBranch(repository.id(), branch) != null
            || hasActiveAgentWrapperBranch(repository.id(), branch)) {
          continue;
        }
        try {
          if (!canCleanupBranch(repository.id(), branch, repository.mainBranch())) {
            continue;
          }
          if (!dryRun) {
            cleanupBranch(repository.id(), branch);
          }
          removed.add(new SweptBranch(repository.id(), repository.name(), branch));
        } catch (RuntimeException e) {
          errors.add(new SweepError(repository.id(), branch, e.getMessage()));
        }
      }
    }
    return new BranchSweepReport(dryRun, repositoriesExamined, branchesExamined, removed, errors);
  }

  /**
   * Merges {@code sourceBranch} into {@code resolvedTarget}, in a detached worktree on the
   * repository's <b>mirror</b>, and <b>pushes</b> the result.
   *
   * <p>This is the call site the whole de-filesystem change is about. It used to add a worktree on
   * the bare origin qits-artifacts serves and let the merge advance the target's ref there, with no
   * push — which is why <b>no merge this service ever performed produced a CI run</b>. Nothing about
   * the merge changed; where it happens and how the result arrives did.
   *
   * <p>No push option: {@code refuseMainAsMergeTarget} has already established that the target is
   * not the default branch, so this is an ordinary push through an unguarded ref. It is still a
   * compare-and-swap — fast-forward-only belongs to receive-pack, not to an option.
   */
  private MergeResult mergeIntoTarget(String repoId, String sourceBranch, String resolvedTarget) {
    RepoMirror mirror = mirrors.of(repoId);
    try {
      mirror.refreshNow();
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException(
          "Could not read the repository from the git host: " + e.getMessage());
    }
    // Named after the source branch, which is unique per repository by construction — the
    // `.tmp-merge-<currentTimeMillis>` this replaces collided within a millisecond.
    try (MirrorWorktree worktree =
        mirror.worktree(sourceBranch, "refs/heads/" + resolvedTarget)) {
      // The one host-spawned synthetic commit. Identity is delivered both as -c (explicit in the
      // argv) AND as GIT_AUTHOR_*/GIT_COMMITTER_* env scoped to this invocation — the env form is
      // what actually guarantees attribution, because an ambient identity env inherited from the
      // host would otherwise outrank the -c config.
      MergeOutcome merged =
          worktree.mergeAndCommit(
              "refs/heads/" + sourceBranch,
              "Merge " + sourceBranch + " into " + resolvedTarget,
              gitIdentity.forMirror());
      if (!merged.clean()) {
        // Answered rather than thrown: a conflict is an outcome of a merge, not an error.
        return new MergeResult(null, true, merged.output(), false);
      }
      String commitHash = worktree.headSha();
      PushOutcome pushed =
          worktree.push(PushSpec.of(PushSpec.Ref.branch("HEAD", resolvedTarget)));
      if (!pushed.accepted()) {
        throw new InternalServerErrorException(
            "The merge was built but the push was refused: " + pushed.output());
      }
      return new MergeResult(commitHash, false, merged.output(), false);
    } catch (GitMirrorException e) {
      throw new InternalServerErrorException("Git merge failed: " + e.getMessage());
    }
  }


  public void discardWorkspace(Long id) {
    discardWorkspace(id, null);
  }

  public void discardWorkspace(Long id, String result) {
    discardWorkspace(id, result, false);
  }

  /**
   * Abandon the workspace. The clean-tree guard is the default and stays the API's posture; {@code
   * force} is the person-in-front-of-a-dialog override — the discard UI shows what will be thrown
   * away and asks twice, and only that confirmed press sends {@code true}. Discard is already
   * deliberately lossy (container, volume and branch all go); what force skips is only the refusal
   * to lose work the daemon still reports as uncommitted.
   */
  @Transactional
  public void discardWorkspace(Long id, String result, boolean force) {
    Workspace workspace = requireActive(id);
    String repoId = workspace.repositoryId;
    repositories.require(repoId);

    if (!force) {
      requireCleanWorkingTree(workspace, "abandon");
    }
    doDiscard(repoId, workspace, WorkspaceStatus.ABANDONED, result);
  }

  /**
   * What {@link #resolveReleasedBranch} answers: whether a workspace was standing on the released
   * branch, and the row id of the one that was. {@code resolved:false} with no id is the ordinary
   * answer — most released branches never carried a workspace — and it is the answer a second call
   * gets too, which is what makes a best-effort caller free to retry.
   */
  public record BranchResolution(boolean resolved, Long workspaceId) {}

  /**
   * Resolve the workspace standing on a branch a <b>release has just deleted</b> on the git host, as
   * {@code INTEGRATED}.
   *
   * <p><b>Why this door exists.</b> qits-projects' Auto Release deletes each released branch through
   * a qits-githost primitive that writes the ref in core and fires no event at all, so nothing here
   * ever learns the branch is gone. The workspace standing on it therefore stays ACTIVE forever,
   * holding a container, a volume and a commissioned credential for a branch that no longer exists,
   * until somebody notices and abandons it by hand — measured live on 2026-09-05 on the
   * storage-creep wrapper workspace. So the release says so, beside the deletion it just made.
   *
   * <p><b>This is a workspace-lifecycle door that a release happens to call, and it is deliberately
   * not a release door.</b> The release flow left this service on 2026-09-03 and stays gone
   * (AGENTS.md, "The release door left, and what stayed"): nothing here merges, stamps, bumps, tags,
   * pushes or announces, and no events module comes back for it. What arrives is a fact about a
   * <em>branch</em> — it is gone, and this version consumed it — and what happens is the resolution
   * this service already performs whenever a workspace's branch stops existing.
   *
   * <p><b>No workspace is the normal answer, not an error.</b> Most released branches carry none, so
   * an absent one answers {@code resolved:false} and touches nothing. That also makes a second call
   * after a resolution a no-op, which the caller — best-effort, and free to retry — needs.
   *
   * <p><b>The main workspace is refused on both belts.</b> A row whose {@code parent} is null (what
   * the retired per-project editor's main workspaces carry, and nothing else does), and a branch
   * equal to the repository's default branch, are two independent readings of the same fact: the
   * first is this service's own record, the second is qits-projects'. Both are checked, because a
   * main branch renamed between the two would leave exactly one of them right, and the cost of being
   * wrong is the workspace a whole repository is worked in. An ordinary discard still resolves such
   * a row, but {@link #doDiscard} keeps its branch on the same two belts (qits-780).
   *
   * <p><b>An agent's leftover work is LOGGED and never refused.</b> This is the one place this
   * service knowingly discards uncommitted work, and it is still the better answer: the branch is
   * gone, so there is nothing left to push to and no ref the work could be recovered from, while the
   * alternative is an immortal ACTIVE workspace on a deleted branch holding a container and a
   * credential nobody will reclaim. The WARN names the workspace and says a release consumed its
   * branch, so the loss is at least on the record. The probe is the agents' cleanup check ({@link
   * #isWorkspaceClean}), which already treats an agent it cannot check as holding work.
   *
   * <p>The teardown is {@link #doDiscard}'s with the branch deletion skipped, and it is deliberately
   * <b>not</b> routed through {@link #cleanupBranch}/{@link #canCleanupBranch}/{@link
   * #sweepMergedBranches}: those decide whether a branch <em>may</em> be deleted and fail closed on
   * a ref they cannot resolve. Here the deletion has already happened, by somebody entitled to make
   * it, so every premise they hold is inverted.
   *
   * @param target the release version the branch was consumed by, recorded on the history event
   * @param commit the released sha, recorded on the same event
   */
  @Transactional
  public BranchResolution resolveReleasedBranch(
      String repoId, String branch, String target, String commit, String result) {
    if (branch == null || branch.isBlank()) {
      throw new BadRequestException("A branch is required to resolve a released branch.");
    }
    Optional<Workspace> standing =
        workspaceRepository.findActiveByRepositoryAndBranch(repoId, branch);
    if (standing.isEmpty()) {
      // The registry is not asked at all on this path, and that is deliberate: a release calls this
      // once per branch it deletes, and the answer for most of them is "nothing here" — which must
      // not cost a qits-projects round trip, nor fail when qits-projects is away.
      return new BranchResolution(false, null);
    }
    Workspace workspace = standing.get();

    // Belt one: this service's own record of what a main workspace is.
    if (workspace.parent == null || workspace.parent.isBlank()) {
      throw new BadRequestException(
          "Workspace '"
              + workspace.workspaceId
              + "' has no parent branch, which is what makes it the repository's main workspace: a"
              + " release does not resolve it.");
    }
    // Belt two: qits-projects' record of the same thing, read independently.
    RepositoryLookup.RepositoryView repo = repositories.require(repoId);
    if (branch.equals(defaultMainBranch(repo))) {
      throw new BadRequestException(
          "Branch '"
              + branch
              + "' is the repository's default branch, so the workspace on it is the main workspace:"
              + " a release does not resolve it.");
    }

    if (!isWorkspaceClean(workspace)) {
      LOG.warnf(
          "Resolving workspace %s (row %s, branch '%s') although an agent on its container holds"
              + " uncommitted, unpushed or uncheckable work: a release consumed the branch, so"
              + " there is nothing left to push to.",
          workspace.workspaceId, workspace.id, branch);
    }

    doDiscard(repoId, workspace, WorkspaceStatus.INTEGRATED, result, target, commit, false);
    return new BranchResolution(true, workspace.id);
  }

  /**
   * Removes a workspace from disk and deletes its branch, then <em>soft-deletes</em> the row: it is
   * marked with its {@code resolution} status ({@code INTEGRATED} for cleanup, {@code ABANDONED}
   * for discard) and kept as a persistent record (with its history events and the commands that ran
   * in it) rather than deleted. The on-disk metadata file is removed so discovery won't re-process
   * it.
   */
  private void doDiscard(
      String repoId, Workspace workspace, WorkspaceStatus resolution, String result) {
    doDiscard(repoId, workspace, resolution, result, null, null);
  }

  /**
   * {@link #doDiscard(String, Workspace, WorkspaceStatus, String)} with the branch and commit the
   * resolution refers to, recorded on the history event. Integrate is the caller that has both — the
   * default branch it released into and the merge commit's sha — and a resolution event that names
   * neither is a timeline entry a person cannot follow back to the release.
   */
  private void doDiscard(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit) {
    doDiscard(repoId, workspace, resolution, result, target, commit, true);
  }

  /**
   * The teardown itself, with one thing made optional: whether the branch is <b>deleted</b> as part
   * of it.
   *
   * <p>{@code deleteBranch} is false for exactly one caller — {@link #resolveReleasedBranch}, where
   * a release has already deleted the ref on the git host — and the flag exists rather than a second
   * copy of this sequence because the ORDER is the part worth having one of. {@code fireStopping}
   * before {@code containers.rm} is pinned by an observer (a service settling after its container is
   * gone reads as a crash to be resurrected), the container goes before its volume (docker refuses
   * an in-use volume), and the credential goes back beside the {@code rm} rather than on the event
   * this method fires. A duplicate would be free to drift on every one of them.
   *
   * <p>Skipping the deletion is not merely an optimisation. The push would be a wire round trip
   * whose only possible outcome on an absent ref is the failure the catch below swallows — so the
   * flag turns a silent no-op into a stated one, and the same reading {@link #ensureContainer}'s
   * branch-gone abandon already makes one hop earlier.
   */
  private void doDiscard(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit,
      boolean deleteBranch) {
    boolean deletesBranch = deleteBranch && !keepsBranch(repoId, workspace);
    if (workspace.placement == WorkspacePlacement.TARGET) {
      discardOnTarget(repoId, workspace, resolution, result, target, commit, deletesBranch);
      return;
    }
    WorkspacePlacements.requireDirectAllowed(workspace);
    try {
      String branch = workspace.branch;

      // Remove the workspace's container AND its persistent /workspace volume. Discard is
      // intentionally lossy: unlike the graceful stopContainer (which docker-stops in place so the
      // container and its /workspace volume survive), here we delete the container, its volume, AND
      // the branch right after, so preserving /workspace would be pointless — the operator asked to
      // throw this work away. Container first, then the volume (docker refuses an in-use volume).
      // Settle any live services first (immediate — no graceful signal, the work is being
      // discarded)
      // so their disappearance doesn't read as a crash to be resurrected.
      //
      // The container itself still gets a best-effort docker stop ahead of the rm, though, and for
      // a reason that has nothing to do with services: a coding agent running inside (claude
      // --remote-control) only archives its claude.ai session on SIGTERM, and an rm straight to
      // SIGKILL leaves that session dangling. stopBeforeRemove gives it the chance; the rm follows
      // regardless of whether the stop lands.
      containerEvents.fireStopping(
          repoId, workspace.workspaceId, workspace.id, false, liveAgentIds(workspace.id));
      stopBeforeRemove(containers.containerName(workspace.workspaceId, repoId));
      containers.rm(containers.containerName(workspace.workspaceId, repoId));
      containers.removeWorkspaceVolume(workspace.workspaceId);
      // The workspace token an admin or editor row on an edge plane holds (qits-1084) dies with the
      // container, so it goes back here — beside the rm, not on the WorkspaceResolved event this
      // method fires below.
      //
      // An observer on that event would be the tidier-looking seam and would be wrong twice. It
      // covers the resolution paths and NOT deleteContainer, which removes a container while the row
      // stays ACTIVE and fires nothing — so one mechanism would still need a second call site, and
      // two mechanisms for one rule is how they drift apart. And the event is fired synchronously
      // inside the resolving transaction so observers can join it, which is the one place this HTTP
      // call must not be. Beside containers.rm — itself an HTTP call, best-effort for the same
      // reason — is where the container's other teardown already sits.
      deleteToken(clearToken(workspace));

      if (deletesBranch && branch != null && !branch.isBlank()) {
        try {
          mirrors.of(repoId).deleteBranch(branch);
        } catch (GitMirrorException ignored) {
          // the branch may already be gone, and the resolution is not conditional on the ref
        }
      }

      workspace.status = resolution;
      workspace.resolvedAt = Instant.now();
      if (result != null && !result.isBlank()) {
        workspace.result = result;
      }
      recordEvent(
          workspace,
          resolution == WorkspaceStatus.INTEGRATED
              ? WorkspaceEventType.INTEGRATED
              : WorkspaceEventType.ABANDONED,
          branch,
          target,
          commit);
      // Pre-launch composition state (prompt drafts, their attachments) is not a durable record
      // like the history events, and its FK cascade never fires because the workspace row is only
      // soft-deleted. Whoever owns those tables drops them on this event, in this transaction.
      workspaceResolvedEvent.fire(
          new WorkspaceResolved(repoId, workspace.workspaceId, workspace.id, resolution));
      workspaceMetadata.delete(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  /**
   * Whether a resolution must leave this workspace's branch on the git host: <b>a main workspace's
   * branch is never deleted</b> (qits-780). Two independent readings, as {@link
   * #resolveReleasedBranch} makes them: a row with no {@code parent} (what the retired per-project
   * editor's main workspaces carry, and nothing else), and a branch equal to the repository's
   * default branch. A repository the registry cannot name keeps the branch too, since deleting is
   * the direction this has to fail away from. Each keep is logged.
   */
  private boolean keepsBranch(String repoId, Workspace workspace) {
    String branch = workspace.branch;
    if (branch == null || branch.isBlank()) {
      return false;
    }
    if (workspace.parent == null || workspace.parent.isBlank()) {
      LOG.infof(
          "Workspace %s (row %s) has no parent branch, so it is a main workspace: its branch '%s'"
              + " is kept on the git host",
          workspace.workspaceId, workspace.id, branch);
      return true;
    }
    Optional<RepositoryLookup.RepositoryView> repo;
    try {
      repo = repositories.find(repoId);
    } catch (RuntimeException e) {
      repo = Optional.empty();
    }
    if (repo.isEmpty()) {
      LOG.warnf(
          "Repository %s could not be read, so workspace %s (row %s) keeps its branch '%s' on the"
              + " git host",
          repoId, workspace.workspaceId, workspace.id, branch);
      return true;
    }
    if (branch.equals(defaultMainBranch(repo.get()))) {
      LOG.infof(
          "Branch '%s' of workspace %s (row %s) is the repository's default branch: it is kept on"
              + " the git host",
          branch, workspace.workspaceId, workspace.id);
      return true;
    }
    return false;
  }

  // --- TARGET placement (epic qits-1481, qits-1532) ---------------------------------------------
  //
  // Each container verb above branches ONCE, at its top, on Workspace.placement, and a TARGET row
  // comes here. Nothing below calls qits-containers: a TARGET row's container runs in a WORKSPACE
  // slot of an execution target, under a lease qits-system grants (TargetPlacement), and its
  // persisted runtime status is its lease's word (TargetLeaseStates). Its start builds the edge
  // address plane (qits-799), mints the lease's workspace token (qits-802) and composes the spec
  // before it asks for the lease, and fails the row when it can have none of them.

  /**
   * Start, for a TARGET row: ask qits-system for a WORKSPACE lease.
   *
   * <ul>
   *   <li>already RUNNING, PROVISIONING or QUEUED → nothing to do. The start process still open for
   *       it is answered, so a second press joins the first; a QUEUED row's process says why it
   *       waits (the lease's {@code detail});
   *   <li>the durable branch is gone → abandoned and 404, as rung 3 of the DIRECT ladder does;
   *   <li>no edge address plane ({@link EdgePlaneUnconfigured}: no public {@code QITS_DOMAIN}) →
   *       FAILED with {@code EDGE_PLANE_UNCONFIGURED} as its runtime error, and no lease;
   *   <li>a previous lease still names the row (EXITED, FAILED or ENDING) → it is ended and waited
   *       for, up to {@link TargetPlacement#ENDING_DEADLINE}, else 504 {@code TARGET_TIMEOUT}:
   *       qits-system holds one live lease per owner ref;
   *   <li>no workspace token could be minted → FAILED with {@code WORKSPACE_TOKEN_UNAVAILABLE} and
   *       the reason, and no lease ({@link #mintLeaseToken}): the container reaches every hop with
   *       that token;
   *   <li>qits-system refused the request itself (a 4xx: the image is not a workspace image, this
   *       service is not a WORKSPACE tenant) → FAILED with {@code TARGET_REFUSED} and its answer;
   *       did not answer, or answered 5xx or 409 → 409 {@code TARGET_UNAVAILABLE} and the row as it
   *       was. Either way the token minted for it is deleted;
   *   <li>otherwise the lease is recorded on the row, QUEUED with {@code queuedAt} now and the
   *       lease's {@code detail} as its runtime error, and {@link TargetLeaseStates} takes it from
   *       there.
   * </ul>
   *
   * <p><b>A row a retired runner held</b> ({@code legacy_placement}, {@code V30}) asks with {@code
   * adopt{container, volume}} = {@code qits-workspaces-runner-ws-<rowId>-<oldRunnerId8>} and {@code
   * pinTargetId} the target on the same docker engine ({@link #legacyPin}); the adopted name stays
   * on the row ({@code adopted_volume}) and every later lease carries it.
   */
  private String beginTargetStart(Workspace row) {
    return beginTargetStart(row, true);
  }

  /**
   * {@link #beginTargetStart(Workspace)}, with or without its technical process. A create starts its
   * row through here unnarrated ({@code narrate} false): no process is opened and none is answered,
   * and the row's runtime status is the whole of its progress (qits-853).
   */
  private String beginTargetStart(Workspace row, boolean narrate) {
    // Only a row that holds a lease is already started. A QUEUED row with no lease waits for the
    // target that holds its legacy volume: a start (and every reconcile, startWaitingLegacyRows)
    // asks again whether that target is there now. A RUNNING or PROVISIONING row with no lease is
    // one a retired runner held, whose container is gone (qits-1592): it asks for its lease.
    if (row.leaseId != null
        && (row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
            || row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING
            || row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED)) {
      Optional<WorkspaceProcessTracker.Handle> open = leaseStates.trackedStart(row.id);
      if (open.isPresent()) {
        return narrate ? open.get().id() : null;
      }
      WorkspaceProcessTracker.Handle process =
          narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
      if (process != null) {
        process.completeNoOp(
            TargetLeaseStates.QUEUED_SEGMENT,
            row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED
                    && row.runtimeError != null
                    && !row.runtimeError.isBlank()
                ? "Waiting for a workspace slot: " + row.runtimeError
                : "Already " + row.runtimeStatus.name().toLowerCase() + " — nothing to do.");
      }
      return process == null ? null : process.id();
    }
    // A slot (qits-1152) names no branch, so only an old-model row can have lost its own.
    if (row.branch != null && !row.branch.isBlank() && !branchExists(row.repositoryId, row.branch)) {
      abandonTargetRow(row);
    }
    // The edge plane, BEFORE anything is minted or leased: a deployment with no public domain has
    // no address a slot's container could reach home by, so the row goes FAILED saying so.
    WorkspaceAddressPlane plane;
    try {
      plane = addressPlanes.plane();
    } catch (EdgePlaneUnconfigured refused) {
      return failUnplaceable(row, refused.getMessage(), narrate);
    }
    if (!targetPlacement.isResolvable()) {
      throw TargetRefusals.unavailable(row.id, "start", "no lease client is configured");
    }
    TargetPlacement leases = targetPlacement.get();
    // qits-system holds one live lease per owner ref: a previous one has to be over first.
    retirePreviousLease(row, leases, "start");
    // A row a retired runner held is adopted only on the target that holds its volume: with none
    // installed yet it waits, asking for no lease and minting no token (legacyPin).
    LegacyPin legacy = legacyPin(row.id, leases);
    if (legacy.waitingFor() != null) {
      return waitForLegacyTarget(row, legacy.waitingFor(), narrate);
    }
    // The lease's token, minted immediately before the lease is asked for and outside every
    // transaction (an HTTP call to qits-idp). A token a previous container held is given back first.
    String unminted = mintLeaseToken(row);
    if (unminted != null) {
      return failUnplaceable(row, unminted, narrate);
    }
    Workspace current = QuarkusTransaction.requiringNew().call(() -> requireActive(row.id));
    TargetPlacement.LeaseRequest request;
    String adopted;
    try {
      adopted = legacy.volumeGone() ? null : adoptedVolume(current);
      request =
          new TargetPlacement.LeaseRequest(
              current.id,
              projectUuid(repositoryOf(current.repositoryId)),
              leaseSpecs.compose(current, plane),
              adopted == null ? null : new TargetPlacement.Adopt(adopted, adopted),
              legacy.targetId());
    } catch (RuntimeException uncomposable) {
      decommissionFor(row.id);
      return failUnplaceable(
          row, "the lease spec could not be composed: " + uncomposable.getMessage(), narrate);
    }

    WorkspaceProcessTracker.Handle process =
        narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
    if (process != null) {
      process.openSegment(TargetLeaseStates.QUEUED_SEGMENT);
      process.appendLine(TargetLeaseStates.QUEUED_SEGMENT, "Asking for a workspace slot.");
      // Carried BEFORE the lease is asked for, so a grant that arrives the instant it is made finds
      // the process to settle.
      leaseStates.track(row.id, process);
    }
    // The daemon's report is expected from here on, not from the RUNNING this service observes
    // later (qits-1600): a container restarted over an existing /workspace reports Provisioned
    // within seconds, and an await registered only at RUNNING missed it and failed the row at the
    // provision timeout. Before the lease is asked for, since no daemon can dial home without one.
    WorkspaceDaemonProvisioner.ExpectedProvision provision = leaseStates.expectProvision(row.id);
    TargetPlacement.Lease lease;
    try {
      lease = leases.create(request);
    } catch (TargetPlacement.LeaseRefused refused) {
      leaseStates.forget(row.id, process);
      leaseStates.forgetProvision(row.id, provision);
      decommissionFor(row.id);
      if (refused.permanent()) {
        return failUnplaceableWith(
            row, TARGET_REFUSED + ": " + refused.getMessage(), process, narrate);
      }
      if (process != null) {
        process.completeNoOp(TargetLeaseStates.QUEUED_SEGMENT, refused.getMessage());
      }
      throw TargetRefusals.unavailable(row.id, "start", refused.getMessage());
    } catch (RuntimeException unasked) {
      leaseStates.forgetProvision(row.id, provision);
      throw unasked;
    }
    leaseStates.expectStart(lease.id());
    int recorded =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository.recordLease(
                        row.id,
                        lease.id(),
                        lease.targetId(),
                        truncate(lease.detail()),
                        adopted,
                        Instant.now()));
    if (recorded == 0) {
      // Something else moved the row between the read and the write: a stop, a second start, a
      // resolution. This lease is nobody's.
      LOG.infof(
          "Workspace %s/%s moved while its lease %s was asked for; the lease is ended",
          row.repositoryId, row.workspaceId, lease.id());
      leaseStates.forget(row.id, process);
      leaseStates.forgetProvision(row.id, provision);
      endQuietly(leases, lease.id(), row.id);
      if (process != null) {
        process.completeNoOp(
            TargetLeaseStates.QUEUED_SEGMENT,
            "The workspace is not stopped any more — nothing to start.");
      }
      return process == null ? null : process.id();
    }
    if (process != null && lease.detail() != null && !lease.detail().isBlank()) {
      process.appendLine(TargetLeaseStates.QUEUED_SEGMENT, "Waiting: " + lease.detail());
    }
    LOG.infof(
        "Workspace %s/%s asked for lease %s (%s)",
        row.repositoryId, row.workspaceId, lease.id(), lease.state());
    changePublisher.runtimeChanged(row.repositoryId, row.id);
    // The lease may have moved before the row named it: apply what it says now, and once more from
    // a fresh read, so a grant that raced the write is not left for the reconcile.
    leaseStates.observe(lease);
    try {
      leases.get(lease.id()).ifPresent(leaseStates::observe);
    } catch (RuntimeException unread) {
      LOG.debugf("Could not re-read lease %s; its event or the reconcile will", lease.id());
    }
    return process == null ? null : process.id();
  }

  /**
   * A TARGET start refused before a lease: the row FAILED with the refusal as its runtime error
   * ({@code EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN '<v>' is not a public domain}, {@code
   * WORKSPACE_TOKEN_UNAVAILABLE: …} or {@code TARGET_REFUSED: …}), the start's process — when it is
   * narrated — failed with one {@code container} segment saying the same, and no lease.
   */
  private String failUnplaceable(Workspace row, String reason, boolean narrate) {
    return failUnplaceableWith(
        row, reason, narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null, narrate);
  }

  private String failUnplaceableWith(
      Workspace row, String reason, WorkspaceProcessTracker.Handle open, boolean narrate) {
    LOG.errorf(
        "Workspace %s/%s cannot be placed on a target: %s",
        row.repositoryId, row.workspaceId, reason);
    boolean failed =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findByIdOptional(row.id)
                        .map(
                            w -> {
                              boolean was = w.runtimeStatus == WorkspaceRuntimeStatus.FAILED;
                              w.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
                              w.runtimeError = truncate(reason);
                              w.queuedAt = null;
                              return !was;
                            })
                        .orElse(false));
    changePublisher.runtimeChanged(row.repositoryId, row.id);
    if (failed) {
      announceRuntime(row.id, row.repositoryId, WorkspaceRuntimeStatus.FAILED, truncate(reason));
    }
    WorkspaceProcessTracker.Handle process = narrate ? open : null;
    if (process == null) {
      return null;
    }
    process.openSegment(TargetLeaseStates.CONTAINER_SEGMENT);
    process.appendLine(TargetLeaseStates.CONTAINER_SEGMENT, reason);
    process.settleSegment(TargetLeaseStates.CONTAINER_SEGMENT, false);
    process.failProvision(reason);
    return process.id();
  }

  /** The code a TARGET row's runtime error starts with when no workspace token could be had. */
  static final String TOKEN_UNAVAILABLE = "WORKSPACE_TOKEN_UNAVAILABLE";

  /** The code a TARGET row's runtime error starts with when qits-system refused its lease. */
  static final String TARGET_REFUSED = "TARGET_REFUSED";

  /**
   * Mints the workspace token the next lease of {@code row} carries (qits-625, qits-802, epic
   * qits-1481), answering null when the row holds it, else the reason it does not, as a runtime
   * error.
   *
   * <p><b>One token per lease.</b> The dossier's rule for every slot type: the owner mints the
   * lease's token immediately before {@code POST /system/api/leases} and deletes it as soon as the
   * lease reports ENDED, EXITED or FAILED ({@link TargetLeaseStates}). A token the row still holds
   * here belongs to no live lease — a stopped legacy container's, or one a crash left — and is given
   * back before the new one is minted.
   *
   * <p><b>Refused, not degraded.</b> The container has no other credential, so no issuer, an issuer
   * that stayed unreachable through the commissioner's patience, or an empty answer all fail the
   * start. The kind is {@code workspace} (a TARGET row is never admin), the context the row id, the
   * claims the row's project, and {@code gitRefs: []}: every push is an agent's, on the agent's
   * token.
   */
  private String mintLeaseToken(Workspace row) {
    Long rowId = row.id;
    decommissionFor(rowId);
    if (!commissioner.isResolvable()) {
      return TOKEN_UNAVAILABLE + ": no issuer is wired to mint the workspace token";
    }
    RepositoryLookup.RepositoryView repository = repositoryOf(row.repositoryId);
    Optional<WorkspaceToken> issued;
    try {
      issued = mintToken(rowId, repository, List.of(), false);
    } catch (RuntimeException failed) {
      return TOKEN_UNAVAILABLE + ": " + failed.getMessage();
    }
    if (issued.isEmpty()) {
      return TOKEN_UNAVAILABLE + ": no issuer is configured to mint the workspace token";
    }
    WorkspaceToken token = issued.get();
    if (!storeToken(rowId, token)) {
      // Another start stored a token first, or the row resolved meanwhile: this one is nobody's.
      deleteToken(token.tokenId());
      return TOKEN_UNAVAILABLE + ": the workspace is no longer active, or is starting already";
    }
    LOG.debugf(
        "Minted workspace token %s for workspace %s/%s",
        token.tokenId(), row.repositoryId, row.workspaceId);
    return null;
  }

  /**
   * Ends the lease {@code row} still names, if any, and waits until qits-system reports it ENDED,
   * then clears it off the row with its token. 504 {@code TARGET_TIMEOUT} when it is still ending
   * after {@link TargetPlacement#ENDING_DEADLINE}; 409 {@code TARGET_UNAVAILABLE} when qits-system
   * cannot be asked.
   */
  private void retirePreviousLease(Workspace row, TargetPlacement leases, String verb) {
    UUID leaseId =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findByIdOptional(row.id).map(w -> w.leaseId).orElse(null));
    if (leaseId == null) {
      return;
    }
    Instant deadline = Instant.now().plus(TargetPlacement.ENDING_DEADLINE);
    try {
      Optional<TargetPlacement.Lease> now = leases.end(leaseId);
      while (now.isPresent() && now.get().state().live()) {
        if (Instant.now().isAfter(deadline)) {
          throw TargetRefusals.timeout(row.id, verb);
        }
        sleepQuietly(Duration.ofMillis(500));
        now = leases.get(leaseId);
      }
    } catch (TargetPlacement.LeaseRefused refused) {
      throw TargetRefusals.unavailable(row.id, verb, refused.getMessage());
    }
    leaseStates.observe(TargetLeaseStates.ended(leaseId));
  }

  /**
   * The adopted name every lease of {@code row} carries: the one on the row, or — for its first
   * lease — the one its {@code legacy_placement} entry names. Null for a row no retired runner held.
   */
  private String adoptedVolume(Workspace row) {
    if (row.adoptedVolume != null && !row.adoptedVolume.isBlank()) {
      return row.adoptedVolume;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                legacyPlacements
                    .findByIdOptional(row.id)
                    .map(eu.wohlben.qits.workspaces.entity.LegacyPlacement::legacyName)
                    .orElse(null));
  }

  /**
   * Where a row a retired runner held may be adopted ({@code legacy_placement}, {@code V30}).
   *
   * @param targetId the target on the old runner's docker engine, or null: no entry, or a volume
   *     known gone
   * @param waitingFor the old runner's name while no target on its node is installed: the row
   *     waits rather than starting fresh elsewhere and stranding its volume
   * @param volumeGone the entry names no runner at all (its runner row was gone when {@code V30}
   *     copied it), so no node can hold the volume: the lease is asked for unpinned and adopts
   *     nothing, and the entry is deleted
   */
  private record LegacyPin(UUID targetId, String waitingFor, boolean volumeGone) {
    static final LegacyPin NONE = new LegacyPin(null, null, false);
  }

  /**
   * The target on the docker engine a retired runner ran {@code rowId} on, for its adopting lease's
   * {@code pinTargetId}: the target whose engine id is the one the runner recorded, else whose host
   * name is, else whose name is the runner's name. {@link LegacyPin#NONE} for a row with no {@code
   * legacy_placement} entry.
   *
   * <p><b>No match is a wait, never an unpinned lease</b>: a lease granted anywhere else would
   * start the workspace fresh and strand its volume on the old node. The row waits QUEUED until a
   * target on that node is installed ({@link #startWaitingLegacyRows}), and so does a row whose
   * targets could not be read. Only an entry that names no runner at all — the old runner's row was
   * already gone, so nothing records where the volume was — is asked for unpinned, adopting
   * nothing.
   */
  private LegacyPin legacyPin(Long rowId, TargetPlacement leases) {
    eu.wohlben.qits.workspaces.entity.LegacyPlacement entry =
        QuarkusTransaction.requiringNew()
            .call(() -> legacyPlacements.findByIdOptional(rowId).orElse(null));
    if (entry == null) {
      return LegacyPin.NONE;
    }
    if (blankOrNull(entry.runnerName) && blankOrNull(entry.engineId) && blankOrNull(entry.hostname)) {
      LOG.warnf(
          "Workspace %s was held by runner %s, of which nothing is recorded: volume %s is taken as"
              + " gone, and the workspace starts on a fresh volume",
          rowId, entry.oldRunnerId, entry.legacyName());
      QuarkusTransaction.requiringNew().run(() -> legacyPlacements.deleteById(rowId));
      return new LegacyPin(null, null, true);
    }
    String waitFor = blankOrNull(entry.runnerName) ? entry.oldRunnerId.toString() : entry.runnerName;
    List<TargetPlacement.Target> targets;
    try {
      targets = leases.targets();
    } catch (RuntimeException unread) {
      LOG.warnf(
          "Workspace %s adopts %s, and the targets could not be read to pin it (%s); it waits",
          rowId, entry.legacyName(), unread.getMessage());
      return new LegacyPin(null, waitFor, false);
    }
    Optional<UUID> pinned =
        match(targets, entry.engineId, TargetPlacement.Target::engineId)
            .or(() -> match(targets, entry.hostname, TargetPlacement.Target::hostname))
            .or(() -> match(targets, entry.runnerName, TargetPlacement.Target::name));
    if (pinned.isEmpty()) {
      LOG.infof(
          "Workspace %s adopts volume %s, and no target is on runner %s's node yet (engine id %s,"
              + " host name %s): it waits for one",
          rowId, entry.legacyName(), entry.runnerName, entry.engineId, entry.hostname);
      return new LegacyPin(null, waitFor, false);
    }
    return new LegacyPin(pinned.get(), null, false);
  }

  /** The runtime error of a row waiting for the target that holds its legacy volume. */
  static String waitingForTarget(String runner) {
    return "waiting for target " + runner + " to be installed (it holds this workspace's volume)";
  }

  /**
   * Parks {@code row} QUEUED with no lease, saying which target it waits for, and gives back any
   * token it still held: nothing runs with it. The start's process, when narrated, ends saying so.
   */
  private String waitForLegacyTarget(Workspace row, String runner, boolean narrate) {
    String reason = waitingForTarget(runner);
    boolean moved =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository
                        .findActiveById(row.id)
                        .filter(w -> w.leaseId == null)
                        .map(
                            w -> {
                              boolean changed =
                                  w.runtimeStatus != WorkspaceRuntimeStatus.QUEUED
                                      || !reason.equals(w.runtimeError);
                              w.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
                              w.runtimeError = reason;
                              if (w.queuedAt == null) {
                                w.queuedAt = Instant.now();
                              }
                              return changed;
                            })
                        .orElse(false));
    decommissionFor(row.id);
    if (moved) {
      changePublisher.runtimeChanged(row.repositoryId, row.id);
    }
    WorkspaceProcessTracker.Handle process =
        narrate ? tracker(row.repositoryId, row.workspaceId, row.id) : null;
    if (process == null) {
      return null;
    }
    process.completeNoOp(TargetLeaseStates.QUEUED_SEGMENT, reason);
    return process.id();
  }

  /**
   * Asks again for every ACTIVE TARGET row that waits, QUEUED with no lease, for the target that
   * holds its legacy volume (epic qits-1481): the lease reconcile calls this on every pass, so the
   * adopting, pinned lease is asked for as soon as that target is installed. Answers the rows it
   * tried. A start that fails is logged and tried again on the next pass.
   */
  public List<Long> startWaitingLegacyRows() {
    List<Workspace> waiting =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    workspaceRepository.list(
                        "status = ?1 and placement = ?2 and runtimeStatus = ?3 and leaseId is null",
                        WorkspaceStatus.ACTIVE,
                        WorkspacePlacement.TARGET,
                        WorkspaceRuntimeStatus.QUEUED));
    List<Long> tried = new ArrayList<>();
    for (Workspace row : waiting) {
      tried.add(row.id);
      try {
        beginTargetStart(row, false);
      } catch (RuntimeException e) {
        LOG.debugf(e, "Workspace %s still waits for its target", row.id);
      }
    }
    return tried;
  }

  private static boolean blankOrNull(String value) {
    return value == null || value.isBlank();
  }

  private static Optional<UUID> match(
      List<TargetPlacement.Target> targets,
      String wanted,
      java.util.function.Function<TargetPlacement.Target, String> fact) {
    if (wanted == null || wanted.isBlank()) {
      return Optional.empty();
    }
    return targets.stream()
        .filter(t -> wanted.equals(fact.apply(t)))
        .map(TargetPlacement.Target::id)
        .findFirst();
  }

  /** A project id qits-system can take, or null: it is a UUID there. */
  private static UUID projectUuid(RepositoryLookup.RepositoryView repository) {
    String project = projectOf(repository);
    if (project == null) {
      return null;
    }
    try {
      return UUID.fromString(project);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }

  /**
   * Rung 3 of the ladder for a TARGET row: its durable branch is gone, so it is abandoned and 404s,
   * as a DIRECT row is. Its lease is ended, and its work volume purged once that lease is ENDED.
   */
  private void abandonTargetRow(Workspace row) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              Workspace wt = requireActive(row.id);
              wt.status = WorkspaceStatus.ABANDONED;
              wt.resolvedAt = Instant.now();
              wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              wt.queuedAt = null;
              recordEvent(wt, WorkspaceEventType.ABANDONED, wt.branch, null, null);
              workspaceResolvedEvent.fire(
                  new WorkspaceResolved(
                      wt.repositoryId, wt.workspaceId, wt.id, WorkspaceStatus.ABANDONED));
            });
    releaseFromTarget(row);
    decommissionFor(row.id);
    throw new NotFoundException(
        "Workspace '" + row.workspaceId + "' has no branch to recreate from; abandoned");
  }

  /**
   * Stop, for a TARGET row: its lease is ended ({@code DELETE /system/api/leases/{id}}) and the row
   * is STOPPED. A QUEUED lease is ENDED at once; a granted one is ENDING until its target reports
   * the container gone, and {@link TargetLeaseStates} then clears it off the row with its token. The
   * work volume stays. A row with no lease — a stopped one, or a row a retired runner still runs —
   * just goes STOPPED, and its token is given back.
   */
  private void stopOnTarget(Workspace row) {
    UUID leaseId = row.leaseId;
    if (leaseId != null) {
      if (!targetPlacement.isResolvable()) {
        throw TargetRefusals.unavailable(row.id, "stop", "no lease client is configured");
      }
      Optional<TargetPlacement.Lease> ended;
      try {
        ended = targetPlacement.get().end(leaseId);
      } catch (TargetPlacement.LeaseRefused refused) {
        throw TargetRefusals.unavailable(row.id, "stop", refused.getMessage());
      }
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository
                      .findActiveById(row.id)
                      .filter(wt -> leaseId.equals(wt.leaseId))
                      .ifPresent(
                          wt -> {
                            wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                            wt.runtimeError = null;
                            wt.queuedAt = null;
                          }));
      if (ended.isEmpty() || !ended.get().state().live()) {
        leaseStates.observe(TargetLeaseStates.ended(leaseId));
      }
    } else {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  workspaceRepository
                      .findActiveById(row.id)
                      .filter(wt -> wt.leaseId == null)
                      .ifPresent(
                          wt -> {
                            wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                            wt.queuedAt = null;
                          }));
      decommissionFor(row.id);
    }
    leaseStates.abandonStart(row.id, "Stopped.");
    changePublisher.runtimeChanged(row.repositoryId, row.id);
  }

  /**
   * Delete-container, for a TARGET row: its lease is ended and waited for (up to {@link
   * TargetPlacement#ENDING_DEADLINE}, else 504 {@code TARGET_TIMEOUT}), then its work volume {@code
   * ws-<rowId>} is purged on its target ({@code DELETE /system/api/work-volumes/ws-<rowId>}), and
   * the row is STOPPED with no lease, no target and no adopted volume. The row stays ACTIVE, and its
   * next start gets a fresh volume on any target. The workspace token goes with the container.
   */
  private void deleteOnTarget(Workspace row) {
    if (row.leaseId != null || targetPlacement.isResolvable()) {
      if (!targetPlacement.isResolvable()) {
        throw TargetRefusals.unavailable(
            row.id, "delete its container", "no lease client is configured");
      }
      TargetPlacement leases = targetPlacement.get();
      retirePreviousLease(row, leases, "delete its container");
      try {
        leases.purgeWorkVolume(TargetPlacement.workKey(row.id));
      } catch (TargetPlacement.LeaseRefused refused) {
        if (refused.status() == 409) {
          throw TargetRefusals.timeout(row.id, "delete its container");
        }
        throw TargetRefusals.unavailable(row.id, "delete its container", refused.getMessage());
      }
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              workspaceRepository
                  .findActiveById(row.id)
                  .ifPresent(
                      wt -> {
                        wt.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                        wt.runtimeError = null;
                        wt.leaseId = null;
                        wt.targetId = null;
                        wt.adoptedVolume = null;
                        wt.queuedAt = null;
                      });
              // The old volume is gone, or the person removes it with the retired runner's: the
              // next lease adopts nothing.
              legacyPlacements.deleteById(row.id);
            });
    leaseStates.abandonStart(row.id, "The container was deleted.");
    decommissionFor(row.id);
    changePublisher.runtimeChanged(row.repositoryId, row.id);
  }

  /**
   * Recreate, for a TARGET row: the agent-worktree gate first, exactly the DIRECT one (a live agent
   * with leftover work, busy, or uncheckable refuses with 400), then {@link #deleteOnTarget}
   * (lease ended, work volume purged, token deleted), then the start, which mints a fresh token and
   * asks for a new lease on any target.
   */
  private String beginTargetRecreate(Workspace row) {
    requireCleanForRecreate(row.workspaceId, row.id);
    deleteOnTarget(row);
    return beginTargetStart(QuarkusTransaction.requiringNew().call(() -> requireActive(row.id)));
  }

  /**
   * A TARGET row left the estate: its lease is ended once the resolution committed, without
   * waiting, and {@link TargetLeaseStates} purges its work volume when that lease is ENDED; a row
   * with no lease has its volume purged at once. Never in the way of the resolution.
   */
  private void releaseFromTarget(Workspace row) {
    leaseStates.abandonStart(row.id, "The workspace was resolved.");
    if (!targetPlacement.isResolvable()) {
      return;
    }
    UUID leaseId = row.leaseId;
    long rowId = row.id;
    TargetPlacement leases = targetPlacement.get();
    afterCommit(
        () -> {
          try {
            if (leaseId != null) {
              leases.end(leaseId).filter(l -> !l.state().live()).ifPresent(leaseStates::observe);
            } else {
              leases.purgeWorkVolume(TargetPlacement.workKey(rowId));
            }
          } catch (RuntimeException e) {
            LOG.debugf(
                e, "Could not release resolved workspace %s's lease; the reconcile will", rowId);
          }
        });
  }

  private static void endQuietly(TargetPlacement leases, UUID leaseId, Long rowId) {
    try {
      leases.end(leaseId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not end lease %s nobody holds (workspace %s); the reconcile will: %s",
          leaseId, rowId, e.getMessage());
    }
  }

  private static void sleepQuietly(Duration pause) {
    try {
      Thread.sleep(pause.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DomainException(503, "Interrupted while waiting for a lease to end");
    }
  }

  /**
   * {@link #doDiscard}'s teardown for a TARGET row. The row resolves exactly as a DIRECT one does
   * (the branch deletion, the status, the history event, the resolved event, the metadata) — the
   * tail below is that method's, kept in step with it — and what differs is the container half:
   * nothing here calls qits-containers, and resolution never waits on a lease ({@link
   * #releaseFromTarget}).
   */
  private void discardOnTarget(
      String repoId,
      Workspace workspace,
      WorkspaceStatus resolution,
      String result,
      String target,
      String commit,
      boolean deleteBranch) {
    try {
      String branch = workspace.branch;
      releaseFromTarget(workspace);
      // The workspace token goes with the container it was minted for (qits-625), beside the
      // release and for doDiscard's reason not on the resolved event.
      deleteToken(clearToken(workspace));

      if (deleteBranch && branch != null && !branch.isBlank()) {
        try {
          mirrors.of(repoId).deleteBranch(branch);
        } catch (GitMirrorException ignored) {
          // the branch may already be gone, and the resolution is not conditional on the ref
        }
      }

      workspace.status = resolution;
      workspace.resolvedAt = Instant.now();
      // STOPPED here and not from the lease: the row keeps naming its lease until it is ENDED, so the
      // volume is purged then, and nothing writes a resolved row's runtime status (qits-1064).
      workspace.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
      workspace.queuedAt = null;
      if (result != null && !result.isBlank()) {
        workspace.result = result;
      }
      recordEvent(
          workspace,
          resolution == WorkspaceStatus.INTEGRATED
              ? WorkspaceEventType.INTEGRATED
              : WorkspaceEventType.ABANDONED,
          branch,
          target,
          commit);
      workspaceResolvedEvent.fire(
          new WorkspaceResolved(repoId, workspace.workspaceId, workspace.id, resolution));
      workspaceMetadata.delete(repoId, workspace.workspaceId);
    } catch (InternalServerErrorException e) {
      throw e;
    } catch (Exception e) {
      throw new InternalServerErrorException("Git discard failed: " + e.getMessage());
    }
  }

  /**
   * A tracking handle for a streamed operation, or {@code null} when no {@link
   * WorkspaceProcessTracker} is installed — every call site already treats null as "run it
   * unnarrated", which is the pre-streaming behaviour.
   */
  private WorkspaceProcessTracker.Handle tracker(String repoId, String workspaceId, Long rowId) {
    return processes.isResolvable() ? processes.get().begin(repoId, workspaceId, rowId) : null;
  }

  public record MergeResult(
      String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
}
