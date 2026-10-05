package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.error.IntegrateConflictException;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The error body every route here answers with, <b>as the document declares it</b>.
 *
 * <p>Nothing returns this type. {@link WorkspacesExceptionMapper} builds the map that goes on the
 * wire, and it must keep doing so: the envelope is additive and the two extra fields are present
 * only for the conflicts that have them, which a fixed record would turn into explicit nulls. What
 * this class is for is the <em>schema</em> — the reason enum in particular. Before it, {@code
 * docs/openapi.yml} declared no 4xx at all, so the discriminator a client is meant to branch on
 * appeared in no generated model anywhere and had to be copied out of prose.
 *
 * <p>Kept in step by construction rather than by discipline: {@code reason} is typed as the domain
 * enum itself, so a new refusal mode reaches the document by being added to {@link
 * IntegrateConflictException.Reason} and nowhere else.
 */
@Schema(
    name = "ApiError",
    description = "The error envelope: a message, plus a reason or a code on a 409.")
public record ApiError(
    @Schema(description = "What went wrong, in words. Always present.") String message,
    @Schema(
            description =
                "Which refusal this is. Present on the 409s the release and integrate flows raise,"
                    + " absent on every other error. A value a client does not recognise is shown"
                    + " as the message verbatim.")
        IntegrateConflictException.Reason reason,
    @Schema(description = "The conflicted paths. Present only for CONFLICT and MERGE_CONFLICT.")
        List<String> conflicts,
    @Schema(
            description =
                "The refusal's own name, where it has one (e.g. RUNNER_OWNS_WORKSPACES). Absent"
                    + " otherwise.")
        String code,
    @Schema(
            description =
                "The ACTIVE workspace row ids a runner still owns. Present only with"
                    + " RUNNER_OWNS_WORKSPACES.")
        List<Long> workspaceIds,
    @Schema(
            description =
                "The ACTIVE workspaces a runner still owns, each with its repository and branch so"
                    + " a client can link it. Present only with RUNNER_OWNS_WORKSPACES.")
        List<OwnedWorkspace> workspaces) {

  /** One workspace a runner still owns, as the 409 names it. */
  @Schema(name = "RunnerOwnedWorkspace")
  public record OwnedWorkspace(
      @Schema(description = "The workspace row id") Long id,
      @Schema(description = "Its repository") String repositoryId,
      @Schema(description = "Its branch, or null") String branch) {}
}
