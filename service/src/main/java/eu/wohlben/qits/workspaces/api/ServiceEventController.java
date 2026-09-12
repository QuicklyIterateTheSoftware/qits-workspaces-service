package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.ServiceEventService;
import eu.wohlben.qits.workspaces.dto.ServiceEventDto;
import eu.wohlben.qits.workspaces.entity.ServiceEventSeverity;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;

/**
 * The durable service-event feed (replacing the old in-memory per-workspace endpoint): paginated,
 * newest first, filterable by workspace, severity, time, and source. Events survive JVM restarts,
 * so last night's crash and what the classifier saw remain inspectable.
 */
@Path("/service-events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class ServiceEventController {

  @Inject ServiceEventService serviceEventService;

  public static record ListServiceEventsRequest() {
    public record Response(List<ServiceEventDto> events) {}
  }

  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  public ListServiceEventsRequest.Response list(
      @QueryParam("repoId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("severity") ServiceEventSeverity severity,
      @QueryParam("since") Instant since,
      @QueryParam("source") String source,
      @QueryParam("page") @DefaultValue("0") @Min(0) int page,
      @QueryParam("pageSize") @DefaultValue("50") @Min(1) @Max(500) int pageSize) {
    return new ListServiceEventsRequest.Response(
        serviceEventService.query(repoId, workspaceId, severity, since, source, page, pageSize));
  }
}
