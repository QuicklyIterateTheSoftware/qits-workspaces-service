package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code POST /workspaces/api/branches/resolution?repositoryId=<id>} — the branch this repository
 * held has been <b>released and deleted on the git host</b>; resolve whatever workspace was standing
 * on it.
 *
 * <p><b>What it is for.</b> qits-projects' Auto Release deletes each released branch through a
 * qits-githost primitive that writes the ref in core and fires no event, so nothing reaches this
 * service. The workspace standing on that branch stays ACTIVE forever — holding a container, a
 * volume and a commissioned credential for a branch that no longer exists — until somebody notices
 * and abandons it by hand. That was measured live on 2026-09-05, on the storage-creep wrapper
 * workspace. So the release tells us, beside the deletion, and this is where it says so.
 *
 * <p><b>It is a workspace-lifecycle door that a release happens to call, and never a release
 * door.</b> The release flow left this service on 2026-09-03 and stays gone (AGENTS.md, "The release
 * door left, and what stayed"). Nothing here merges, stamps, tags, pushes or announces; the {@code
 * target} and {@code commit} in the body are the release's version and sha carried onto the history
 * event so a timeline entry can be followed back, and are read as nothing else. Do not grow a second
 * verb here on the grounds that a release already calls it.
 *
 * <p><b>A new class rather than two methods on {@link BranchController}</b>, and that is mechanical
 * as well as semantic. {@code BranchController} is a person's door at {@code
 * @RolesAllowed("qits:admin")} on the class, and a class-level role is inherited by every non-private
 * method of the bean and enforced on ArC's INTERNAL calls too — so widening that class for a machine
 * caller is the 403 of 2026-09-03 waiting to happen in the other direction. The roles here are this
 * door's own, on a class of its own, exactly as {@link GcController} carries the machine roles for
 * the nightly sweep.
 *
 * <p>The repository is <em>scope</em> in the query string and the branch is in the body, for {@link
 * BranchController}'s reason: a branch has no id of its own, so the repository narrows and the body
 * names one. The answer is the record itself and not a bare {@code Response} — an entity inside an
 * untyped {@code Response} is invisible to native-image indexing, and this module compiles to a
 * binary.
 */
@Path("/branches/resolution")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// qits:system beside the admin role, like GcController: the caller is qits-projects' release
// executor, a machine. Every method on this class is meant for both, so the class list and the
// bodies agree by construction and no method widens what the class states.
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class BranchResolutionController {

  @Inject WorkspaceService workspaceService;

  /**
   * @param branch the branch the release consumed and deleted
   * @param target the release version, recorded on the resolution's history event
   * @param commit the released sha, recorded on the same event
   * @param result a short note kept on the resolved row
   */
  public static record ResolveReleasedBranchRequest(
      @NotBlank String branch, String target, String commit, String result) {}

  /**
   * Resolve the ACTIVE workspace on {@code branch} as INTEGRATED, tearing its container, volume and
   * credential down without touching the ref — it is already gone.
   *
   * <p>A branch with no workspace answers 200 {@code resolved:false}: that is the ordinary case, not
   * an error, and it is what a second call after a resolution answers too. The caller is best-effort
   * and may retry.
   */
  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "Resolved, or `resolved:false` when no active workspace stood on the branch — the"
              + " ordinary case, and what a repeated call answers.")
  @APIResponse(
      responseCode = "400",
      description =
          "A blank branch, or the repository's main workspace — refused on both belts: the row has"
              + " no parent, or the branch is the repository's default branch.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public WorkspaceService.BranchResolution resolveReleasedBranch(
      @QueryParam("repositoryId") String repositoryId,
      @Valid ResolveReleasedBranchRequest request) {
    return workspaceService.resolveReleasedBranch(
        repositoryId, request.branch(), request.target(), request.commit(), request.result());
  }
}
