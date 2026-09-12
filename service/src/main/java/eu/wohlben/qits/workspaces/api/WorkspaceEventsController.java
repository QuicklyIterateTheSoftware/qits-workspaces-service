package eu.wohlben.qits.workspaces.api;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The single Server-Sent-Events channel for one workspace's detail route. Emits payload-free
 * <em>invalidation hints</em> — a topic name per frame ({@code services}, {@code service-events},
 * {@code telemetry}, {@code commands}) — which the frontend maps to a TanStack Query invalidation,
 * so data keeps flowing through the unchanged REST endpoints. Replaces eight free-running polls
 * with fetch-on-signal: an idle workspace produces zero traffic. A ~25s {@code ping} heartbeat
 * keeps idle connections alive through the dev proxies; {@code EventSource} reconnects
 * automatically, and the frontend re-syncs everything on reconnect, so no replay/{@code
 * Last-Event-ID} protocol is needed.
 */
@Path("/workspaces/{id}/events")
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspaceEventsController {

  @Inject WorkspaceEventBroadcaster broadcaster;

  /**
   * {@code @Blocking} because subscribing now resolves the workspace id against the database, and
   * this method would otherwise run on the IO thread. Only the subscribe is blocking — the returned
   * {@link Multi} streams as it did before.
   */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Operation(hidden = true)
  @Blocking
  public Multi<String> events(@PathParam("id") Long id) {
    return broadcaster.withHeartbeat(broadcaster.subscribeToWorkspace(id));
  }
}
