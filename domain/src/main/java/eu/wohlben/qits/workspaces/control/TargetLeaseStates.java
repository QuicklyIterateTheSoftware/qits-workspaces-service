package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.control.TargetPlacement.Lease;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseState;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.events.WorkspaceFailed;
import eu.wohlben.qits.workspaces.events.WorkspaceStarted;
import eu.wohlben.qits.workspaces.persistence.LegacyPlacementRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * What a lease's state does to its workspace row (epic qits-1481, qits-1532): the one writer of a
 * TARGET row's runtime status after its start asked for the lease. {@code
 * targethost/TargetLeaseEvents} hands it every {@code TargetLeaseChanged} of this service's and,
 * every {@code qits.workspaces.target-leases.reconcile-interval}, every lease qits-system lists, so
 * a lost event only delays a row. Nothing here knows HTTP.
 *
 * <p><b>The mapping</b> (dossier, "Leases and tokens per slot type"):
 *
 * <ul>
 *   <li>QUEUED → {@code QUEUED}, the lease's {@code detail} (why it waits) as the row's runtime
 *       error;
 *   <li>GRANTED → {@code PROVISIONING}: a target is starting the container;
 *   <li>RUNNING → still {@code PROVISIONING} until the container's workspace-daemon has dialled home
 *       and reported its self-clone, then {@code RUNNING} — through the same {@link
 *       WorkspaceDaemonProvisioner} and windows as rung 3 of the DIRECT ladder. A daemon that never
 *       dials home, or a clone that fails, leaves the row FAILED with the reason;
 *   <li>EXITED → {@code STOPPED} from a live status; FAILED → {@code FAILED} with the lease's
 *       detail. Either way the lease's token is deleted and the lease is ended, so the row can ask
 *       for the next one;
 *   <li>ENDED → {@code STOPPED} from a live status, the lease and its target cleared off the row and
 *       the token deleted. A resolved row's ENDED lease purges its work volume.
 * </ul>
 *
 * <p><b>A row is found by its lease id</b>, never by the lease's owner ref: a lease the row does not
 * name (an earlier one, or one a crash orphaned) moves nothing. A resolved row still names its lease
 * until that lease is ENDED, so the ending is seen and the volume purged.
 *
 * <p><b>RUNNING after a restart.</b> The daemon wait runs in memory. A lease this process asked for,
 * or saw waiting, is {@link #expectStart expected}: its RUNNING starts the wait. A RUNNING lease this
 * process knows nothing about whose row is still PROVISIONING is a restart that lost the wait, and
 * the row reads RUNNING — the old runner inventory's reading of the same case.
 *
 * <p><b>The technical process a start opened</b> is carried here, by row id ({@link #track}), as it
 * was for the runners: segment {@code queued} until a target is granted, {@code container} until it
 * runs, {@code clone} until the daemon reported.
 *
 * <p>Each write is its own {@code requiringNew} transaction, and every announcement, token deletion
 * and lease call follows it.
 */
@ApplicationScoped
public class TargetLeaseStates {

  private static final Logger LOG = Logger.getLogger(TargetLeaseStates.class);

  /** The segment a start waits in, and the one a launch runs in. */
  static final String QUEUED_SEGMENT = "queued";

  static final String CONTAINER_SEGMENT = "container";

  /** The segment the daemon's self-clone streams into, as on the DIRECT ladder. */
  static final String CLONE_SEGMENT = "clone";

  @Inject WorkspaceRepository workspaces;

  @Inject LegacyPlacementRepository legacy;

  @Inject WorkspaceChangePublisher changePublisher;

  /** Optional, see {@link TargetPlacement}: absent, nothing is ended or purged from here. */
  @Inject Instance<TargetPlacement> placement;

  /** The lease's token is deleted through it once the lease is over. Absent: nothing to delete. */
  @Inject Instance<CredentialCommissioner> commissioner;

  /** The daemon's self-provision, awaited after RUNNING as the DIRECT ladder awaits it. */
  @Inject Instance<WorkspaceDaemonProvisioner> daemonProvisioner;

  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long provisionConnectTimeoutMs;

  @ConfigProperty(name = "qits.workspace.provision.timeout-ms", defaultValue = "600000")
  long provisionTimeoutMs;

  /** A row's start ended other than by its container coming up (qits-626). */
  @Inject Event<WorkspaceUnqueued> unqueued;

  /** {@code WorkspaceStarted} and {@code WorkspaceFailed} (qits-1236). Absent: nothing announced. */
  @Inject Instance<LifecycleAnnouncer> lifecycle;

  private final Map<Long, WorkspaceProcessTracker.Handle> starts = new ConcurrentHashMap<>();

  /** The leases whose RUNNING starts a daemon wait: asked for, or seen waiting, by this process. */
  private final Set<UUID> expected = ConcurrentHashMap.newKeySet();

  /** The rows whose daemon is being waited for. */
  private final Set<Long> awaitingDaemon = ConcurrentHashMap.newKeySet();

  private final ExecutorService daemonWaits =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "target-workspace-daemon-wait");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdownWaits() {
    daemonWaits.shutdownNow();
  }

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
   * Ends the start process open for {@code rowId}, if any, as failed with {@code message}, and fires
   * {@link WorkspaceUnqueued}: every way a row's start ends other than by its container coming up
   * comes through here.
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

  /** {@code leaseId} was asked for by this process: its RUNNING starts the daemon wait. */
  void expectStart(UUID leaseId) {
    if (leaseId != null) {
      expected.add(leaseId);
    }
  }

  /** Whether {@code rowId}'s daemon is being waited for. */
  public boolean awaitingDaemon(Long rowId) {
    return rowId != null && awaitingDaemon.contains(rowId);
  }

  // --- a lease's state, applied ---------------------------------------------------------------------

  /** What {@link #observe} did, for the caller and the tests. */
  public enum Outcome {
    /** No row names the lease. */
    NO_ROW,
    /** The row names it and nothing changed. */
    UNCHANGED,
    /** The row's status, error, lease or target moved. */
    CHANGED
  }

  /** A lease that qits-system no longer lists: as good as ENDED. */
  public static Lease ended(UUID leaseId) {
    return new Lease(leaseId, null, LeaseState.ENDED, null, null, null, null);
  }

  /**
   * Applies what {@code lease} says to the row that names it. See the class javadoc for the mapping.
   * Never throws for a lease it cannot place: a row that names another lease, or none, is left as it
   * is.
   */
  public Outcome observe(Lease lease) {
    if (lease == null || lease.id() == null || lease.state() == null) {
      return Outcome.NO_ROW;
    }
    if (lease.state() == LeaseState.QUEUED || lease.state() == LeaseState.GRANTED) {
      expected.add(lease.id());
    }
    Applied applied =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace row = workspaces.lockByLeaseId(lease.id());
                  if (row == null || row.placement != WorkspacePlacement.TARGET) {
                    return null;
                  }
                  return row.status == WorkspaceStatus.ACTIVE
                      ? applyToActive(row, lease)
                      : applyToResolved(row, lease);
                });
    if (applied == null) {
      if (lease.state() == LeaseState.ENDED) {
        expected.remove(lease.id());
      }
      return Outcome.NO_ROW;
    }
    after(applied, lease);
    return applied.changed() ? Outcome.CHANGED : Outcome.UNCHANGED;
  }

  /**
   * What one write decided, carried out of its transaction: the row as written, its runtime status
   * before, the token it gave up and what follows the commit.
   */
  private record Applied(
      Workspace row,
      WorkspaceRuntimeStatus was,
      boolean changed,
      String tokenToDelete,
      boolean endLease,
      boolean purge,
      Follow follow) {}

  /** What happens after the write committed. */
  private enum Follow {
    NOTHING,
    GRANTED,
    AWAIT_DAEMON,
    STARTED,
    STOPPED,
    FAILED
  }

  private Applied applyToActive(Workspace row, Lease lease) {
    WorkspaceRuntimeStatus was = row.runtimeStatus;
    String error = row.runtimeError;
    UUID target = row.targetId;
    boolean live =
        was == WorkspaceRuntimeStatus.QUEUED
            || was == WorkspaceRuntimeStatus.PROVISIONING
            || was == WorkspaceRuntimeStatus.RUNNING;
    String token = null;
    boolean end = false;
    Follow follow = Follow.NOTHING;
    switch (lease.state()) {
      case QUEUED -> {
        if (was == WorkspaceRuntimeStatus.QUEUED) {
          row.runtimeError = truncate(lease.detail());
        }
      }
      case GRANTED -> {
        if (lease.targetId() != null) {
          row.targetId = lease.targetId();
        }
        if (was == WorkspaceRuntimeStatus.QUEUED) {
          row.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING;
          row.runtimeError = null;
          row.queuedAt = null;
          follow = Follow.GRANTED;
        }
      }
      case RUNNING -> {
        if (lease.targetId() != null) {
          row.targetId = lease.targetId();
        }
        // The adopted lease is the row's now: the legacy entry has done its work.
        legacy.deleteById(row.id);
        if (was == WorkspaceRuntimeStatus.QUEUED || was == WorkspaceRuntimeStatus.PROVISIONING) {
          if (awaitingDaemon.contains(row.id)) {
            break;
          }
          row.queuedAt = null;
          if (expected.remove(lease.id())) {
            row.runtimeStatus = WorkspaceRuntimeStatus.PROVISIONING;
            row.runtimeError = null;
            // Marked BEFORE the write commits, so a reconcile that lands in between does not call
            // the row RUNNING ahead of its daemon.
            awaitingDaemon.add(row.id);
            follow = Follow.AWAIT_DAEMON;
          } else {
            // A restart lost the wait: the old inventory's reading, the container runs.
            row.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
            if (!AgentKills.isAgentKill(row.runtimeError)) {
              row.runtimeError = null;
            }
            follow = Follow.STARTED;
          }
        }
      }
      case EXITED -> {
        token = clearToken(row);
        end = true;
        if (live) {
          row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
          row.queuedAt = null;
          follow = Follow.STOPPED;
        }
      }
      case FAILED -> {
        token = clearToken(row);
        end = true;
        if (live) {
          row.runtimeStatus = WorkspaceRuntimeStatus.FAILED;
          row.runtimeError =
              truncate(
                  lease.detail() == null || lease.detail().isBlank()
                      ? "the target could not start the workspace"
                      : lease.detail());
          row.queuedAt = null;
          follow = Follow.FAILED;
        }
      }
      case ENDING, UNKNOWN -> {}
      case ENDED -> {
        token = clearToken(row);
        row.leaseId = null;
        row.targetId = null;
        if (live) {
          row.runtimeStatus = WorkspaceRuntimeStatus.STOPPED;
          row.queuedAt = null;
          follow = Follow.STOPPED;
        }
      }
    }
    boolean changed =
        row.runtimeStatus != was
            || !Objects.equals(row.runtimeError, error)
            || !Objects.equals(row.targetId, target)
            || row.leaseId == null
            || token != null;
    return new Applied(row, was, changed, token, end, false, follow);
  }

  /** A resolved row's lease: ended if it is not yet, and once ENDED, its volume purged. */
  private Applied applyToResolved(Workspace row, Lease lease) {
    String token = clearToken(row);
    if (lease.state() == LeaseState.ENDED) {
      row.leaseId = null;
      row.targetId = null;
      return new Applied(row, row.runtimeStatus, true, token, false, true, Follow.NOTHING);
    }
    return new Applied(
        row,
        row.runtimeStatus,
        token != null,
        token,
        lease.state() != LeaseState.ENDING,
        false,
        Follow.NOTHING);
  }

  private void after(Applied applied, Lease lease) {
    Workspace row = applied.row();
    if (applied.changed()) {
      changePublisher.runtimeChanged(row.repositoryId, row.id);
    }
    if (applied.tokenToDelete() != null && commissioner.isResolvable()) {
      commissioner.get().deleteToken(applied.tokenToDelete());
    }
    if (applied.endLease() && placement.isResolvable()) {
      try {
        placement.get().end(lease.id());
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not end lease %s of workspace %s; the reconcile asks again: %s",
            lease.id(), row.id, e.getMessage());
      }
    }
    if (applied.purge() && placement.isResolvable()) {
      purgeQuietly(row.id);
    }
    WorkspaceProcessTracker.Handle process = starts.get(row.id);
    switch (applied.follow()) {
      case GRANTED -> {
        if (process != null) {
          process.settleSegment(QUEUED_SEGMENT, true);
          process.openSegment(CONTAINER_SEGMENT);
          process.appendLine(
              CONTAINER_SEGMENT, "Granted a workspace slot on target " + lease.targetId() + ".");
        }
      }
      case AWAIT_DAEMON -> {
        if (process != null) {
          process.settleSegment(QUEUED_SEGMENT, true);
          process.appendLine(CONTAINER_SEGMENT, "The target reports the container running.");
          process.settleSegment(CONTAINER_SEGMENT, true);
          process.openSegment(CLONE_SEGMENT);
        }
        try {
          daemonWaits.submit(() -> awaitDaemon(lease.id(), row.id));
        } catch (RejectedExecutionException shuttingDown) {
          awaitingDaemon.remove(row.id);
        }
      }
      case STARTED -> {
        announceStarted(row);
        WorkspaceProcessTracker.Handle done = starts.remove(row.id);
        if (done != null) {
          done.settleSegment(QUEUED_SEGMENT, true);
          done.settleSegment(CONTAINER_SEGMENT, true);
          done.finishProvision(true);
        }
      }
      case STOPPED ->
          abandonStart(
              row.id,
              lease.state() == LeaseState.EXITED
                  ? "The container exited"
                      + (lease.exitCode() == null ? "." : " with code " + lease.exitCode() + ".")
                  : "The lease ended.");
      case FAILED -> {
        announceFailed(row);
        abandonStart(row.id, row.runtimeError);
      }
      case NOTHING -> {}
    }
    if (lease.state() == LeaseState.ENDED || lease.state() == LeaseState.FAILED
        || lease.state() == LeaseState.EXITED) {
      expected.remove(lease.id());
    }
  }

  /**
   * Rung 3 for a TARGET row whose container runs: the daemon's self-provision, awaited. Reported →
   * RUNNING; no daemon in the connect window, or a failed or overdue clone → FAILED with the reason.
   * Either write only lands on a row still PROVISIONING under the same lease, so a stop or a delete
   * that came first stands.
   */
  void awaitDaemon(UUID leaseId, Long rowId) {
    try {
      WorkspaceProcessTracker.Handle process = starts.get(rowId);
      Consumer<String> onLine =
          process == null ? null : line -> process.appendLine(CLONE_SEGMENT, line);
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
      WorkspaceRuntimeStatus to =
          error == null ? WorkspaceRuntimeStatus.RUNNING : WorkspaceRuntimeStatus.FAILED;
      if (error != null) {
        LOG.warnf("Target workspace %s did not come up: %s", rowId, error);
      }
      Optional<Workspace> written =
          QuarkusTransaction.requiringNew()
              .call(
                  () -> {
                    Workspace row = workspaces.lockByLeaseId(leaseId);
                    if (row == null
                        || !rowId.equals(row.id)
                        || row.status != WorkspaceStatus.ACTIVE
                        || row.runtimeStatus != WorkspaceRuntimeStatus.PROVISIONING) {
                      return Optional.<Workspace>empty();
                    }
                    row.runtimeStatus = to;
                    row.runtimeError = error == null ? null : truncate(error);
                    return Optional.of(row);
                  });
      if (written.isEmpty()) {
        return;
      }
      Workspace row = written.get();
      changePublisher.runtimeChanged(row.repositoryId, row.id);
      if (error == null) {
        announceStarted(row);
        WorkspaceProcessTracker.Handle done = starts.remove(rowId);
        if (done != null) {
          done.settleSegment(CLONE_SEGMENT, true);
          done.finishProvision(true);
        }
      } else {
        announceFailed(row);
        abandonStart(rowId, error);
      }
    } catch (RuntimeException e) {
      LOG.warnf(e, "Waiting for the daemon of target workspace %s failed", rowId);
    } finally {
      awaitingDaemon.remove(rowId);
    }
  }

  // --- the reconcile's half ---------------------------------------------------------------------------

  /**
   * One reconcile pass over what qits-system lists (every {@code
   * qits.workspaces.target-leases.reconcile-interval}): each row that names a lease is told that
   * lease's state, an ENDED one when the listing no longer holds it; a live lease of this service's
   * that no row names, and that is older than {@code orphanAfter}, is ended. Answers the rows it
   * changed.
   */
  public List<Long> reconcile(List<Lease> listed, Duration orphanAfter, Instant now) {
    Map<UUID, Lease> byId = new java.util.HashMap<>();
    for (Lease lease : listed) {
      if (lease != null && lease.id() != null) {
        byId.put(lease.id(), lease);
      }
    }
    List<Workspace> rows = QuarkusTransaction.requiringNew().call(workspaces::findWithLease);
    Set<UUID> named = new java.util.HashSet<>();
    List<Long> changed = new ArrayList<>();
    for (Workspace row : rows) {
      named.add(row.leaseId);
      Lease lease = byId.getOrDefault(row.leaseId, ended(row.leaseId));
      try {
        if (observe(lease) == Outcome.CHANGED) {
          changed.add(row.id);
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not reconcile workspace %s with lease %s", row.id, row.leaseId);
      }
    }
    if (placement.isResolvable()) {
      for (Lease lease : byId.values()) {
        if (named.contains(lease.id())
            || !lease.state().live()
            || lease.state() == LeaseState.ENDING
            || lease.requestedAt() == null
            || lease.requestedAt().isAfter(now.minus(orphanAfter))) {
          continue;
        }
        LOG.infof(
            "Ending lease %s (owner ref %s): no workspace row names it", lease.id(), lease.ownerRef());
        try {
          placement.get().end(lease.id());
        } catch (RuntimeException e) {
          LOG.warnf("Could not end orphaned lease %s: %s", lease.id(), e.getMessage());
        }
      }
    }
    return changed;
  }

  // --- internals --------------------------------------------------------------------------------------

  /** Clears the token columns, answering the token id to delete at qits-idp after the commit. */
  private static String clearToken(Workspace row) {
    String tokenId = row.commissionedTokenId;
    row.commissionedTokenId = null;
    row.commissionedTokenSubject = null;
    row.commissionedToken = null;
    return tokenId;
  }

  private void purgeQuietly(Long rowId) {
    try {
      placement.get().purgeWorkVolume(TargetPlacement.workKey(rowId));
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not purge work volume %s of resolved workspace %s: %s",
          TargetPlacement.workKey(rowId), rowId, e.getMessage());
    }
  }

  private void announceStarted(Workspace row) {
    LifecycleAnnouncer.announce(
        lifecycle, new WorkspaceStarted(row.id.toString(), row.repositoryId, Instant.now()));
  }

  private void announceFailed(Workspace row) {
    LifecycleAnnouncer.announce(
        lifecycle,
        new WorkspaceFailed(row.id.toString(), row.repositoryId, row.runtimeError, Instant.now()));
  }

  static String truncate(String s) {
    return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
