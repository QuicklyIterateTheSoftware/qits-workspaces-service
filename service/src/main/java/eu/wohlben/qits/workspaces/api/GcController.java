package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The orchestrator's door into branch cleanup: {@code POST /workspaces/api/gc/branches} sweeps
 * every fully-merged <b>plain</b> branch across the repositories the caller names.
 *
 * <p><b>The repository list is an input, not a lookup</b>, on the pin pattern the gc process
 * already runs on: qits-platform-orchestrator is the component holding a credential for every
 * peer, so it reads qits-projects' catalogue once and hands the rows here — this context keeps
 * holding repository ids as strings it did not mint. An absent or empty list sweeps nothing and
 * says so, rather than guessing at an enumeration this service does not own.
 *
 * <p><b>What a sweep may touch is decided in {@link WorkspaceService#sweepMergedBranches}</b>, and
 * the hard refusals — the main branch, anything a legacy workspace row or an ACTIVE agent's
 * wrapper branch (qits-1152) stands on — live there rather than here, so no caller shapes a
 * request around them. {@code keepPrefixes} here can only widen the protection.
 *
 * <p>{@code qits:system} beside the admin role, unlike {@link BranchController}: the scheduled
 * caller is a machine. A dry run examines and judges exactly as a real one does and deletes
 * nothing — the orchestrator's nightly run passes its own dry-run flag straight through.
 *
 * <p><b>{@code qits:desk} may dry-run it and nothing more</b> (qits-1596). A real sweep deletes
 * branches on the git host, and every ref deletion is a push — a right the delivery desk does not
 * hold, whoever's credential the push would travel on. A dry run judges and deletes nothing, so it
 * is admitted unbound, as the sweep is a platform-wide door.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({
  "qits:admin",
  "qits:admin-agent",
  "qits:system",
  "qits:desk"
})
public class GcController {

  @Inject WorkspaceService workspaceService;

  @Inject DeskBinding desk;

  public static record SweepBranchesRequest(
      boolean dryRun,
      @NotNull List<WorkspaceService.SweepRepository> repositories,
      List<String> keepPrefixes) {}

  @POST
  @Path("/branches")
  @Operation(operationId = "sweepBranches")
  @APIResponse(responseCode = "200", description = "The sweep's report; a dry run deleted nothing.")
  @APIResponse(
      responseCode = "403",
      description =
          "A `qits:desk` caller asking for a real sweep: deleting a branch is a push, and the"
              + " delivery desk may only dry-run. Nothing was deleted.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public WorkspaceService.BranchSweepReport sweepBranches(@Valid SweepBranchesRequest request) {
    if (!request.dryRun()) {
      desk.refuse(
          "The delivery desk may only dry-run the branch sweep: deleting a branch is a push.");
    }
    Set<String> keep =
        request.keepPrefixes() == null ? Set.of() : Set.copyOf(request.keepPrefixes());
    return workspaceService.sweepMergedBranches(request.repositories(), keep, request.dryRun());
  }
}
