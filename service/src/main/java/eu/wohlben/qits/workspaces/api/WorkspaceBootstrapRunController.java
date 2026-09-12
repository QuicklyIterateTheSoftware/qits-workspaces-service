package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.BootstrapRunService;
import eu.wohlben.qits.workspaces.dto.BootstrapRunDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * When each of this workspace's bootstrap steps last ran, and how it went.
 *
 * <p><b>The reader {@code workspace_bootstrap_run} did not have.</b> The table is written by {@code
 * WorkspaceBootstrapRunner} on every chain and was left querying-nobody when the host's
 * {@code /bootstrap-commands} routes were deleted — durable history nobody can ask for is not
 * history. This closes that, and it closes it without reopening what was deleted.
 *
 * <p><b>It is not the deleted controller by another name.</b> The old one forwarded the run verbs
 * into the container; those live on the daemon's own {@code POST /bootstrap-commands/…} and stay
 * there. This is a read of a host table, and it is the only place that table is readable from. The
 * declared chain — what the steps <em>are</em> — is likewise the daemon's {@code GET
 * /bootstrap-commands}: the client joins the two on {@code bootstrapCommandId}, which is why the id
 * is on the row and not only the display name.
 *
 * <p>One row per {@code (workspace, bootstrap command)}, overwritten on each run, so this is a
 * last-run view and never a log. The per-run output lives behind {@code commandId} — null for a
 * SKIPPED step, which spawns no command.
 */
@Path("/workspaces/{id}/bootstrap-runs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspaceBootstrapRunController {

  @Inject BootstrapRunService bootstrapRuns;

  public static record ListBootstrapRunsRequest() {
    public record Response(List<BootstrapRunDto> runs) {}
  }

  /**
   * Empty rather than 404 when the chain has never run here: a freshly created workspace has no
   * rows yet, and that is a state the Actions panel renders, not an error.
   */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes).
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:agent"})
  @GET
  @APIResponse(responseCode = "200", description = "The last run of each bootstrap step.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public ListBootstrapRunsRequest.Response list(@PathParam("id") Long id) {
    return new ListBootstrapRunsRequest.Response(bootstrapRuns.listForWorkspace(id));
  }
}
