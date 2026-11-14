package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.control.TargetPlacement.Lease;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseRefused;
import eu.wohlben.qits.workspaces.control.TargetPlacement.LeaseState;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refreshes idle workspaces whose container's DNS resolvers are stale: a TARGET row's lease
 * (qits-2091), and a DIRECT row's container itself (qits-2231).
 *
 * <p>A target's runner decides a lease container's resolvers only when it creates it, so a
 * workspace whose lease was created before the node's tunnel answered keeps public DNS for life.
 * qits-system reports that as the lease's {@code resolversStale}, and its refresh door makes the
 * runner recreate the container on the same work volume: the files survive, every process in it —
 * the agents' harnesses, the workspace-daemon — is restarted. So a pass ({@link #refreshStale})
 * refreshes only a slot where no harness works:
 *
 * <ul>
 *   <li>one listing of this service's leases per pass; a lease is a candidate when it is RUNNING and
 *       stale, and the row naming it is ACTIVE, TARGET and RUNNING. A DIRECT row never holds a lease
 *       and is the other leg's (below);
 *   <li>idle is {@link AgentWorktreeGuard#notIdle}: the recreate gate's busy question without its
 *       leftover-work one, since the volume stays. A busy slot, or one whose daemon cannot say, is
 *       skipped and asked again on the next pass;
 *   <li>the check and the refresh run {@linkplain AgentService#betweenPasses between scheduler
 *       passes}, which is where every agent start and every turn is said — so no turn can begin
 *       between the two. The pass also waits there, up to {@code
 *       qits.workspaces.target-leases.refresh-settle}, for the slot's daemon to drop its control
 *       socket, so the next pass does not say a turn into a container about to be recreated;
 *   <li>a lease this process refreshed is not refreshed again at the generation the refresh
 *       answered for {@code qits.workspaces.target-leases.refresh-cooldown} (PT30M): until then,
 *       still stale there most likely means the runner has not reported the new container yet.
 *       After it, a lease still stale at that generation — the node's tunnel was down when the
 *       container was recreated — is refreshed again, idle and between passes as before.
 * </ul>
 *
 * <p><b>The DIRECT leg</b> (qits-2231). An admin workspace on the platform host is run by
 * qits-containers with {@code privateEdge}, which makes the private edge's swarm VIP its first
 * resolver — read at the run and never again. When the edge service is recreated its VIP moves, the
 * container keeps the dead one, and every private name then resolves to the public edge. So each
 * pass also asks qits-containers, once per ACTIVE, DIRECT and RUNNING row, whether that row's
 * container's resolvers are stale ({@link ContainerRuntime#resolversStale}); only a definite yes is
 * a candidate, and an answer that could not be had is asked again next pass. A candidate is
 * recreated on its own volume ({@link WorkspaceService#recreateDirectContainerKeepingVolume}: the
 * container removed, its volume kept, and run again with its provision's own arguments, so
 * qits-containers resolves the VIP afresh) under the same rules as a lease: only when idle, between
 * passes, the pass waiting there for the old daemon to go. A row this process recreated is not
 * recreated again for the same {@code refresh-cooldown}, whatever it reads meanwhile. The two legs
 * are independent: the DIRECT one runs where no target can be had, and a failure of either leaves
 * the other's pass as it was.
 *
 * <p>Never the delete or recreate paths: they purge the work volume. A refusal (409 {@code
 * LEASE_NOT_RUNNING}: the lease moved since the listing) is logged and the pass goes on; so is a
 * DIRECT run that failed, which leaves the row as the run left it.
 */
@ApplicationScoped
public class StaleResolverRefresh {

  private static final Logger LOG = Logger.getLogger(StaleResolverRefresh.class);

  /** The reason every refresh of this pass is logged with. */
  static final String REASON = "resolvers stale";

  private static final Duration SETTLE_POLL = Duration.ofMillis(500);

  /** Absent: no target can be had, and there is nothing to refresh. */
  @Inject Instance<TargetPlacement> placement;

  @Inject WorkspaceRepository workspaces;

  @Inject AgentWorktreeGuard guard;

  @Inject AgentService agents;

  /** The DIRECT leg's question: whether a platform-host container's resolvers are stale. */
  @Inject ContainerRuntime containerRuntime;

  /** The DIRECT leg's recreate, on the volume the row already has. */
  @Inject WorkspaceService workspaceService;

  /** Absent: whether the daemon left cannot be told, and nothing is waited for. */
  @Inject Instance<WorkspaceDaemonLiveness> daemonLiveness;

  /** How long a refresh waits, between passes, for the slot's daemon to go. */
  @ConfigProperty(name = "qits.workspaces.target-leases.refresh-settle", defaultValue = "PT60S")
  Duration settle;

  /** How long a lease is not refreshed again at the generation its refresh answered. */
  @ConfigProperty(name = "qits.workspaces.target-leases.refresh-cooldown", defaultValue = "PT30M")
  Duration cooldown;

  private volatile Clock clock = Clock.systemUTC();

  /** A refresh this process made: the generation it answered, and when. */
  private record Refreshed(long generation, Instant at) {}

  /** The refresh this process last made of each listed lease. */
  private final Map<UUID, Refreshed> refreshedTo = new ConcurrentHashMap<>();

  /** For tests: the clock the cooldown is read on. */
  void clock(Clock clock) {
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  /** When this process last recreated each DIRECT row's container, by row id (qits-2231). */
  private final Map<Long, Instant> directRefreshedAt = new ConcurrentHashMap<>();

  /** For tests: forgets every refresh made, as a restart would. */
  void forgetRefreshes() {
    refreshedTo.clear();
    directRefreshedAt.clear();
  }

  /** What one pass did, by row id, for the scheduler's caller and the tests. */
  public record Pass(List<Long> refreshed, List<Long> busy, List<Long> refused) {
    static final Pass NOTHING = new Pass(List.of(), List.of(), List.of());

    /** Both legs' rows, this one's first. */
    Pass and(Pass other) {
      return new Pass(
          concat(refreshed, other.refreshed), concat(busy, other.busy), concat(refused, other.refused));
    }

    private static List<Long> concat(List<Long> first, List<Long> second) {
      List<Long> both = new ArrayList<>(first);
      both.addAll(second);
      return List.copyOf(both);
    }
  }

  private enum Outcome {
    REFRESHED,
    BUSY,
    REFUSED
  }

  /**
   * One pass: every RUNNING, stale lease of an idle RUNNING TARGET row is refreshed, and every idle
   * RUNNING DIRECT row whose container reads its resolvers stale is recreated on its volume.
   */
  public Pass refreshStale() {
    return leg("lease", this::refreshStaleLeases).and(leg("DIRECT", this::refreshStaleDirect));
  }

  /** One leg of a pass; one that fails is a WARN and leaves the other leg's rows as they were. */
  private static Pass leg(String name, Supplier<Pass> leg) {
    try {
      return leg.get();
    } catch (RuntimeException e) {
      LOG.warnf(e, "The %s leg of the stale-resolver pass failed; the next pass tries again", name);
      return Pass.NOTHING;
    }
  }

  /** The lease leg (qits-2091): nothing when no target can be had. */
  private Pass refreshStaleLeases() {
    if (!placement.isResolvable()) {
      return Pass.NOTHING;
    }
    TargetPlacement leases = placement.get();
    List<Lease> listed;
    try {
      listed = leases.list();
    } catch (RuntimeException e) {
      LOG.debugf("Could not list the leases for stale resolvers; the next pass asks again: %s", e);
      return Pass.NOTHING;
    }
    Set<UUID> ids = listed.stream().map(Lease::id).collect(Collectors.toSet());
    refreshedTo.keySet().retainAll(ids);
    List<Long> refreshed = new ArrayList<>();
    List<Long> busy = new ArrayList<>();
    List<Long> refused = new ArrayList<>();
    for (Lease lease : listed) {
      if (lease.id() == null || lease.state() != LeaseState.RUNNING || !lease.resolversStale()) {
        continue;
      }
      Refreshed done = refreshedTo.get(lease.id());
      if (done != null
          && done.generation() == lease.generation()
          && clock.instant().isBefore(done.at().plus(cooldown))) {
        LOG.debugf(
            "Lease %s still reads its resolvers stale at generation %d, which this service"
                + " refreshed it to at %s; not refreshed again before %s",
            lease.id(), lease.generation(), done.at(), cooldown);
        continue;
      }
      Workspace row =
          QuarkusTransaction.requiringNew().call(() -> workspaces.findByLeaseId(lease.id()));
      if (!candidate(row)) {
        continue;
      }
      Long rowId = row.id;
      try {
        Outcome outcome = agents.betweenPasses(() -> refreshIfIdle(leases, lease.id(), rowId));
        switch (outcome) {
          case REFRESHED -> refreshed.add(rowId);
          case BUSY -> busy.add(rowId);
          case REFUSED -> refused.add(rowId);
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not refresh workspace %s's lease %s (%s); the next pass asks again: %s",
            rowId, lease.id(), REASON, e.getMessage());
      }
    }
    return new Pass(List.copyOf(refreshed), List.copyOf(busy), List.copyOf(refused));
  }

  /**
   * The DIRECT leg (qits-2231): each ACTIVE, DIRECT and RUNNING row's container asked about its
   * resolvers, and an idle stale one recreated on its volume.
   */
  private Pass refreshStaleDirect() {
    List<Workspace> rows =
        QuarkusTransaction.requiringNew().call(() -> workspaces.findActiveDirectRunning());
    directRefreshedAt.keySet().retainAll(rows.stream().map(w -> w.id).collect(Collectors.toSet()));
    List<Long> refreshed = new ArrayList<>();
    List<Long> busy = new ArrayList<>();
    List<Long> refused = new ArrayList<>();
    for (Workspace row : rows) {
      Long rowId = row.id;
      Instant done = directRefreshedAt.get(rowId);
      if (done != null && clock.instant().isBefore(done.plus(cooldown))) {
        LOG.debugf(
            "Workspace %s's container was recreated at %s (%s); not asked again before %s",
            rowId, done, REASON, cooldown);
        continue;
      }
      String container = containerRuntime.containerName(row.workspaceId, row.repositoryId);
      if (!containerRuntime.resolversStale(container).orElse(false)) {
        continue;
      }
      try {
        Outcome outcome = agents.betweenPasses(() -> recreateIfIdle(rowId, container));
        switch (outcome) {
          case REFRESHED -> refreshed.add(rowId);
          case BUSY -> busy.add(rowId);
          case REFUSED -> refused.add(rowId);
        }
      } catch (RuntimeException e) {
        LOG.warnf(
            "Could not recreate DIRECT workspace %s's container %s (%s); the next pass asks again:"
                + " %s",
            rowId, container, REASON, e.getMessage());
      }
    }
    return new Pass(List.copyOf(refreshed), List.copyOf(busy), List.copyOf(refused));
  }

  /** Between passes: the slot's idleness asked, and the DIRECT container recreated when idle. */
  private Outcome recreateIfIdle(Long rowId, String container) {
    Optional<String> notIdle = guard.notIdle(rowId);
    if (notIdle.isPresent()) {
      LOG.debugf(
          "DIRECT workspace %s's container %s has stale resolvers, but the slot is not idle (%s);"
              + " the next pass asks again",
          rowId, container, notIdle.get());
      return Outcome.BUSY;
    }
    boolean daemonWasLive = daemonLive(rowId);
    boolean recreated;
    try {
      recreated = workspaceService.recreateDirectContainerKeepingVolume(rowId);
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not run DIRECT workspace %s's container %s again (%s); the row is marked FAILED"
              + " and its volume kept: %s",
          rowId, container, REASON, e.getMessage());
      return Outcome.REFUSED;
    }
    if (!recreated) {
      LOG.infof(
          "DIRECT workspace %s moved since it was listed (%s); its container is left as it is",
          rowId, REASON);
      return Outcome.REFUSED;
    }
    directRefreshedAt.put(rowId, clock.instant());
    LOG.infof(
        "Recreated DIRECT workspace %s's container %s: %s; it is run again on the same work volume",
        rowId, container, REASON);
    if (daemonWasLive) {
      awaitDaemonGone(rowId);
    }
    return Outcome.REFRESHED;
  }

  private static boolean candidate(Workspace row) {
    return row != null
        && row.status == WorkspaceStatus.ACTIVE
        && row.placement == WorkspacePlacement.TARGET
        && row.runtimeStatus == WorkspaceRuntimeStatus.RUNNING;
  }

  /** Between passes: the slot's idleness asked, and the lease refreshed when it is idle. */
  private Outcome refreshIfIdle(TargetPlacement leases, UUID leaseId, Long rowId) {
    Optional<String> notIdle = guard.notIdle(rowId);
    if (notIdle.isPresent()) {
      LOG.debugf(
          "Workspace %s's lease %s has stale resolvers, but the slot is not idle (%s); the next"
              + " pass asks again",
          rowId, leaseId, notIdle.get());
      return Outcome.BUSY;
    }
    boolean daemonWasLive = daemonLive(rowId);
    Lease refreshed;
    try {
      refreshed = leases.refresh(leaseId);
    } catch (LeaseRefused refused) {
      LOG.infof(
          "qits-system refused to refresh workspace %s's lease %s (%s); the next pass asks again"
              + " if it still reads stale: %s",
          rowId, leaseId, REASON, refused.getMessage());
      return Outcome.REFUSED;
    }
    long generation = refreshed == null ? -1 : refreshed.generation();
    refreshedTo.put(leaseId, new Refreshed(generation, clock.instant()));
    LOG.infof(
        "Refreshed workspace %s's lease %s to generation %d: %s; its container is recreated on the"
            + " same work volume",
        rowId, leaseId, generation, REASON);
    if (daemonWasLive) {
      awaitDaemonGone(rowId);
    }
    return Outcome.REFRESHED;
  }

  private boolean daemonLive(Long rowId) {
    return daemonLiveness.isResolvable() && daemonLiveness.get().isDaemonLive(rowId);
  }

  /** Up to {@link #settle}: the old container's daemon dropping its socket. */
  private void awaitDaemonGone(Long rowId) {
    long deadline = System.nanoTime() + settle.toNanos();
    while (daemonLive(rowId) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(SETTLE_POLL.toMillis());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if (daemonLive(rowId)) {
      LOG.debugf(
          "Workspace %s's daemon is still connected %s after its container was recreated",
          rowId,
          settle);
    }
  }
}
