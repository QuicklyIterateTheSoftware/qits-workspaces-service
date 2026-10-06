package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import java.time.Duration;
import java.util.UUID;

/**
 * The domain's view of the workspace runners' sockets (epic qits-624, qits-853): how {@link
 * WorkspaceService} reaches the runner that holds a RUNNER-placed workspace, and asks whether it is
 * there. Implemented in {@code service} over the socket registry ({@code
 * runnerhost/RunnerPlacementDriver}); this module holds no socket.
 *
 * <p>Injected as {@code Instance<RunnerPlacement>}, and absent is a supported configuration with one
 * reading: <b>no runner is connected</b>. Presence is false, so a row with a runner reads
 * UNAVAILABLE and its routed verbs answer 409 {@link RunnerRefusals#RUNNER_UNAVAILABLE}; a start
 * still queues, and the notifications go nowhere.
 *
 * <p><b>A DIRECT row never reaches this port</b>: every verb branches once on {@code
 * Workspace.placement} at its top, and the DIRECT code below the branch is the code it always was.
 *
 * <p>Every {@code Workspace} handed in is a detached snapshot read just before the call; the rows'
 * ids and {@code runnerId} are what an implementation addresses. No method is called inside a
 * transaction that is waiting on it, and the notifications ({@link #backlogChanged}, {@link
 * #released}, {@link #estateChanged}) must return at once: an implementation does its database reads
 * and its socket writes on its own thread.
 */
public interface RunnerPlacement {

  /** How long a routed {@link #stop} or {@link #delete} waits for the runner's reply. */
  Duration REPLY_DEADLINE = Duration.ofSeconds(60);

  /**
   * The backlog around {@code row} changed: it entered QUEUED, or it left QUEUED other than by a
   * runner's claim (a stop, a delete, a resolution). A row with a runner changes that runner's
   * backlog; a row with none changes every connected runner's. The implementation pushes the
   * recounted backlog, debounced.
   */
  void backlogChanged(Workspace row);

  /**
   * Sends {@code stop{rowId}} to the runner holding {@code row} and waits up to {@link
   * #REPLY_DEADLINE} for {@code stopped}. Returns once it arrived; the caller then writes STOPPED.
   *
   * @throws eu.wohlben.qits.workspaces.error.ConflictException 409 {@link
   *     RunnerRefusals#RUNNER_UNAVAILABLE} when the runner is not connected
   * @throws eu.wohlben.qits.workspaces.error.DomainException 504 {@link
   *     RunnerRefusals#RUNNER_TIMEOUT} when no reply came in time; the caller writes nothing
   */
  void stop(Workspace row);

  /**
   * Sends {@code delete{rowId}} to the runner holding {@code row} (container and {@code -ws-<rowId>}
   * volume) and waits up to {@link #REPLY_DEADLINE} for {@code deleted}. Returns once it arrived; the
   * caller then writes STOPPED with no runner.
   *
   * @throws eu.wohlben.qits.workspaces.error.ConflictException 409 {@link
   *     RunnerRefusals#RUNNER_UNAVAILABLE} when the runner is not connected
   * @throws eu.wohlben.qits.workspaces.error.DomainException 504 {@link
   *     RunnerRefusals#RUNNER_TIMEOUT} when no reply came in time; the caller writes nothing
   */
  void delete(Workspace row);

  /**
   * {@code row} resolved (discarded, integrated, abandoned): a connected runner holding it is sent
   * {@code delete{rowId}}, and nobody waits for the answer; an offline one drops it at its next
   * {@code estate}. Never throws, never blocks: a resolution never waits on a runner.
   */
  void released(Workspace row);

  /**
   * Whether the runner is connected, or dropped within the reconnect grace. False past the grace,
   * which is what lays UNAVAILABLE over its rows.
   */
  boolean presence(UUID runnerId);

  /**
   * The runners that could take a workspace this moment: connected on a greeted, current session
   * that is not draining. In memory, no row read; whether such a runner is in service and has a
   * free slot is the rows' question (the direct-migration sweep's, qits-776, asks it of them).
   */
  java.util.Set<UUID> servingRunnerIds();

  /**
   * The set of ACTIVE rows {@code runnerId} owns changed (a row resolved, or was cleared off it by a
   * delete-container): a connected runner is sent a fresh {@code estate}.
   */
  void estateChanged(UUID runnerId);
}
