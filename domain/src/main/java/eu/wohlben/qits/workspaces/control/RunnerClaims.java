package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
 * <p>Each write is its own {@code requiringNew} transaction, and every hint and notification fires
 * after it commits.
 */
@ApplicationScoped
public class RunnerClaims {

  private static final Logger LOG = Logger.getLogger(RunnerClaims.class);

  /** The segment a start waits in, and the one a launch runs in. */
  static final String QUEUED_SEGMENT = "queued";

  static final String CONTAINER_SEGMENT = "container";

  @Inject WorkspaceRepository workspaces;

  @Inject WorkspaceRunnerRepository runners;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Optional, see {@link RunnerPlacement}: absent, the backlog notifications go nowhere. */
  @Inject Instance<RunnerPlacement> placement;

  private final Map<Long, WorkspaceProcessTracker.Handle> starts = new ConcurrentHashMap<>();

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
   */
  public void abandonStart(Long rowId, String message) {
    WorkspaceProcessTracker.Handle process = rowId == null ? null : starts.remove(rowId);
    if (process != null) {
      process.failProvision(message);
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
        });
    return claimed;
  }

  // --- launch results -----------------------------------------------------------------------------

  /** {@code launched}: the runner's container for {@code rowId} runs. RUNNING. */
  public boolean launched(UUID runnerId, Long rowId) {
    Optional<Workspace> written =
        write(
            runnerId,
            rowId,
            row -> {
              row.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
              row.runtimeError = null;
              row.queuedAt = null;
            });
    written.ifPresent(
        row -> {
          WorkspaceProcessTracker.Handle process = starts.remove(rowId);
          if (process != null) {
            process.appendLine(CONTAINER_SEGMENT, "The runner reports the container running.");
            process.settleSegment(CONTAINER_SEGMENT, true);
            process.finishProvision(true);
          }
        });
    return written.isPresent();
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
   *   <li>an owned row held running → RUNNING;
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
    List<Workspace> changed =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<Workspace> touched = new ArrayList<>();
                  Instant now = Instant.now();
                  for (Workspace row : workspaces.lockActiveOnRunner(runnerId)) {
                    WorkspaceRuntimeStatus was = row.runtimeStatus;
                    Boolean running = holding.get(row.id);
                    if (Boolean.TRUE.equals(running)) {
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
