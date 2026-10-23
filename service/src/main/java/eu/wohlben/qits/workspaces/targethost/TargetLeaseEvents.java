package eu.wohlben.qits.workspaces.targethost;

import eu.wohlben.qits.eventstream.QitsEventListener;
import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.TargetLeaseStates;
import eu.wohlben.qits.workspaces.control.TargetPlacement;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * How this service learns what became of its leases (epic qits-1481, qits-1532): qits-system's
 * {@link TargetLeaseChanged} as it happens, and a reconcile from {@code GET /system/api/leases}
 * every {@code qits.workspaces.target-leases.reconcile-interval} (PT30S), so a lost event only
 * delays a row. Both hand the lease to {@link TargetLeaseStates}, which maps it onto the row.
 *
 * <p><b>A live listener, not a durable one.</b> The reconcile is the catch-up: what an event says,
 * the next listing says too, so a missed one costs at most an interval and needs no watermark. Only
 * leases whose owner is {@code qits-workspaces} and whose slot type is WORKSPACE are this service's;
 * every other is another owner's and is ignored.
 *
 * <p><b>The reconcile also ends orphans</b>: a live lease of this service's that no row names, older
 * than {@link #ORPHAN_AFTER} — a start that asked for one and crashed before the row recorded it. And
 * it kicks the agent scheduler when a row moved, as a door would.
 */
@ApplicationScoped
public class TargetLeaseEvents implements QitsEventListener<TargetLeaseChanged> {

  private static final Logger LOG = Logger.getLogger(TargetLeaseEvents.class);

  /** The owner qits-system names this service's leases by. */
  static final String OWNER = "qits-workspaces";

  /** How old an unnamed lease has to be before the reconcile ends it: a start's write window. */
  static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

  @Inject TargetLeaseStates states;

  @Inject Instance<TargetPlacement> placement;

  @Inject Instance<AgentService> agents;

  @Override
  public Class<TargetLeaseChanged> eventType() {
    return TargetLeaseChanged.class;
  }

  @Override
  public void onEvent(TargetLeaseChanged event) {
    if (event == null
        || !OWNER.equals(event.ownerApp())
        || !TargetPlacement.SLOT_TYPE.equals(event.slotType())) {
      return;
    }
    UUID leaseId = uuid(event.leaseId());
    if (leaseId == null) {
      LOG.debugf("TargetLeaseChanged %s names no lease id; ignored", event.eventId());
      return;
    }
    TargetPlacement.Lease lease =
        new TargetPlacement.Lease(
            leaseId,
            event.ownerRef(),
            TargetPlacement.LeaseState.parse(event.state()),
            uuid(event.targetId()),
            event.exitCode(),
            event.detail(),
            null);
    try {
      if (states.observe(lease) == TargetLeaseStates.Outcome.CHANGED) {
        kick();
      }
    } catch (RuntimeException e) {
      // The reconcile tells the row the same thing within an interval.
      LOG.warnf(e, "Could not apply lease %s's %s; the reconcile will", leaseId, event.state());
    }
  }

  @Scheduled(
      every = "{qits.workspaces.target-leases.reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
      skipExecutionIf = Scheduled.ApplicationNotRunning.class)
  void reconcileOnSchedule() {
    reconcile();
  }

  /** One pass; answers the rows it changed, or empty when the leases could not be listed. */
  List<Long> reconcile() {
    if (!placement.isResolvable()) {
      return List.of();
    }
    List<TargetPlacement.Lease> listed;
    try {
      listed = placement.get().list();
    } catch (RuntimeException e) {
      // An unreadable listing must not read as "every lease is gone".
      LOG.debugf("Could not list this service's leases; the next pass asks again: %s", e.getMessage());
      return List.of();
    }
    List<Long> changed = states.reconcile(listed, ORPHAN_AFTER, Instant.now());
    if (!changed.isEmpty()) {
      kick();
    }
    return changed;
  }

  private void kick() {
    if (agents.isResolvable()) {
      try {
        agents.get().kick();
      } catch (RuntimeException e) {
        LOG.debugf(e, "Could not kick the agent scheduler after a lease moved");
      }
    }
  }

  private static UUID uuid(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(text.trim());
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
