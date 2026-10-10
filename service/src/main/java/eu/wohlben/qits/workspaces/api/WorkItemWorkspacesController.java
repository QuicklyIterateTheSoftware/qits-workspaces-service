package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.WorkItemWorkspaceDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * {@code /workspaces/api/work} — workspaces by the work item they are bound to (qits-112), the
 * reads a work list and a work item's page draw from.
 *
 * <p>A class of its own because {@code WorkspaceController} is {@code qits:admin} on the class.
 * These are reads, so a machine and an agent may make them too.
 *
 * <p>No project filter: a work item's qualified id already names its project, and the caller holds
 * the project's items.
 */
@Path("/work")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system", "qits:agent"})
public class WorkItemWorkspacesController {

  @Inject WorkspaceService workspaces;

  /** Since qits-1152 the agents are what binds a work item to a workspace. */
  @Inject eu.wohlben.qits.workspaces.control.AgentService agents;

  /**
   * The answer of both reads: one entry per workspace. The entry has a name of its own, so the
   * OpenAPI document does not renumber the other doors' {@code Entry} schemas.
   */
  public static record WorkItemWorkspaces(List<WorkItemWorkspaceEntry> entries) {
    static WorkItemWorkspaces of(List<WorkItemWorkspaceDto> rows) {
      return new WorkItemWorkspaces(rows.stream().map(WorkItemWorkspaceEntry::new).toList());
    }
  }

  /** One workspace in {@link WorkItemWorkspaces}. */
  public static record WorkItemWorkspaceEntry(WorkItemWorkspaceDto workspace) {}

  /**
   * Every open workspace bound to a work item, oldest first. Open means {@code ACTIVE}: integrated
   * and abandoned workspaces are left out, and so are workspaces bound to no work item. A work item
   * has at most one entry here.
   */
  @GET
  @Path("/workspaces")
  @Operation(operationId = "listOpenWorkspaces")
  public WorkItemWorkspaces open() {
    // The agents' entries, then any old-model workspace still bound to an item (the drain, D20).
    java.util.List<WorkItemWorkspaceDto> rows = new java.util.ArrayList<>(agents.openWorkItemViews());
    rows.addAll(workspaces.openWorkspaces());
    return WorkItemWorkspaces.of(rows);
  }

  /**
   * Every workspace of one work item, whatever its status, newest first. An item with none answers
   * an empty list.
   *
   * @param workRef the work item's entity id (a UUID), or its qualified id such as {@code
   *     qits-614}. The entity id is the stable one; a qualified id matches what was recorded at
   *     dispatch, which a later project slug change does not update
   */
  @GET
  @Path("/{workRef}/workspaces")
  @Operation(operationId = "listWorkItemWorkspaces")
  public WorkItemWorkspaces ofWorkItem(@PathParam("workRef") String workRef) {
    java.util.List<WorkItemWorkspaceDto> rows =
        new java.util.ArrayList<>(agents.workItemViews(workRef));
    rows.addAll(workspaces.workItemWorkspaces(workRef));
    return WorkItemWorkspaces.of(rows);
  }
}
