package eu.wohlben.qits.workspaces.wiring;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.workspaces.control.RecentEvents;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The shipped {@link RecentEvents} (qits-1668): qits-events' list API through {@link EventsLog},
 * oldest first, with the {@code qits} bearer ({@link IdpProjectsBearer}, audience qits-platform).
 * At most {@link #MAX_PAGES} pages; a look-back of minutes on one event name rarely fills one.
 */
@ApplicationScoped
public class HttpRecentEvents implements RecentEvents {

  static final int PAGE = 200;

  static final int MAX_PAGES = 5;

  @Inject @RestClient EventsLog log;

  @Inject IdpProjectsBearer bearer;

  @Override
  public List<EventFrame> since(String name, Instant since) {
    String authorization = bearer.authorization().orElse(null);
    List<EventFrame> out = new ArrayList<>();
    String cursor = null;
    for (int page = 0; page < MAX_PAGES; page++) {
      EventsLog.Page answer = log.list(authorization, "asc", name, since.toString(), PAGE, cursor);
      if (answer == null || answer.events() == null) {
        break;
      }
      for (EventsLog.Event e : answer.events()) {
        out.add(new EventFrame(e.id(), e.name(), e.occurredAt(), e.payload(), null, null, null));
      }
      cursor = answer.nextCursor();
      if (cursor == null || answer.events().isEmpty()) {
        break;
      }
    }
    return out;
  }
}
