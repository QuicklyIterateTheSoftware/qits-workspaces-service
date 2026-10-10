package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.AgentService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code POST /workspaces/api/branches/resolution?repositoryId=<id>} — <b>a release consumed this
 * branch</b>. The one door a release calls, and not a release door: qits-projects deletes each
 * released branch through a githost primitive that fires no event, and says so here.
 *
 * <p>Since qits-1152 what it resolves are <b>agents</b>: every ACTIVE agent owning the branch — as
 * its wrapper branch, or as a branch it pushed — is marked OBSOLETE, and the cleanup sweep removes
 * it once its item is VERIFIED and it has been idle (D4). A workspace of the old model standing on
 * the branch is still resolved as it was (the drain, D20), answering {@code agentIds: []}.
 *
 * <p>A class of its own at {@code {qits:admin, qits:system}}, for the reason {@code
 * AgentDispatchController} gives: {@code BranchController}'s class role is a person's, and a
 * class-level {@code @RolesAllowed} binds ArC's internal calls too.
 */
@Path("/branches/resolution")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
public class BranchResolutionController {

  @Inject AgentService agents;

  /**
   * @param target the release version that consumed the branch, logged
   * @param commit the released sha, recorded on an old-model workspace's history event
   */
  public static record ResolveReleasedBranchRequest(
      @NotBlank String branch, String target, String commit, String result) {}

  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "Resolved, or `resolved:false` when nothing owned the branch — the ordinary case, and"
              + " what a repeated call answers. `agentIds` are the agents marked OBSOLETE.")
  @APIResponse(
      responseCode = "400",
      description =
          "A blank branch, or an old-model main workspace (refused on both belts: no parent, or"
              + " the repository's default branch).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "resolveReleasedBranch")
  public AgentService.Resolution resolveReleasedBranch(
      @QueryParam("repositoryId") String repositoryId,
      @Valid ResolveReleasedBranchRequest request) {
    return agents.resolveReleasedBranch(
        repositoryId, request.branch(), request.target(), request.commit(), request.result());
  }
}
