package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceHistoryService;
import eu.wohlben.qits.workspaces.dto.ArchivedSessionDto;
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

  public static record ListAgentSessionsRequest() {
    public record Response(List<ArchivedSessionDto> sessions) {}
  }

  /**
   * The agent sessions that ran in this workspace, oldest first, read off the shared harness volume
   * rather than out of this context's store — which is why they answer for a resolved workspace at
   * all: the container and its {@code /workspace} volume are destroyed on resolution, the shared
   * volume is not.
   *
   * <p><b>An empty list is a valid 200.</b> A workspace where no agent ever ran, and one resolved
   * before the volume was mounted here, both legitimately have no sessions; neither is an error to
   * report and neither is distinguishable from the other.
   */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Path("/{id}/agent-sessions")
  public ListAgentSessionsRequest.Response agentSessions(@PathParam("id") Long id) {
    return new ListAgentSessionsRequest.Response(workspaceHistoryService.agentSessions(id));
  }

  public static record GetAgentTranscriptRequest() {
    public record Response(List<String> lines) {}
  }

  /**
   * One session's raw JSONL, the lines the harness itself wrote, with each subagent's sidechain
   * introduced by a synthetic marker line the frontend parser reads. Unrendered on purpose: the
   * envelopes are the render contract, and reshaping them here would fork it.
   *
   * <p>A session id that does not attribute to this workspace answers 404, exactly as one that
   * never existed does — the id is matched against the listing above and never reaches a path.
   */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @Path("/{id}/agent-sessions/{sessionId}/transcript")
  public GetAgentTranscriptRequest.Response agentTranscript(
      @PathParam("id") Long id, @PathParam("sessionId") String sessionId) {
    return new GetAgentTranscriptRequest.Response(
        workspaceHistoryService.agentTranscript(id, sessionId));
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
