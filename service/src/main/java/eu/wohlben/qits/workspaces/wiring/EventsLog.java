package eu.wohlben.qits.workspaces.wiring;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-events' list API, read by {@link HttpRecentEvents} for a wait's look-back (qits-1668):
 * {@code GET /events/api/events?order=asc&name=..&since=..&limit=..[&cursor=..]}. The records hold
 * only what the look-back reads; the rest of an answer is ignored.
 */
@Path("/events/api/events")
@RegisterRestClient(configKey = "qits-events")
@RegisterForReflection(targets = {EventsLog.Page.class, EventsLog.Event.class})
public interface EventsLog {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  Page list(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @QueryParam("order") String order,
      @QueryParam("name") String name,
      @QueryParam("since") String since,
      @QueryParam("limit") int limit,
      @QueryParam("cursor") String cursor);

  /** One page; {@code nextCursor} is null at the end. */
  record Page(List<Event> events, String nextCursor) {}

  record Event(String id, String name, Instant occurredAt, String payload) {}
}
