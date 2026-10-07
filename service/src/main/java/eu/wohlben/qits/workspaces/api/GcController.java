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
 * the hard refusals — the main branch, anything an active workspace stands on — live there rather
 * than here, so no caller shapes a request around them. {@code keepPrefixes} here can only widen
 * the protection.
 *
 * <p>{@code qits:system} beside the admin role, unlike {@link BranchController}: the scheduled
 * caller is a machine. A dry run examines and judges exactly as a real one does and deletes
 * nothing — the orchestrator's nightly run passes its own dry-run flag straight through.
 */
@Path("/gc")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
public class GcController {

  @Inject WorkspaceService workspaceService;

  public static record SweepBranchesRequest(
      boolean dryRun,
      @NotNull List<WorkspaceService.SweepRepository> repositories,
      List<String> keepPrefixes) {}

  @POST
  @Path("/branches")
  public WorkspaceService.BranchSweepReport sweepBranches(@Valid SweepBranchesRequest request) {
    Set<String> keep =
        request.keepPrefixes() == null ? Set.of() : Set.copyOf(request.keepPrefixes());
    return workspaceService.sweepMergedBranches(request.repositories(), keep, request.dryRun());
  }
}
