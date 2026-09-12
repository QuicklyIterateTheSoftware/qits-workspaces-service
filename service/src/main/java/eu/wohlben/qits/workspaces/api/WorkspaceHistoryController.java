package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceHistoryService;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDetailDto;
import eu.wohlben.qits.workspaces.dto.WorkspaceHistoryDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The workspace history for a repository: every workspace (active + resolved) as a browsable record
 * of the work that flowed through the repo. Keyed by the surrogate id, since workspace ids are
 * reusable once resolved.
 */
@Path("/history")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspaceHistoryController {

  @Inject WorkspaceHistoryService workspaceHistoryService;

  public static record ListHistoryRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorkspaceHistoryDto workspace) {}
    }
  }

  /** The repository is a real filter here — the collection is "what flowed through this repo". */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  public ListHistoryRequest.Response list(@QueryParam("repositoryId") String repositoryId) {
    var entries =
        workspaceHistoryService.list(repositoryId).stream()
            .map(ListHistoryRequest.Response.Entry::new)
            .toList();
    return new ListHistoryRequest.Response(entries);
  }

  public static record GetHistoryRequest() {
    public record Response(WorkspaceHistoryDetailDto workspace) {}
  }

  /**
   * A history row was always addressed by the surrogate id; the repository segment was decoration on
   * the item routes and only ever a filter on the collection above.
   */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Path("/{id}")
  public GetHistoryRequest.Response get(@PathParam("id") Long id) {
    return new GetHistoryRequest.Response(workspaceHistoryService.get(id));
  }

  public static record UpdateHistoryRequest(String preamble, String result) {
    public record Response(WorkspaceHistoryDetailDto workspace) {}
  }

  @PATCH
  @Path("/{id}")
  public UpdateHistoryRequest.Response update(
      @PathParam("id") Long id, UpdateHistoryRequest request) {
    return new UpdateHistoryRequest.Response(
        workspaceHistoryService.updateNarrative(id, request.preamble(), request.result()));
  }
}
