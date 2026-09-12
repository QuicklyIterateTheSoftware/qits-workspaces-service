package eu.wohlben.qits.workspaces.api;

import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestStreamElementType;

/**
 * The app-wide Server-Sent-Events channel — the global sibling of {@code WorkspaceEventsController}
 * (one workspace) and {@code RepositoryEventsController} (one repository). Emits payload-free
 * <em>invalidation hints</em> for changes that a cross-repository view cares about; currently just
 * {@code agent-activity}, which the project detail route maps to invalidating every cached
 * workspace list so its per-repository agent-activity bars re-sort live. One connection serves the
 * whole route regardless of how many repositories the project has — deliberately NOT one repository
 * channel per repo, which would burn a browser connection each.
 *
 * <p>Reuses the existing {@link WorkspaceEventBroadcaster} keyed by {@code (null, null)}: the key
 * ({@code "null/null"}) can never collide with a repository subscriber's {@code "repoId/null"} or a
 * workspace subscriber's {@code "repoId/workspaceId"} (repo ids are UUIDs). Producers fan a hint
 * out to every channel whose scope contains the change (see {@code
 * WorkspaceDaemonRegistry#onAgentActivity}). Same heartbeat/reconnect story as the siblings.
 */
@Path("/events")
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class GlobalEventsController {

  @Inject WorkspaceEventBroadcaster broadcaster;

  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.TEXT_PLAIN)
  @Operation(hidden = true)
  public Multi<String> events() {
    return broadcaster.withHeartbeat(broadcaster.subscribe(null, null));
  }
}
