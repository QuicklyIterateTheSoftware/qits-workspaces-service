package eu.wohlben.qits.workspaces.containershost;

import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.WorkspacePlacements;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * "Somebody is still using this" — the keepalive an idle-stopped editor is measured against.
 *
 * <p>The editor's container is the one workspace container with an {@code IDLE_STOP} lifetime (see
 * {@link WorkspaceContainers#lifetime}), so qits-containers' own sweep is what stops it and this is
 * what tells the sweep it is being used. There are two sources of "used", and they are deliberately
 * different in kind:
 *
 * <ul>
 *   <li><b>Traffic through the editor proxy</b> — a request or an open stream on the editor's origin.
 *       Somebody has the editor on screen.
 *   <li><b>Agent activity the daemon reports</b> — a coding agent working in the workspace. Nobody is
 *       looking at it, and it must not be stopped anyway: an editor that closed on an unattended
 *       agent would kill work in progress, and a browser tab is not what the container is for.
 * </ul>
 *
 * <h2>The debounce, and why it is not an optimisation</h2>
 *
 * <p>An editor session is a stream of requests — a keystroke is a websocket frame, a file open is
 * several — and one HTTP call per request against qits-containers would be a keepalive that costs
 * more than the container. So a workspace is touched at most once per {@code
 * qits.editor.touch-interval}, and every call inside that window returns having done nothing at all,
 * on the caller's own thread, with one map operation.
 *
 * <p><b>The window must be well under the idle deadline</b>, which is what makes the debounce safe
 * rather than a race: the sweep measures time since the last touch, so an interval near the deadline
 * would let a busy editor be stopped between two touches. The shipped 30 seconds against a deadline
 * measured in tens of minutes is three orders of magnitude of margin.
 *
 * <h2>Where the work happens</h2>
 *
 * <p>Not on the caller's thread. Both callers are on threads that must not block — a websocket frame
 * arrives on an event loop, and the daemon registry's reports arrive on a socket thread — and a touch
 * is a database read plus an HTTP call. So the claim is taken inline (that is the cheap part and it
 * has to be atomic) and the call is handed to a single thread. One thread is plenty by construction:
 * the debounce is what bounds the arrival rate, not the executor.
 *
 * <p><b>The whole thing is off while {@code qits.editor.idle-stop-after} is unset</b>, which is how
 * it ships. Nothing is idle-stopped, so nothing needs to be kept alive, and a touch would be a
 * request that could only be ignored at the far end.
 */
@ApplicationScoped
public class EditorKeepalive {

  private static final Logger LOG = Logger.getLogger(EditorKeepalive.class);

  @Inject ContainerRuntime containers;

  @Inject WorkspaceRepository workspaces;

  /**
   * The switch. Blank — the shipped value — means no container is idle-stopped, so there is nothing
   * to keep alive and every call here is a no-op. It is the same key {@link WorkspaceContainers}
   * reads to choose the policy, deliberately: a keepalive that could be on while the policy was off
   * would be a request nothing acts on.
   */
  @ConfigProperty(name = "qits.editor.idle-stop-after")
  Optional<Duration> idleStopAfter;

  /** How often one workspace may actually be touched. See the class note on the margin. */
  @ConfigProperty(name = "qits.editor.touch-interval", defaultValue = "PT30S")
  Duration touchInterval;

  /** Last touch per workspace row id, in epoch milliseconds. */
  private final Map<Long, Long> lastTouch = new ConcurrentHashMap<>();

  private ExecutorService dispatch;

  @PostConstruct
  void start() {
    dispatch =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "editor-keepalive");
              t.setDaemon(true);
              return t;
            });
  }

  void stop(@Observes ShutdownEvent shutdown) {
    if (dispatch != null) {
      dispatch.shutdownNow();
    }
  }

  /**
   * Report that this workspace is being used. Cheap, non-blocking and safe to call per request.
   *
   * <p><b>It does not ask what kind of workspace this is</b>, and that is deliberate: a touch means
   * nothing to a container whose lifetime is EXPLICIT, so buying that answer would cost a lookup to
   * save a request nobody notices. What bounds the traffic is the debounce and the switch.
   */
  public void touched(Long workspaceRowId) {
    if (workspaceRowId == null || idleStopAfter.isEmpty()) {
      return;
    }
    if (!claim(lastTouch, workspaceRowId, System.currentTimeMillis(), touchInterval.toMillis())) {
      return;
    }
    ExecutorService executor = dispatch;
    if (executor == null || executor.isShutdown()) {
      return;
    }
    try {
      executor.submit(() -> touchNow(workspaceRowId));
    } catch (RuntimeException rejected) {
      // Shutting down. A dropped keepalive is the designed failure mode — see ContainerRuntime.touch.
      LOG.debugf("dropped an editor keepalive for workspace %s: %s", workspaceRowId, rejected);
    }
  }

  /**
   * The debounce itself: whether this call is the one that gets to touch.
   *
   * <p>Static and handed its state so it can be exercised without a clock, a database or a container
   * — the property that matters is that <b>exactly one</b> of any number of concurrent callers
   * inside a window claims it, which is a statement about the compare-and-set and not about
   * anything around it. {@code compute} is what makes it one: a get-then-put would let two threads
   * both read a stale timestamp and both touch.
   */
  static boolean claim(Map<Long, Long> lastTouch, Long rowId, long nowMillis, long intervalMillis) {
    boolean[] claimed = {false};
    lastTouch.compute(
        rowId,
        (key, previous) -> {
          if (previous == null || nowMillis - previous >= intervalMillis) {
            claimed[0] = true;
            return nowMillis;
          }
          return previous;
        });
    return claimed[0];
  }

  /**
   * The touch, on the keepalive thread: resolve the row to the container name this service composed,
   * and say so to the orchestrator. Its own transaction because this thread has no session, and it
   * swallows everything for the reason the seam does.
   */
  private void touchNow(Long workspaceRowId) {
    try {
      Optional<Workspace> workspace =
          QuarkusTransaction.requiringNew()
              .call(() -> workspaces.findActiveById(workspaceRowId));
      if (workspace.isEmpty()) {
        // Resolved or gone between the claim and this call. Drop the entry so a workspace id that
        // is reused by nothing does not sit in the map forever.
        lastTouch.remove(workspaceRowId);
        return;
      }
      Workspace row = workspace.get();
      // Only the editor is ever touched here; the router's refusal still stands in front of
      // qits-containers, as at every other direct call (qits-780).
      WorkspacePlacements.requireDirectAllowed(row);
      containers.touch(containers.containerName(row.workspaceId, row.repositoryId));
    } catch (RuntimeException e) {
      LOG.debugf(e, "editor keepalive failed for workspace %s", workspaceRowId);
    }
  }

  /** Whether the keepalive does anything at all — what a caller may skip work for. */
  public boolean enabled() {
    return idleStopAfter.isPresent();
  }

  /** Drain the queue, for tests that assert a touch landed. */
  boolean awaitQuiet(long millis) throws InterruptedException {
    ExecutorService executor = dispatch;
    if (executor == null) {
      return true;
    }
    java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
    executor.submit(drained::countDown);
    return drained.await(millis, TimeUnit.MILLISECONDS);
  }
}
