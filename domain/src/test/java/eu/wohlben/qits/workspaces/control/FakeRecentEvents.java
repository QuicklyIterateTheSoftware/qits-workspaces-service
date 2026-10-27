package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The event log the look-back reads (qits-1668), as qits-events answers it: the events of the name
 * at or after {@code since}, oldest first. Empty unless a test adds to it; {@link #reset} after.
 */
@ApplicationScoped
public class FakeRecentEvents implements RecentEvents {

  private final List<EventFrame> log = new CopyOnWriteArrayList<>();

  private final List<Instant> asked = new CopyOnWriteArrayList<>();

  private volatile RuntimeException failure;

  @Override
  public List<EventFrame> since(String name, Instant since) {
    asked.add(since);
    if (failure != null) {
      throw failure;
    }
    List<EventFrame> out = new ArrayList<>();
    for (EventFrame frame : log) {
      if (frame.name().equals(name) && !frame.occurredAt().isBefore(since)) {
        out.add(frame);
      }
    }
    out.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
    return out;
  }

  public void add(String id, String name, Instant occurredAt, String payload) {
    log.add(new EventFrame(id, name, occurredAt, payload, null, null, null));
  }

  public void failWith(RuntimeException e) {
    failure = e;
  }

  /** The {@code since} of every read, in order. */
  public List<Instant> asked() {
    return List.copyOf(asked);
  }

  public void reset() {
    log.clear();
    asked.clear();
    failure = null;
  }
}
