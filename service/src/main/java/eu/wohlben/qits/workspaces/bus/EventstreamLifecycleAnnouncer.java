package eu.wohlben.qits.workspaces.bus;

import eu.wohlben.qits.eventstream.CausationScope;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.workspaces.control.LifecycleAnnouncer;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * {@link LifecycleAnnouncer} over the bus (qits-1236): the agent and workspace lifecycle events
 * {@code control} builds, handed to {@link QitsEventBus} — qits-ci's {@code
 * RunnerLifecycleAnnouncer}, for the same reasons and in the same shape.
 *
 * <p><b>Never on the caller's thread.</b> {@link QitsEventBus#publish} never throws but can block
 * for its publish timeout while qits-events is unreachable, and the callers are the agent
 * scheduler's one pass thread, a daemon's frame handler and a runner's result handler: an outage of
 * the bus must not stall placement, or leave a runner's {@code launched} waiting. So the event is
 * built on the caller's thread — which fixes its facts and its instant — and handed to <b>one</b>
 * publishing thread; the call returns at once.
 *
 * <p><b>One thread, not a pool, because the order is part of what is said.</b> {@code AgentPlaced}
 * before {@code AgentStarted}, {@code AgentObsolete} before {@code AgentRemoved}: a pool could
 * put a removal on the wire before the obsolescence it follows. The envelope's {@code occurredAt}
 * is the event's own either way; the order is for a subscriber reading the stream live.
 *
 * <p><b>The cause is read on the caller's thread and passed on explicitly</b>, because {@link
 * CausationScope} is a thread-local and does not follow the hop. A door's request (a discard, a
 * release resolving a branch) keeps its edge; a scheduler pass publishes roots, which is what it
 * is.
 *
 * <p><b>A request context around each publish.</b> The publishing thread is a bare daemon thread,
 * and when the inline PUT does not land the outbox writes its row in a transaction of its own —
 * qits-deployments' {@code DeployEventAnnouncer} found that path wants an active request context on
 * a bare thread and puts {@code @ActivateRequestContext} on its methods. That annotation cannot
 * reach a lambda on an executor (an interceptor binds only a call through the bean's proxy), so the
 * context is activated by hand, and only when none is active.
 *
 * <p><b>Taken, for a caller that keeps a record of it</b> (qits-1618). {@link #announce(QitsEvent,
 * Runnable)} runs its callback on the publishing thread once {@link QitsEventBus#publish} returned —
 * the event delivered, or held by the outbox — still inside that request context. A desk wait's
 * close marks itself announced there; one never taken is published again by the next sweep.
 *
 * <p><b>What stopping costs.</b> On shutdown the queue gets a few seconds to drain; whatever is
 * still queued after that is dropped with a WARN. An event handed to the bus is the outbox's from
 * there.
 */
@ApplicationScoped
public class EventstreamLifecycleAnnouncer implements LifecycleAnnouncer {

  private static final Logger LOG = Logger.getLogger(EventstreamLifecycleAnnouncer.class);

  /** How long a stopping process waits for the queue to drain. */
  private static final long DRAIN_SECONDS = 5;

  @Inject QitsEventBus bus;

  private final ExecutorService publisher =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "workspaces-lifecycle-events");
            t.setDaemon(true);
            return t;
          });

  /** Queue one event for the publishing thread, with the cause read here, on the caller's thread. */
  @Override
  public void announce(QitsEvent event) {
    announce(event, null);
  }

  /** {@link #announce(QitsEvent)}, then {@code taken} on the publishing thread once the bus took it. */
  @Override
  public void announce(QitsEvent event, Runnable taken) {
    UUID parent = CausationScope.current();
    try {
      publisher.execute(() -> publish(event, parent, taken));
    } catch (RejectedExecutionException stopped) {
      LOG.warnf(
          "%s %s not published: this process is stopping", event.signature(), event.eventId());
    }
  }

  private void publish(QitsEvent event, UUID parent, Runnable taken) {
    ManagedContext requestContext = Arc.container().requestContext();
    boolean activated = !requestContext.isActive();
    if (activated) {
      requestContext.activate();
    }
    try {
      bus.publish(event, parent);
      if (taken != null) {
        try {
          taken.run();
        } catch (RuntimeException e) {
          LOG.warnf(e, "%s %s was published, but not recorded as such", event.signature(), event.eventId());
        }
      }
    } finally {
      if (activated) {
        requestContext.terminate();
      }
    }
  }

  @PreDestroy
  void drain() {
    publisher.shutdown();
    try {
      if (!publisher.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
        LOG.warnf(
            "%d lifecycle event(s) still queued after %ds; dropped",
            publisher.shutdownNow().size(), DRAIN_SECONDS);
      }
    } catch (InterruptedException e) {
      publisher.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
