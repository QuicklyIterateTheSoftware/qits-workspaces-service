package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Test double for {@link LifecycleAnnouncer} (qits-1236): records every event, publishes nothing.
 * Recording only, so being a bean for every {@code @QuarkusTest} in the module changes no other
 * test. Never cleared: the scheduler runs background passes for other tests' rows too, so a test
 * selects its own events by id ({@link #of}) rather than counting the whole list.
 */
@ApplicationScoped
public class RecordingLifecycleAnnouncer implements LifecycleAnnouncer {

  // Private, with methods: a test holds a client proxy, and a field read on it reads the proxy's.
  private final List<QitsEvent> events = new CopyOnWriteArrayList<>();

  /** A one-shot hook for the first event its predicate selects; null = none, the usual case. */
  private final AtomicReference<Hook> hook = new AtomicReference<>();

  private record Hook(Predicate<QitsEvent> which, Consumer<QitsEvent> run) {}

  @Override
  public void announce(QitsEvent event) {
    Hook h = hook.get();
    if (h != null && h.which().test(event) && hook.compareAndSet(h, null)) {
      h.run().accept(event);
    }
    events.add(event);
  }

  /**
   * Run {@code run} once, inside the {@link #announce} of the first event {@code which} selects,
   * before it is recorded and so before {@link LifecycleAnnouncer#announce(QitsEvent, Runnable)}'s
   * {@code taken} marks it — the moment a concurrent sweep could read the row unmarked (qits-1867).
   * One-shot and selected by the test's own ids, so a background pass over another test's rows
   * never runs it; it runs on whichever thread announces.
   */
  public void beforeNext(Predicate<QitsEvent> which, Consumer<QitsEvent> run) {
    hook.set(new Hook(which, run));
  }

  /** The recorded events of {@code type} that {@code which} selects, in announcement order. */
  public <T extends QitsEvent> List<T> of(Class<T> type, Predicate<T> which) {
    return events.stream().filter(type::isInstance).map(type::cast).filter(which).toList();
  }

  /**
   * {@link #of}, waited for until there are at least {@code count} — for an announcement made on
   * another thread (a background start, a runner's daemon wait) after a write the test can see.
   */
  public <T extends QitsEvent> List<T> await(Class<T> type, Predicate<T> which, int count)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + 15_000;
    while (of(type, which).size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    return of(type, which);
  }
}
