package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Everything a workspace runner's session writes to a workspace row (qits-851): the reserve that is
 * the claim, the results of a launch, the inventory reconcile, and the two reads a session makes.
 * The socket registry in {@code service} is the caller; nothing here knows a frame.
 *
 * <p><b>Reserve is the claim.</b> A runner with a free slot asks; {@link #reserveFor} answers one
 * row, already PROVISIONING on that runner, or nothing. The claim is the compare-and-swap in {@link
 * WorkspaceRepository#claimForRunner}, so two runners asking at once for one never-placed row get it
 * once between them, and the server's slot count ({@code countLiveOnRunner}) wins over whatever the
 * runner believes.
 *
 * <p><b>A row is never reassigned.</b> Every write below is to a row the runner owns ({@code
 * runner_id} = its id), and the only write that sets an owner is the claim, which only takes a row
 * nobody owns or this runner already does. An offline runner's rows wait for it.
 *
 * <p><b>The technical process a start opened</b> is carried here, by row id ({@link #track}): the
 * start returns it to the browser with segment {@code queued} open, the claim settles {@code queued}
 * and opens {@code container}, and the launch result settles that. It is in-memory, like every
 * technical process; a restart loses the narration and never the row.
 *
 * <p><b>{@code launched} is not RUNNING</b> (qits-625, qits-802). The runner's word is that the
 * container runs; the row stays PROVISIONING until the container's workspace-daemon has dialled home
 * and reported its self-clone, as rung 3 of the DIRECT ladder waits for it — through the same {@link
 * WorkspaceDaemonProvisioner}, bounded by the same {@code qits.workspace.provision.connect-timeout-ms}
 * and {@code timeout-ms}. A daemon that never dials home, or a clone that fails, leaves the row
 * FAILED with the reason. The wait runs on a thread of this bean's own, never the runner socket's.
 *
 * <p>Each write is its own {@code requiringNew} transaction, and every hint and notification fires
 * after it commits.
 *
 * <p><b>Two in-process events say a row left the queue</b> (qits-626), for {@link DispatchService}'s
 * parked launches: {@link WorkspaceTaken} when a runner took it — the claim, or an inventory that
 * found it running — and {@link WorkspaceUnqueued} from {@link #abandonStart}, which every other
 * way out passes through. Both are fired after the write committed.
 */
@ApplicationScoped
public class RunnerClaims {

  private static final Logger LOG = Logger.getLogger(RunnerClaims.class);

  /** The segment a start waits in, and the one a launch runs in. */
  static final String QUEUED_SEGMENT = "queued";

  static final String CONTAINER_SEGMENT = "container";

  /** The segment the daemon's self-clone streams into, as on the DIRECT ladder. */
  static final String CLONE_SEGMENT = "clone";

  @Inject WorkspaceRepository workspaces;

  @Inject WorkspaceRunnerRepository runners;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Optional, see {@link RunnerPlacement}: absent, the backlog notifications go nowhere. */
  @Inject Instance<RunnerPlacement> placement;

  /**
   * The daemon's self-provision, awaited after {@code launched} exactly as the DIRECT ladder awaits
   * it. Absent is a failed provision there, and it is here.
   */
  @Inject Instance<WorkspaceDaemonProvisioner> daemonProvisioner;

  /** How long a launched container's daemon has to dial home: rung 3's window. */
  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long provisionConnectTimeoutMs;

  /** How long, once it dialled home, it has to report its clone. */
  @ConfigProperty(name = "qits.workspace.provision.timeout-ms", defaultValue = "600000")
  long provisionTimeoutMs;

  /** A queued row was taken; fired after the claim committed (qits-626). */
  @Inject Event<WorkspaceTaken> taken;

  /** A row's start ended other than by a claim; fired by {@link #abandonStart} (qits-626). */
  @Inject Event<WorkspaceUnqueued> unqueued;

  private final Map<Long, WorkspaceProcessTracker.Handle> starts = new ConcurrentHashMap<>();

  /**
   * The rows whose daemon is being waited for after {@code launched}. The inventory reconcile reads
   * it: a container the runner holds running is not yet a RUNNING row while its daemon has not
   * reported, and a restart that lost the wait lets the inventory say RUNNING as before.
   */
  private final Set<Long> awaitingDaemon = ConcurrentHashMap.newKeySet();

  /** The waits themselves: each blocks for up to the two windows, so each gets its own thread. */
  private final ExecutorService daemonWaits =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "runner-workspace-daemon-wait");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdownWaits() {
    daemonWaits.shutdownNow();
  }

  /** One container a runner reported holding: the row it was launched for, and whether it runs. */
  public record HeldContainer(Long rowId, boolean running) {}

  // --- the start's technical process ---------------------------------------------------------------

  /** Carries the process a start opened for {@code rowId} until the launch settles it. */
  public void track(Long rowId, WorkspaceProcessTracker.Handle process) {
    if (rowId != null && process != null) {
      starts.put(rowId, process);
    }
  }

  /** The start process still open for {@code rowId}, if any. */
  public Optional<WorkspaceProcessTracker.Handle> trackedStart(Long rowId) {
    return Optional.ofNullable(rowId == null ? null : starts.get(rowId));
  }

  /**
   * Ends the start process open for {@code rowId}, if any, as failed with {@code message}: the row
   * left the queue or the node other than by a launch.
   *
   * <p>Also fires {@link WorkspaceUnqueued} with {@code message} as its reason, process or none: a
   * row queued unnarrated (a create) has no process and may still have an agent launch parked on
   * it. Every way a row leaves the queue other than a claim comes through here — stop while
   * queued, delete-container, recreate, resolution and abandon, a failed launch, the runner's
   * {@code deleted} — so this is the one place that has to say it. The observer runs after the
   * caller's transaction commits, or at once when there is none.
   */
  public void abandonStart(Long rowId, String message) {
    WorkspaceProcessTracker.Handle process = rowId == null ? null : starts.remove(rowId);
    if (process != null) {
      process.failProvision(message);
    }
    if (rowId != null) {
      unqueued.fire(new WorkspaceUnqueued(rowId, message));
    }
  }

  /** Forgets {@code process} for {@code rowId} when it is still the one carried. */
  void forget(Long rowId, WorkspaceProcessTracker.Handle process) {
    if (rowId != null && process != null) {
      starts.remove(rowId, process);
    }
  }

  // --- reserve ------------------------------------------------------------------------------------

  /**
   * Answers {@code runner}'s reserve: the one QUEUED row it now holds as PROVISIONING, or empty. One
   * transaction. Empty when the runner is gone, unregistered, drained or quarantined, or when its
   * live rows (RUNNING + PROVISIONING) already fill its slots. Otherwise the candidates are its own
   * sticky rows, then never-placed ones, each oldest first, and the first whose compare-and-swap
   * changes a row is the answer; a candidate another runner took in between is skipped.
   */
  public Optional<Workspace> reserveFor(WorkspaceRunner runner) {
    UUID runnerId = runner.id;
    Optional<Workspace> claimed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  WorkspaceRunner current = runners.findById(runnerId);
                  if (current == null || !current.eligible()) {
                    return Optional.<Workspace>empty();
                  }
                  if (workspaces.countLiveOnRunner(runnerId) >= current.slots) {
                    return Optional.<Workspace>empty();
                  }
                  for (Long candidate : workspaces.queuedCandidatesFor(runnerId)) {
                    if (workspaces.claimForRunner(candidate, runnerId) == 1) {
                      // Read after the bulk update and never before it, so the row is the one the
                      // update wrote and not a copy this persistence context held already.
                      return Optional.ofNullable(workspaces.findById(candidate));
                    }
                  }
                  return Optional.<Workspace>empty();
                });
    claimed.ifPresent(
        row -> {
          trackedStart(row.id)
              .ifPresent(
                  process -> {
                    process.settleSegment(QUEUED_SEGMENT, true);
                    process.openSegment(CONTAINER_SEGMENT);
                    process.appendLine(
                        CONTAINER_SEGMENT, "Taken by runner " + runner.name + "; launching.");
                  });
          changePublisher.runtimeChanged(row.repositoryId, row.id);
          backlogChanged(row);
          // After the claim's transaction committed, so a claim that rolled back releases nothing:
          // a dispatch parked on this row starts its launch window now (qits-626).
          taken.fire(new WorkspaceTaken(row.id, runnerId));
        });
    return claimed;
  }

  // --- launch results -----------------------------------------------------------------------------

  /**
   * {@code launched}: the runner's container for {@code rowId} runs. The row stays PROVISIONING and
   * its daemon is waited for ({@link #awaitDaemon}); the {@code container} segment settles and
   * {@code clone} opens.
   */
  public boolean launched(UUID runnerId, Long rowId) {
    // Marked BEFORE the write commits, so an inventory that lands in between does not call the row
    // RUNNING ahead of its daemon.
    boolean fresh = rowId != null && awaitingDaemon.add(rowId);
    Optional<Workspace> written =
        write(
            runnerId,
            rowId,
            row -> {
              row.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING;
              row.runtimeError = null;
              row.queuedAt = null;
            });
    if (written.isEmpty()) {
      if (fresh) {
        awaitingDaemon.remove(rowId);
      }
      return false;
    }
    WorkspaceProcessTracker.Handle process = starts.get(rowId);
    if (process != null) {
      process.appendLine(CONTAINER_SEGMENT, "The runner reports the container running.");
      process.settleSegment(CONTAINER_SEGMENT, true);
      process.openSegment(CLONE_SEGMENT);
    }
    if (!fresh) {
      // A wait for this row is already running, and it settles the row.
      return true;
    }
    try {
      daemonWaits.submit(() -> awaitDaemon(runnerId, rowId));
    } catch (RejectedExecutionException shuttingDown) {
      awaitingDaemon.remove(rowId);
    }
    return true;
  }

  /**
   * Rung 3 for a launched RUNNER row: the daemon's self-provision, awaited. Reported → RUNNING;
   * no daemon in the connect window, or a failed or overdue clone → FAILED with the reason. Either
   * write only lands on a row still PROVISIONING on this runner, so a stop or a delete that came
   * first stands.
   */
  void awaitDaemon(UUID runnerId, Long rowId) {
    try {
      WorkspaceProcessTracker.Handle process = starts.get(rowId);
      Consumer<String> onLine =
          process == null ? null : line -> process.appendLine(CLONE_SEGMENT, line);
      // The DIRECT ladder's two failure wordings: no daemon at all, and a daemon that said no.
      String error;
      if (!daemonProvisioner.isResolvable()) {
        error = "no workspace-daemon provisioner is available";
      } else {
        Optional<ProvisionResult> outcome =
            daemonProvisioner
                .get()
                .awaitProvision(
                    rowId,
                    Duration.ofMillis(provisionConnectTimeoutMs),
                    Duration.ofMillis(provisionTimeoutMs),
                    onLine);
        if (outcome.isEmpty()) {
          error = "no workspace-daemon dialed home within " + provisionConnectTimeoutMs + "ms";
        } else if (!outcome.get().ok()) {
          error = "workspace-daemon self-provision failed: " + outcome.get().message();
        } else {
          error = null;
        }
      }
      if (error == null) {
        Optional<Workspace> written =
            write(
                runnerId,
                rowId,
                row -> {
                  if (row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
                    row.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                    row.runtimeError = null;
                  }
                });
        if (written.filter(row -> row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING).isPresent()) {
          WorkspaceProcessTracker.Handle done = starts.remove(rowId);
          if (done != null) {
            done.settleSegment(CLONE_SEGMENT, true);
            done.finishProvision(true);
          }
        }
        return;
      }
      LOG.warnf("Runner workspace %s did not come up: %s", rowId, error);
      Optional<Workspace> written =
          write(
              runnerId,
              rowId,
              row -> {
                if (row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
                  row.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
                  row.runtimeError = truncate(error);
                }
              });
      if (written.filter(row -> row.runtimeStatus == WorkspaceRuntimeStatus.FAILED).isPresent()) {
        abandonStart(rowId, error);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Waiting for the daemon of runner workspace %s failed", rowId);
    } finally {
      awaitingDaemon.remove(rowId);
    }
  }

  /** Whether {@code rowId}'s daemon is being waited for after its launch. */
  public boolean awaitingDaemon(Long rowId) {
    return rowId != null && awaitingDaemon.contains(rowId);
  }

  /**
   * {@code launchFailed}: the runner could not launch {@code rowId}. FAILED with {@code detail} as
   * the row's runtime error, which also frees the slot (FAILED is not live).
   */
  public boolean launchFailed(UUID runnerId, Long rowId, String detail) {
    String reason = detail == null || detail.isBlank() ? "the runner could not launch it" : detail;
    Optional<Workspace> written =
        write(
            runnerId,
            rowId,
            row -> {
              row.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
              row.runtimeError = truncate(reason);
              row.queuedAt = null;
            });
    written.ifPresent(row -> abandonStart(rowId, reason));
    return written.isPresent();
  }

  /**
   * {@code exited}: the container stopped on its own. STOPPED — but only from RUNNING or
   * PROVISIONING, so a row already queued again is not knocked out of the queue by news about the
   * container it is waiting to have started.
   */
  public boolean exited(UUID runnerId, Long rowId) {
    return stoppedFromLive(runnerId, rowId, "The container exited.");
  }

  /** {@code stopped}: the runner's answer to a routed stop. STOPPED, as {@link #exited}. */
  public boolean stopped(UUID runnerId, Long rowId) {
    return stoppedFromLive(runnerId, rowId, "The container was stopped.");
  }

  /**
   * {@code deleted}: the runner removed the container and its volume. STOPPED with no runner:
   * nothing of the row is left on that node, so the next start may be taken by any runner.
   */
  public boolean deleted(UUID runnerId, Long rowId) {
    Optional<Workspace> written =
        write(
            runnerId,
            rowId,
            row -> {
              row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              row.runtimeError = null;
              row.runnerId = null;
              row.queuedAt = null;
            });
    written.ifPresent(row -> abandonStart(rowId, "The container was deleted."));
    return written.isPresent();
  }

  private boolean stoppedFromLive(UUID runnerId, Long rowId, String message) {
    Optional<Workspace> written =
        write(
            runnerId,
            rowId,
            row -> {
              if (row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING
                  || row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
                row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
              }
            });
    written.ifPresent(
        row -> {
          if (row.runtimeStatus == WorkspaceRuntimeStatus.STOPPED) {
            abandonStart(rowId, message);
          }
        });
    return written.isPresent();
  }

  /**
   * Applies {@code change} to {@code rowId} when it is an ACTIVE RUNNER row this runner owns, locked
   * for the length of the write, and announces it. Empty for any other row: a result about a row the
   * runner does not own is dropped, never applied.
   */
  private Optional<Workspace> write(UUID runnerId, Long rowId, Consumer<Workspace> change) {
    if (runnerId == null || rowId == null) {
      return Optional.empty();
    }
    Optional<Workspace> written =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row = workspaces.findById(rowId, LockModeType.PESSIMISTIC_WRITE);
                  if (row == null
                      || row.status != WorkspaceStatus.ACTIVE
                      || row.placement != WorkspacePlacement.RUNNER
                      || !runnerId.equals(row.runnerId)) {
                    return Optional.<Workspace>empty();
                  }
                  change.accept(row);
                  return Optional.of(row);
                });
    if (written.isEmpty()) {
      LOG.debugf("Runner %s reported on workspace %s, which it does not own; ignored", runnerId, rowId);
    }
    written.ifPresent(row -> changePublisher.runtimeChanged(row.repositoryId, row.id));
    return written;
  }

  // --- inventory ----------------------------------------------------------------------------------

  /**
   * Reconciles the rows {@code runnerId} owns with the containers it reports holding, in one
   * transaction. The server's list is the desired state, and nothing is ever reassigned:
   *
   * <ul>
   *   <li>an owned row held running → RUNNING, unless it is PROVISIONING with its daemon still
   *       being waited for after {@code launched}: that wait settles it;
   *   <li>an owned row held stopped → STOPPED, unless it is QUEUED: a start is waiting for a slot,
   *       and a stopped container is exactly what it is waiting to have started;
   *   <li>an owned PROVISIONING row not held → QUEUED again, still this runner's, keeping its place
   *       in the queue ({@code queuedAt} kept, or now when it had none);
   *   <li>an owned RUNNING row not held → STOPPED;
   *   <li>every other owned row, and every held container naming a row this runner does not own,
   *       is left as it is.
   * </ul>
   *
   * @return the ids of the rows it changed
   */
  public List<Long> reconcile(UUID runnerId, List<HeldContainer> held) {
    Map<Long, Boolean> holding = new HashMap<>();
    if (held != null) {
      for (HeldContainer container : held) {
        if (container != null && container.rowId() != null) {
          holding.merge(container.rowId(), container.running(), Boolean::logicalOr);
        }
      }
    }
    // Rows that were still QUEUED and are held running: taken without a claim (a sticky row whose
    // runner came back holding its container), which a parked dispatch must hear as a take.
    List<Long> runningFromQueue = new ArrayList<>();
    List<Workspace> changed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Workspace> touched = new ArrayList<>();
                  Instant now = Instant.now();
                  for (Workspace row : workspaces.lockActiveOnRunner(runnerId)) {
                    WorkspaceRuntimeStatus was = row.runtimeStatus;
                    Boolean running = holding.get(row.id);
                    if (Boolean.TRUE.equals(running)
                        && was == WorkspaceRuntimeStatus.PROVISIONING
                        && awaitingDaemon.contains(row.id)) {
                      // Launched, and its daemon is still being waited for: that wait settles it.
                      continue;
                    } else if (Boolean.TRUE.equals(running)) {
                      if (was == WorkspaceRuntimeStatus.QUEUED) {
                        runningFromQueue.add(row.id);
                      }
                      row.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                      row.runtimeError = null;
                      row.queuedAt = null;
                    } else if (Boolean.FALSE.equals(running)) {
                      if (was != WorkspaceRuntimeStatus.QUEUED) {
                        row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                      }
                    } else if (was == WorkspaceRuntimeStatus.PROVISIONING) {
                      row.runtimeStatus = WorkspaceRuntimeStatus.QUEUED;
                      if (row.queuedAt == null) {
                        row.queuedAt = now;
                      }
                    } else if (was == WorkspaceRuntimeStatus.RUNNING) {
                      row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
                    }
                    if (row.runtimeStatus != was) {
                      touched.add(row);
                    }
                  }
                  return touched;
                });
    for (Workspace row : changed) {
      changePublisher.runtimeChanged(row.repositoryId, row.id);
      if (row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING) {
        WorkspaceProcessTracker.Handle process = starts.remove(row.id);
        if (process != null) {
          process.settleSegment(QUEUED_SEGMENT, true);
          process.settleSegment(CONTAINER_SEGMENT, true);
          process.finishProvision(true);
        }
      } else if (row.runtimeStatus == WorkspaceRuntimeStatus.STOPPED) {
        abandonStart(row.id, "The runner no longer runs the container.");
      }
    }
    // One notification is enough: every requeued row is this runner's, so it is one backlog.
    changed.stream()
        .filter(row -> row.runtimeStatus == WorkspaceRuntimeStatus.QUEUED)
        .findFirst()
        .ifPresent(this::backlogChanged);
    runningFromQueue.forEach(rowId -> taken.fire(new WorkspaceTaken(rowId, runnerId)));
    return changed.stream().map(row -> row.id).toList();
  }

  // --- reads --------------------------------------------------------------------------------------

  /** How many QUEUED RUNNER rows {@code runnerId} may take: its own and the never-placed ones. */
  public long backlogFor(UUID runnerId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.countBacklogFor(runnerId));
  }

  /** The ACTIVE rows {@code runnerId} owns, oldest first: its estate. */
  public List<Long> ownedRowIds(UUID runnerId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaces.findActiveIdsOnRunner(runnerId));
  }

  /**
   * Tells every client watching one of {@code runnerId}'s ACTIVE rows that its state may read
   * differently now, with no row written: the runner connected, or its reconnect grace ran out, so
   * the UNAVAILABLE overlay came off or went on.
   */
  public void announceOwned(UUID runnerId) {
    List<Workspace> owned =
        QuarkusTransaction.requiringNew().call(() -> workspaces.findActiveOnRunner(runnerId));
    for (Workspace row : owned) {
      changePublisher.runtimeChanged(row.repositoryId, row.id);
    }
  }

  private void backlogChanged(Workspace row) {
    if (placement.isResolvable()) {
      placement.get().backlogChanged(row);
    }
  }

  private static String truncate(String s) {
    return s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
