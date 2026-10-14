package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.QitsEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

  @Override
  public void announce(QitsEvent event) {
    events.add(event);
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
