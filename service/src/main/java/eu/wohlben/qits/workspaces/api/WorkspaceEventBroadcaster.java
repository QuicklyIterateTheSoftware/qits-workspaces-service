package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceChangeHint;
import eu.wohlben.qits.workspaces.control.WorkspaceResolver;
import eu.wohlben.qits.workspaces.entity.Workspace;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-JVM fan-out from {@link WorkspaceChangeHint}s (fired in {@code domain} via CDI async
 * events) to per-workspace SSE streams. Each subscribed workspace tab gets a {@link
 * BroadcastProcessor}; the {@code @ObservesAsync} observer routes each hint — after a
 * per-(workspace, topic) debounce — into the matching processor as a lowercase topic string, which
 * the {@link WorkspaceEventsController} emits as an SSE {@code data:} frame.
 *
 * <p>Debounce is leading-edge + trailing: the first hint in a quiet window emits immediately (a
 * bootstrap step settling feels instant), further hints during the {@code qits.events.debounce-ms}
 * window coalesce into at most one trailing emit, and a continuous burst (the SPA flushing OTLP
 * every 1s) converges to ≤1 emit/s per topic instead of being chattier than the poll it replaces. A
 * missed or dropped hint self-heals: the frontend re-fetches on the next hint or on reconnect, so
 * overflow is simply dropped.
 */
@ApplicationScoped
public class WorkspaceEventBroadcaster {

  private static final Logger LOG = Logger.getLogger(WorkspaceEventBroadcaster.class);

  @Inject WorkspaceResolver workspaceResolver;

  @ConfigProperty(name = "qits.events.debounce-ms", defaultValue = "1000")
  long debounceMillis;

  private final Map<String, BroadcastProcessor<String>> processors = new ConcurrentHashMap<>();
  private final Map<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();

  private record DebounceKey(String workspaceKey, WorkspaceChangeHint.Topic topic) {}

  /**
   * Present while a debounce window is open; {@code trailing} records a hint arrived mid-window.
   */
  private static final class Window {
    boolean trailing;
  }

  private final Map<DebounceKey, Window> windows = new ConcurrentHashMap<>();

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "workspace-events-debounce");
            thread.setDaemon(true);
            return thread;
          });

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * The live topic stream for one workspace, hot and shared: created on first subscriber, dropped
   * on last cancellation. Overflow is dropped (hints are recoverable).
   */
  public Multi<String> subscribeToWorkspace(Long id) {
    // Resolved purely to 404 an unknown workspace — the channel key is the id itself.
    workspaceResolver.resolveActive(id);
    return subscribe(null, id);
  }

  /**
   * The channel for a scope key. The workspace scope is reached through {@link
   * #subscribeToWorkspace}; the repository ({@code (repoId, null)}) and global ({@code (null,
   * null)}) scopes have no workspace to name and address themselves directly.
   */
  public Multi<String> subscribe(String repoId, Long workspaceRowId) {
    String key = key(repoId, workspaceRowId);
    return Multi.createFrom()
        .deferred(
            () -> {
              BroadcastProcessor<String> processor =
                  processors.computeIfAbsent(key, k -> BroadcastProcessor.create());
              subscriberCounts.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
              AtomicBoolean released = new AtomicBoolean();
              Runnable release =
                  () -> {
                    if (released.compareAndSet(false, true)) {
                      AtomicInteger count = subscriberCounts.get(key);
                      if (count != null && count.decrementAndGet() <= 0) {
                        subscriberCounts.remove(key);
                        processors.remove(key);
                      }
                    }
                  };
              return processor
                  .onOverflow()
                  .drop()
                  .onCancellation()
                  .invoke(release)
                  .onTermination()
                  .invoke(release);
            });
  }

  /** Routes a fired hint into its workspace's stream, through the debounce gate. */
  void onHint(@ObservesAsync WorkspaceChangeHint hint) {
    String key = key(hint.repoId(), hint.workspaceRowId());
    DebounceKey debounceKey = new DebounceKey(key, hint.topic());
    boolean emitNow = false;
    synchronized (windows) {
      Window window = windows.get(debounceKey);
      if (window == null) {
        windows.put(debounceKey, new Window());
        emitNow = true;
        scheduler.schedule(() -> closeWindow(debounceKey), debounceMillis, TimeUnit.MILLISECONDS);
      } else {
        window.trailing = true;
      }
    }
    if (emitNow) {
      emit(key, hint.topic());
    }
  }

  private void closeWindow(DebounceKey debounceKey) {
    boolean emitTrailing = false;
    synchronized (windows) {
      Window window = windows.get(debounceKey);
      if (window != null && window.trailing) {
        window.trailing = false;
        emitTrailing = true;
        scheduler.schedule(() -> closeWindow(debounceKey), debounceMillis, TimeUnit.MILLISECONDS);
      } else {
        windows.remove(debounceKey);
      }
    }
    if (emitTrailing) {
      emit(debounceKey.workspaceKey(), debounceKey.topic());
    }
  }

  private void emit(String workspaceKey, WorkspaceChangeHint.Topic topic) {
    BroadcastProcessor<String> processor = processors.get(workspaceKey);
    if (processor == null) {
      return; // no subscribers for this workspace — nothing to notify, hint self-heals on connect
    }
    try {
      // Wire name: PROMPT_ATTACHMENTS -> "prompt-attachments" (the frontend's topic contract).
      processor.onNext(topic.name().toLowerCase().replace('_', '-'));
    } catch (RuntimeException e) {
      LOG.debugf(e, "Dropped workspace hint %s for %s", topic, workspaceKey);
    }
  }

  /**
   * The channel key. A workspace channel is named by the workspace id alone — it needs no
   * repository beside it, and the three scopes are prefixed so they can never collide.
   */
  private static String key(String repoId, Long workspaceRowId) {
    if (workspaceRowId != null) {
      return "w:" + workspaceRowId;
    }
    return repoId != null ? "r:" + repoId : "g";
  }

  /**
   * Merge the ~25s SSE {@code ping} heartbeat into a hint stream — shared by the workspace and
   * repository events controllers so both keep idle connections alive identically through the dev
   * proxies (the frontend ignores the {@code ping} topic). {@code EventSource} reconnects on its
   * own, so no replay protocol is needed.
   */
  public Multi<String> withHeartbeat(Multi<String> hints) {
    Multi<String> heartbeat =
        Multi.createFrom()
            .ticks()
            .every(Duration.ofSeconds(25))
            .onOverflow()
            .drop()
            .map(tick -> "ping");
    return Multi.createBy().merging().streams(hints, heartbeat);
  }

  /** Test seam: how many workspace channels currently have at least one subscriber. */
  int openChannelCount() {
    return processors.size();
  }
}
