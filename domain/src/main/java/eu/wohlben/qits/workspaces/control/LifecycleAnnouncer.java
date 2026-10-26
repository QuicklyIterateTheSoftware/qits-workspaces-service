package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;

/**
 * The port the agent and workspace lifecycle is announced through (qits-1236): one of the
 * {@code eu.wohlben.qits.workspaces.events} records, built at the site that wrote the row, after
 * the write committed. {@code service/…/bus/EventstreamLifecycleAnnouncer} hands it to the bus.
 *
 * <p>A port, not the bus, for {@link PushCausation}'s reason: {@code domain} builds the vocabulary
 * and never sees a publisher. Injected as {@code Instance<LifecycleAnnouncer>}, and absent is a
 * supported configuration — nothing is announced, and nothing else changes.
 *
 * <p>An implementation returns at once and never throws: the callers are the agent scheduler's pass
 * and the daemon and runner handlers, none of which may wait on, or fail for, an announcement.
 */
public interface LifecycleAnnouncer {

  /** Announce {@code event}. Returns at once; never throws. */
  void announce(QitsEvent event);

  /**
   * Announce {@code event}, and run {@code taken} once the bus has taken it — delivered, or held by
   * its outbox — on whatever thread published it (qits-1618: a desk wait's close is marked announced
   * there). Returns at once; never throws. {@code taken} is not run when the event was never handed
   * on (the process is stopping), which is what leaves it to be published again.
   *
   * <p>The default is for an implementation that takes the event as it is announced.
   */
  default void announce(QitsEvent event, Runnable taken) {
    announce(event);
    taken.run();
  }

  /**
   * {@code event} through {@code port} when one is resolvable, and nothing otherwise. A throwing
   * implementation is logged and swallowed here too, so a call site needs no guard of its own.
   */
  static void announce(Instance<LifecycleAnnouncer> port, QitsEvent event) {
    if (port == null || !port.isResolvable()) {
      return;
    }
    try {
      port.get().announce(event);
    } catch (RuntimeException e) {
      Logger.getLogger(LifecycleAnnouncer.class)
          .warnf(e, "%s %s not announced", event.signature(), event.eventId());
    }
  }

  /**
   * As {@link #announce(Instance, QitsEvent)}, running {@code taken} once the bus has taken the
   * event. Absent port: nothing is announced and {@code taken} is not run.
   */
  static void announce(Instance<LifecycleAnnouncer> port, QitsEvent event, Runnable taken) {
    if (port == null || !port.isResolvable()) {
      return;
    }
    try {
      port.get().announce(event, taken);
    } catch (RuntimeException e) {
      Logger.getLogger(LifecycleAnnouncer.class)
          .warnf(e, "%s %s not announced", event.signature(), event.eventId());
    }
  }
}
