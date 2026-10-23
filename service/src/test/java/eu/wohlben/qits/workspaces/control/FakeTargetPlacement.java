package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test double for {@link TargetPlacement}: qits-system's leases as an in-memory table and a log of
 * every call. A lease is created QUEUED (detail {@link #WAITING}) and stays there until a test moves
 * it ({@link #move}) and hands the move to {@link TargetLeaseStates#observe}, as the event or the
 * reconcile would. An end makes a QUEUED lease ENDED at once and a granted one ENDING, as qits-system
 * does; {@link #endsAtOnce} makes every end ENDED.
 *
 * <p>Every call is logged as {@code "<verb>:<rowId|leaseId|workKey>"}, newest last. The log is how a
 * test proves a DIRECT row never reached this port: it stays empty. A test that changes the double
 * calls {@link #reset} in {@code @AfterEach}.
 */
@ApplicationScoped
public class FakeTargetPlacement implements TargetPlacement {

  /** The detail a fresh lease waits with. */
  public static final String WAITING = "no target has a free WORKSPACE slot";

  private final Map<UUID, Lease> leases = new ConcurrentHashMap<>();
  private final Map<UUID, LeaseRequest> requests = new ConcurrentHashMap<>();
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<String> purged = Collections.synchronizedList(new ArrayList<>());
  private volatile LeaseRefused refusal;
  private volatile LeaseRefused unreachable;
  private volatile boolean endsAtOnce;
  private volatile List<Target> targets = List.of();

  /** Every create from now on throws {@code refused}. */
  public void refuse(LeaseRefused refused) {
    refusal = refused;
  }

  /** Every call from now on throws {@code refused}: qits-system cannot be asked anything. */
  public void unreachable(LeaseRefused refused) {
    unreachable = refused;
  }

  /** A lease qits-system holds already, as a state that seeds a row with it needs. */
  public void seed(Lease lease) {
    leases.put(lease.id(), lease);
  }

  /** Every end from now on answers ENDED at once. */
  public void endsAtOnce() {
    endsAtOnce = true;
  }

  /** What {@link #targets()} answers. */
  public void targets(List<Target> answer) {
    targets = List.copyOf(answer);
  }

  /** The lease, moved to {@code state} on {@code targetId}, as qits-system would report it. */
  public Lease move(UUID leaseId, LeaseState state, UUID targetId, Integer exitCode, String detail) {
    Lease was = leases.get(leaseId);
    Lease moved =
        new Lease(leaseId, was.ownerRef(), state, targetId, exitCode, detail, was.requestedAt());
    leases.put(leaseId, moved);
    return moved;
  }

  /** The lease with this id as the fake holds it. */
  public Lease lease(UUID leaseId) {
    return leases.get(leaseId);
  }

  /** What was asked for {@code leaseId}. */
  public LeaseRequest request(UUID leaseId) {
    return requests.get(leaseId);
  }

  /** The newest lease asked for {@code rowId}, if any. */
  public Optional<Lease> leaseOf(long rowId) {
    return requests.entrySet().stream()
        .filter(e -> e.getValue().rowId() == rowId)
        .map(e -> leases.get(e.getKey()))
        .max(java.util.Comparator.comparing(Lease::requestedAt));
  }

  /** The work keys purged so far. */
  public List<String> purged() {
    synchronized (purged) {
      return List.copyOf(purged);
    }
  }

  /** Every call so far, newest last. */
  public List<String> calls() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  public void clearCalls() {
    calls.clear();
  }

  /** Back to the shipped posture: no leases, nothing logged, nothing refused. */
  public void reset() {
    leases.clear();
    requests.clear();
    calls.clear();
    purged.clear();
    refusal = null;
    unreachable = null;
    endsAtOnce = false;
    targets = List.of();
  }

  @Override
  public Lease create(LeaseRequest request) {
    calls.add("create:" + request.rowId());
    failIfUnreachable();
    LeaseRefused refused = refusal;
    if (refused != null) {
      throw refused;
    }
    UUID id = UUID.randomUUID();
    Lease lease =
        new Lease(
            id,
            TargetPlacement.ownerRef(request.rowId()),
            LeaseState.QUEUED,
            null,
            null,
            WAITING,
            Instant.now());
    leases.put(id, lease);
    requests.put(id, request);
    return lease;
  }

  @Override
  public Optional<Lease> end(UUID leaseId) {
    calls.add("end:" + leaseId);
    failIfUnreachable();
    Lease lease = leases.get(leaseId);
    if (lease == null) {
      return Optional.empty();
    }
    if (lease.state() == LeaseState.ENDED || lease.state() == LeaseState.ENDING) {
      return Optional.of(lease);
    }
    LeaseState next =
        endsAtOnce || lease.state() == LeaseState.QUEUED || lease.targetId() == null
            ? LeaseState.ENDED
            : LeaseState.ENDING;
    return Optional.of(move(leaseId, next, lease.targetId(), lease.exitCode(), lease.detail()));
  }

  @Override
  public Optional<Lease> get(UUID leaseId) {
    calls.add("get:" + leaseId);
    failIfUnreachable();
    return Optional.ofNullable(leases.get(leaseId));
  }

  @Override
  public List<Lease> list() {
    calls.add("list");
    failIfUnreachable();
    return List.copyOf(leases.values());
  }

  @Override
  public boolean purgeWorkVolume(String workKey) {
    calls.add("purge:" + workKey);
    failIfUnreachable();
    purged.add(workKey);
    return true;
  }

  @Override
  public List<Target> targets() {
    calls.add("targets");
    failIfUnreachable();
    return targets;
  }

  private void failIfUnreachable() {
    LeaseRefused down = unreachable;
    if (down != null) {
      throw down;
    }
  }
}
