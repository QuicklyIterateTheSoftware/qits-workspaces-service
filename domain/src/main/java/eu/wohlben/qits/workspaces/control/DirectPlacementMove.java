package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.DomainException;
import eu.wohlben.qits.workspaces.error.MoveRefusals;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRunnerRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Moving a regular DIRECT workspace onto a runner (qits-776): the rows written DIRECT before
 * qits-774 made every regular workspace RUNNER. <b>By recreation from the branch, never by
 * adoption</b> — the DIRECT container and its volume are destroyed and a runner clones the branch
 * afresh — so {@link MoveGate} admits only a row with nothing to lose.
 *
 * <p>Two callers and one path: the person-pressed door ({@link #beginMove}, {@code POST
 * …/{id}/move-to-runner}) and the direct-migration sweep ({@link #sweep}, scheduled by {@code
 * containershost/DirectMigrationSweep}). Both end in {@link #move}:
 *
 * <ol>
 *   <li>{@code move-check}: the gate, then the compare-and-swap {@code placement=RUNNER, runnerId
 *       =NULL WHERE placement=DIRECT} in its own committed transaction ({@link
 *       WorkspaceRepository#moveToRunner}); 0 rows is 409 {@code ALREADY_MOVED}, so of two racing
 *       moves exactly one tears anything down;
 *   <li>{@code move-teardown}: after that commit, the DIRECT container's services settled, a
 *       graceful stop, the {@code rm} and the volume — delete-container's sequence ({@link
 *       WorkspaceService#tearDownDirect}) — then the row's commissioned client given back and its
 *       columns cleared ({@link WorkspaceService#decommissionFor});
 *   <li>a row that was RUNNING is started through the RUNNER placement on the same process
 *       ({@code queued}, then the runner's {@code container}/{@code clone}), so it ends RUNNING, or
 *       QUEUED while no slot is free; any other row is left STOPPED on no runner, the state
 *       delete-container leaves.
 * </ol>
 *
 * <p><b>A teardown that fails is never silent and needs no new state.</b> The row is RUNNER by then,
 * and a WARN {@code direct-orphan <containerName>} says what was left. The orphan is remembered by
 * the orchestrator itself: a DIRECT container (one owner-wide listing, {@link
 * ContainerRuntime#workspaceContainerNames}) whose name belongs to an ACTIVE RUNNER row can only be
 * one a move failed to remove, so the sweep's teardown-only arm re-runs the teardown for exactly
 * those. Such a row is NOT started on a runner by the move: its old container may still be
 * running, and two daemons answering for one row is worse than a stopped workspace a person starts.
 */
@ApplicationScoped
public class DirectPlacementMove {

  private static final Logger LOG = Logger.getLogger(DirectPlacementMove.class);

  /** The gate and the swap. */
  public static final String CHECK_SEGMENT = "move-check";

  /** The DIRECT container's and volume's removal, and the credential given back. */
  public static final String TEARDOWN_SEGMENT = "move-teardown";

  /** How often the door polls for the daemon's first GitStatus frame after a bring-up. */
  private static final long REPORT_POLL_MS = 200;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject WorkspaceRunnerRepository runnerRepository;

  @Inject WorkspaceService workspaces;

  @Inject MoveGate gate;

  @Inject ContainerRuntime containers;

  @Inject WorkspaceChangePublisher changePublisher;

  @Inject Instance<RunnerPlacement> runnerPlacement;

  @Inject Instance<WorkspaceGitStatus> gitStatus;

  @Inject Instance<WorkspaceDaemonLiveness> liveness;

  @Inject Instance<WorkspaceAgentActivity> agentActivity;

  /** The door's bound on waiting for a brought-up daemon's first report: the provision's own. */
  @ConfigProperty(name = "qits.workspace.provision.connect-timeout-ms", defaultValue = "30000")
  long connectTimeoutMs;

  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-move");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  // --- the door -------------------------------------------------------------------------------

  /**
   * The door, recreate's model: what can be refused cheaply is refused in the request, and the work
   * streams over the technical process answered.
   *
   * <p>The posture (admin/editor, already RUNNER, PROVISIONING) is always checked in the request.
   * When the DIRECT container is running — or container and volume are both gone — the whole gate
   * is too, so a dirty, unknown or unpushed tree is a 400 here. When the container is stopped, or
   * gone while its volume is not, the tree cannot be judged until something reads it: the process
   * first brings the container up on the DIRECT path (ensure's start-in-place or provision rungs)
   * and waits, bounded by {@code qits.workspace.provision.connect-timeout-ms}, for the daemon's
   * first GitStatus report, and only then applies the gate — a refusal there fails the process with
   * the gate's sentence and code, and nothing has been torn down.
   */
  public String beginMove(Long rowId) {
    Workspace row = readActive(rowId);
    gate.checkPosture(row);
    String container = containers.containerName(row.workspaceId, row.repositoryId);
    boolean running = containers.isRunning(container);
    boolean present = running || containers.exists(container);
    boolean bringUp =
        (present && !running) || (!present && containers.workspaceVolumeExists(row.workspaceId));
    if (!bringUp) {
      gate.check(row);
    }
    WorkspaceProcessTracker.Handle process =
        workspaces.tracker(row.repositoryId, row.workspaceId, row.id);
    executor.submit(
        () -> {
          try {
            if (bringUp) {
              bringUp(row, process);
            }
            move(rowId, process, bringUp);
          } catch (RuntimeException e) {
            if (process != null) {
              process.failProvision(describe(e));
            }
            LOG.debugf(e, "move-to-runner failed for workspace %s", rowId);
          }
        });
    return process == null ? null : process.id();
  }

  /**
   * Ensure's rungs 2-3 on the DIRECT path — a stopped container started in place, an absent one
   * provisioned onto its surviving volume — then the wait for the daemon's first GitStatus report,
   * which is what the gate reads.
   */
  private void bringUp(Workspace row, WorkspaceProcessTracker.Handle process) {
    if (process != null) {
      process.openSegment(CHECK_SEGMENT);
      process.appendLine(
          CHECK_SEGMENT, "Bringing the direct container up to read its working tree.");
    }
    workspaces.ensureContainer(row.id);
    long deadline = System.currentTimeMillis() + connectTimeoutMs;
    while (workspaces.reportedCleanliness(row.id).isEmpty()
        && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(REPORT_POLL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (process != null) {
      process.appendLine(
          CHECK_SEGMENT,
          workspaces.reportedCleanliness(row.id).isPresent()
              ? "Its daemon reported the working tree."
              : "No daemon reported the working tree within " + connectTimeoutMs + " ms.");
    }
  }

  // --- the move -------------------------------------------------------------------------------

  /**
   * The move itself, gate included; see the class javadoc for its three steps. Throws the gate's
   * refusal (nothing changed), or 409 {@code ALREADY_MOVED} when another move won the swap.
   *
   * @param process the stream it narrates onto, or null to run unnarrated
   */
  public void move(Long rowId, WorkspaceProcessTracker.Handle process) {
    move(rowId, process, false);
  }

  /** {@link #move}, answering the sweep's reason: how the gate passed, and what was left behind. */
  private String move(Long rowId, WorkspaceProcessTracker.Handle process, boolean checkOpen) {
    if (process != null && !checkOpen) {
      process.openSegment(CHECK_SEGMENT);
    }
    Workspace row = readActive(rowId);
    MoveGate.Passage passage = gate.check(row);
    String passed =
        passage == MoveGate.Passage.NOTHING_TO_LOSE
            ? "nothing to lose (no container, no volume)"
            : "clean and pushed";
    String container = containers.containerName(row.workspaceId, row.repositoryId);
    boolean wasRunning = containers.isRunning(container);
    int swapped =
        QuarkusTransaction.requiringNew().call(() -> workspaceRepository.moveToRunner(rowId));
    if (swapped == 0) {
      throw MoveRefusals.alreadyMoved(row.workspaceId);
    }
    if (process != null) {
      process.appendLine(
          CHECK_SEGMENT,
          passage == MoveGate.Passage.NOTHING_TO_LOSE
              ? "No direct container and no volume: nothing to lose."
              : "Clean, and every commit is on the git host.");
      process.appendLine(CHECK_SEGMENT, "Placed on the runners.");
      process.settleSegment(CHECK_SEGMENT, true);
      process.openSegment(TEARDOWN_SEGMENT);
    }
    changePublisher.runtimeChanged(row.repositoryId, rowId);

    boolean tornDown = tearDown(row, true, process);
    // The credential's lifetime is the DIRECT container's, which is gone (or going): given back,
    // with the row's old client id, and both columns cleared — whatever the teardown managed.
    workspaces.decommissionFor(rowId);
    if (!tornDown) {
      if (process != null) {
        process.failProvision(
            "Moved onto the runners, but the direct container "
                + container
                + " is not removed yet; the direct-migration sweep retries its teardown. Start the"
                + " workspace once it is gone.");
      }
      return passed + "; direct-orphan " + container + " left for the retry";
    }
    if (process != null) {
      process.appendLine(TEARDOWN_SEGMENT, "Removed the direct container and its volume.");
    }
    if (wasRunning) {
      if (process != null) {
        process.settleSegment(TEARDOWN_SEGMENT, true);
      }
      workspaces.startOnRunner(rowId, process);
      return passed + "; started on the runners";
    }
    if (process != null) {
      process.completeNoOp(
          TEARDOWN_SEGMENT, "It was not running, so it stays stopped until it is next started.");
    }
    return passed + "; left stopped";
  }

  /**
   * The DIRECT teardown, answering whether the container is gone. Any failure — a throw, or a
   * container still there after its {@code rm} — is a WARN {@code direct-orphan <containerName>}.
   */
  private boolean tearDown(
      Workspace row, boolean settleServices, WorkspaceProcessTracker.Handle process) {
    String container = containers.containerName(row.workspaceId, row.repositoryId);
    try {
      workspaces.tearDownDirect(row.repositoryId, row.workspaceId, row.id, settleServices);
    } catch (RuntimeException e) {
      LOG.warnf(
          "direct-orphan %s: removing workspace %s's direct container failed (%s); the"
              + " direct-migration sweep retries it",
          container, row.id, e.toString());
      if (process != null) {
        process.appendLine(TEARDOWN_SEGMENT, "Removing " + container + " failed: " + e.getMessage());
      }
      return false;
    }
    if (containers.exists(container)) {
      LOG.warnf(
          "direct-orphan %s: workspace %s's direct container is still there after its removal; the"
              + " direct-migration sweep retries it",
          container, row.id);
      if (process != null) {
        process.appendLine(TEARDOWN_SEGMENT, container + " is still there after its removal.");
      }
      return false;
    }
    return true;
  }

  // --- the sweep ------------------------------------------------------------------------------

  /** One sweep decision, as its log line says it. */
  public record Decision(Long rowId, boolean moved, String reason) {
    @Override
    public String toString() {
      return "direct-migration " + (moved ? "moved " : "skipped ") + rowId + " " + reason;
    }
  }

  /** One tick over the whole estate; see {@link #sweep(Predicate)}. */
  public List<Decision> sweep() {
    return sweep(row -> true);
  }

  /**
   * One tick of the direct-migration sweep. Each decision is logged at INFO ({@code
   * direct-migration moved <rowId> <reason>} / {@code direct-migration skipped <rowId> <reason>})
   * and answered.
   *
   * <ol>
   *   <li>The teardown-only arm first, whether or not a runner has room: every {@code
   *       direct-orphan} (see the class javadoc) is torn down again, without settling services —
   *       they may be the runner container's by now.
   *   <li>Nothing else unless a serving runner ({@link RunnerPlacement#servingRunnerIds}) is in
   *       service ({@link WorkspaceRunner#eligible}) and holds fewer live workspaces than its slots
   *       — the server's own slot count, the one a reserve trusts.
   *   <li>At most ONE row moves, oldest {@code createdAt} first, among the ACTIVE regular DIRECT
   *       rows: one with no container and no volume, or a RUNNING one whose daemon is connected,
   *       whose agent is idle-free (no session, or {@code ENDED} — never {@code BUSY}, {@code
   *       WAITING} or {@code IDLE}) and which passes the gate. A stopped container is never
   *       started; a gone container whose volume survives waits for the door, which can bring it
   *       up.
   * </ol>
   *
   * @param scope which rows this tick may touch at all — everything, in production; a test's own
   *     rows in a suite whose database holds other suites' rows too
   */
  public List<Decision> sweep(Predicate<Workspace> scope) {
    List<Decision> decisions = new ArrayList<>();
    retryOrphans(scope, decisions);
    List<Workspace> candidates =
        QuarkusTransaction.requiringNew()
            .call(() -> workspaceRepository.findActiveDirectRegular())
            .stream()
            .filter(scope)
            .toList();
    if (candidates.isEmpty()) {
      return decisions;
    }
    if (!anyFreeSlot()) {
      decide(decisions, candidates.get(0).id, false, "no enabled, connected runner has a free slot");
      return decisions;
    }
    for (Workspace row : candidates) {
      String refusal;
      try {
        refusal = whyNot(row);
      } catch (RuntimeException e) {
        refusal = "could not be judged: " + e.getMessage();
      }
      if (refusal != null) {
        decide(decisions, row.id, false, refusal);
        continue;
      }
      WorkspaceProcessTracker.Handle process =
          workspaces.tracker(row.repositoryId, row.workspaceId, row.id);
      try {
        decide(decisions, row.id, true, move(row.id, process, false));
      } catch (RuntimeException e) {
        // The process must end either way: an ensure joins a live one rather than starting.
        if (process != null) {
          process.failProvision(describe(e));
        }
        decide(
            decisions,
            row.id,
            false,
            e instanceof DomainException domain
                ? code(domain) + ": " + e.getMessage()
                : "move failed: " + e.getMessage());
      }
      return decisions;
    }
    return decisions;
  }

  /** Null when the sweep may move {@code row}, else why not. */
  private String whyNot(Workspace row) {
    if (row.runtimeStatus == WorkspaceRuntimeStatus.PROVISIONING) {
      return MoveRefusals.PROVISIONING;
    }
    String container = containers.containerName(row.workspaceId, row.repositoryId);
    boolean running = containers.isRunning(container);
    boolean present = running || containers.exists(container);
    if (!present) {
      return containers.workspaceVolumeExists(row.workspaceId)
          ? "no container but its volume is there; the move-to-runner door can bring it up"
          : null;
    }
    if (!running) {
      return "its container is stopped, and the sweep never starts one";
    }
    if (!liveness.isResolvable() || !liveness.get().isDaemonLive(row.id)) {
      return "its daemon is not connected";
    }
    Optional<AgentActivityState> activity =
        agentActivity.isResolvable() ? agentActivity.get().activityFor(row.id) : Optional.empty();
    if (activity.isPresent() && activity.get() != AgentActivityState.ENDED) {
      return "an agent session is " + activity.get();
    }
    try {
      gate.check(row);
      return null;
    } catch (DomainException e) {
      return code(e);
    }
  }

  /** Whether any serving runner is in service with a slot its live workspaces do not hold. */
  private boolean anyFreeSlot() {
    if (!runnerPlacement.isResolvable()) {
      return false;
    }
    Set<UUID> serving = runnerPlacement.get().servingRunnerIds();
    if (serving.isEmpty()) {
      return false;
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                serving.stream()
                    .anyMatch(
                        id ->
                            runnerRepository
                                .findByIdOptional(id)
                                .filter(WorkspaceRunner::eligible)
                                .map(r -> workspaceRepository.countLiveOnRunner(id) < r.slots)
                                .orElse(false)));
  }

  /** The teardown-only arm: every DIRECT container whose row is RUNNER now, torn down again. */
  private void retryOrphans(Predicate<Workspace> scope, List<Decision> decisions) {
    Set<String> names = containers.workspaceContainerNames();
    if (names.isEmpty()) {
      return;
    }
    List<Workspace> placed =
        QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findActiveOnAnyRunner());
    for (Workspace row : placed) {
      if (!scope.test(row)
          || !names.contains(containers.containerName(row.workspaceId, row.repositoryId))) {
        continue;
      }
      String container = containers.containerName(row.workspaceId, row.repositoryId);
      decide(
          decisions,
          row.id,
          tearDown(row, false, null),
          "direct-orphan " + container + " teardown retried");
    }
  }

  private static void decide(List<Decision> decisions, Long rowId, boolean moved, String reason) {
    Decision decision = new Decision(rowId, moved, reason);
    LOG.info(decision.toString());
    decisions.add(decision);
  }

  private Workspace readActive(Long rowId) {
    if (rowId == null) {
      throw new NotFoundException("Workspace not found: null");
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                workspaceRepository
                    .findActiveById(rowId)
                    .orElseThrow(() -> new NotFoundException("Workspace not found: " + rowId)));
  }

  private static String code(DomainException e) {
    return e.code() == null ? Integer.toString(e.statusCode()) : e.code();
  }

  /** A failure as the process stream says it: the gate's code first, when it has one. */
  private static String describe(RuntimeException e) {
    return e instanceof DomainException domain && domain.code() != null
        ? domain.code() + ": " + e.getMessage()
        : e.getMessage();
  }
}
