package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.security.identity.SecurityIdentity;
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
 * Branch-level integration and cleanup: the operations the branch list offers on a row that is not
 * (or no longer) a workspace of its own.
 *
 * <p>These sit under {@code /branches} rather than under {@code /workspaces/{id}} because they are
 * keyed by <em>branch name</em>: the source of an integration needs no workspace of its own (a plain
 * branch is merged from its origin ref), and a cleanup target may have outlived the workspace that
 * created it. That is what separates them from {@link WorkspaceController}'s {@code merge}/{@code
 * discard}, which address a workspace by id.
 *
 * <p><b>There was a release door here and it is gone.</b> {@code /branches/release} created release
 * requests in qits-projects and {@code /branches/execute-release} performed the landing — merge,
 * stamp, tag, atomic push, promotion. A release is qits-projects' own procedure now: it folds the
 * request's sources on a backing branch through qits-githost's git primitives, stamps and bumps
 * there, and the tag is the release. Ask qits-projects for a release request; nothing here forwards
 * one.
 *
 * <p>The repository is <em>scope</em>, in the query string, for the same reason it is on {@link
 * WorkspaceController}'s collection: this context does not own repositories — it holds a repository
 * id as a string, with no foreign key and no join, in a different database — so a {@code
 * /repositories/{repoId}/…} segment would assert a containment the model deliberately does not
 * have. Unlike a workspace, a branch has no id of its own, so the pair (repository, branch name) is
 * what names one: the repository narrows, the body says which branch.
 *
 * <p>The monorepo served both from its {@code RepositoryController}, as thin forwards into {@link
 * WorkspaceService}. On extraction the repository half went to {@code qits-projects}, which cut them
 * rather than keep forwarding into another context (see the seam note there) — so they live here,
 * with the service they were always calling.
 */
@Path("/branches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// ADMIN AT CLASS LEVEL, AND THE SAME REFUSAL IN THE METHOD BODIES. Both doors left here are a
// person's: merging a branch and deleting one are what somebody clicks in the branch list, and no
// machine on this platform asks for either — the machine-and-person release doors left with the
// release flow (qits-projects executes releases now). The 403 of 2026-09-03 is root-caused: a
// class-level @RolesAllowed is inherited by EVERY non-private method of the bean and enforced on
// INTERNAL calls too, because ArC's subclass overrides them — so an endpoint that widens the
// class's roles must call only private members. With every door here person-only, the class list
// and the method bodies agree by construction; the body-level refusal stays as the belt that
// survives any future widening. `qits:admin-agent` is admitted too (qits-628 follow-up); remove it
// here if this door must stay human-only.
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
public class BranchController {

  /**
   * The person-only refusal, programmatic on purpose — see the class annotation's comment. A
   * machine's token carries {@code qits:system} and never {@code qits:admin}; a person (or a
   * commissioned context acting as one) carries {@code qits:admin}. {@code qits:admin-agent} is
   * admitted too (qits-628 follow-up); remove it here if this door must stay human-only.
   */
  private void requireAdmin(String door) {
    if (!identity.getRoles().contains("qits:admin")
        && !identity.getRoles().contains("qits:admin-agent")) {
      throw new jakarta.ws.rs.ForbiddenException(
          door + " is a person's door: it wants qits:admin, and this token carries only machine"
              + " roles");
    }
  }

  @Inject WorkspaceService workspaceService;

  @Inject SecurityIdentity identity;

  public static record MergeBranchRequest(@NotBlank String source, String target, String result) {
    /**
     * @param cleanedUp whether the integrated source workspace+branch was removed afterwards (it
     *     was fully merged with no dependents)
     */
    public record Response(
        String commitHash, boolean hasConflicts, String output, boolean cleanedUp) {}
  }

  /**
   * Merge {@code source} into {@code target}, defaulting to the repo's main branch.
   *
   * <p>409 {@code RELEASE_REQUIRED} when the target resolves to that default branch: this service
   * does not write it at all — a release request in qits-projects does — and {@code
   * /workspaces/{id}/integrate} is the door for a plain merge into a parent.
   */
  @POST
  @Path("/merge")
  @APIResponse(responseCode = "200", description = "Merged.")
  @APIResponse(
      responseCode = "400",
      description = "A blank, dash-leading or self-referential branch name.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "The target is the repository's default branch. `reason` is RELEASE_REQUIRED and the"
              + " message names the release flow that does write it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public MergeBranchRequest.Response mergeBranch(
      @QueryParam("repositoryId") String repoId, @Valid MergeBranchRequest request) {
    requireAdmin("/branches/merge");
    var result =
        workspaceService.mergeBranch(repoId, request.source(), request.target(), request.result());
    return new MergeBranchRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output(), result.cleanedUp());
  }

  public static record CleanupBranchRequest(@NotBlank String branch, String result) {
    public record Response(boolean success) {}
  }

  /**
   * Remove a branch (and its workspace, if any) when that loses no work. The UI asks for no
   * confirmation, so eligibility is re-checked in the service and an ineligible branch yields a 400.
   */
  @POST
  @Path("/cleanup")
  @APIResponse(responseCode = "200", description = "Removed.")
  @APIResponse(
      responseCode = "400",
      description =
          "The branch is not eligible: uncommitted changes, unmerged commits, or dependents.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public CleanupBranchRequest.Response cleanupBranch(
      @QueryParam("repositoryId") String repoId, @Valid CleanupBranchRequest request) {
    requireAdmin("/branches/cleanup");
    workspaceService.cleanupBranch(repoId, request.branch(), request.result());
    return new CleanupBranchRequest.Response(true);
  }
}
