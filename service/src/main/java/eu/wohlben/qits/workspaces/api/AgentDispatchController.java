package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.DispatchService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code POST /workspaces/api/agent-dispatches} — <b>put a coding agent to work on this branch</b>,
 * said by a machine.
 *
 * <p><b>What it is for.</b> qits-projects owns tickets, and a ticket that is handed to an agent
 * needs three things this service holds: a branch, a workspace on it carrying the goal, and a
 * container with an agent running in it. Until now a machine could get none of them. Workspace
 * creation is {@code qits:admin} on {@link WorkspaceController}'s class — a person's door, pressed
 * from the branch list — and there has never been a host-side agent launch at all: every agent this
 * platform has ever run was started by a browser posting through {@code ContainerProxyRoute}. So
 * this is one call for the whole arc, and {@link DispatchService} is where the arc is.
 *
 * <p><b>A class of its own, and that is mechanical rather than a matter of taste.</b> {@code
 * WorkspaceController} is {@code @RolesAllowed("qits:admin")} on the class, and a class-level role
 * is inherited by every non-private method of the bean and enforced on ArC's INTERNAL calls too —
 * so adding a machine verb there is the 403 of 2026-09-03 waiting to happen again. The roles here
 * are this door's own, on a class of its own, exactly as {@link BranchResolutionController} and
 * {@link GcController} carry theirs.
 *
 * <p><b>It is idempotent and meant to be polled.</b> A dispatch onto a branch that already has a
 * workspace answers that workspace with {@code fresh:false} rather than 409, and a dispatch onto a
 * workspace whose agent is already running answers {@code SKIPPED_RUNNING} rather than starting a
 * second one. The caller presses it to reach a state, not to perform an action, and pressing it
 * again is how a caller recovers from anything — including this service having been restarted
 * between a dispatch and the container coming up. {@link DispatchService} says why that is the whole
 * of the recovery story.
 *
 * <p>The answer is the record itself rather than a bare {@code Response}: an entity inside an
 * untyped {@code Response} is invisible to native-image indexing, and this module compiles to a
 * binary.
 */
@Path("/agent-dispatches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
// qits:system beside the admin role, like GcController and BranchResolutionController: the caller is
// qits-projects dispatching a ticket, a machine. Every method on this class is meant for both, so
// the class list and the bodies agree by construction and no method widens what the class states.
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system"})
public class AgentDispatchController {

  @Inject DispatchService dispatches;

  /**
   * @param repositoryId the catalog id of the repository to work in — resolved through {@code
   *     RepositoryLookup}, so an unknown one is a 404 and nothing is created
   * @param branch the branch the work happens on, e.g. {@code ticket/fix-login}. A branch that
   *     already carries an ACTIVE workspace is answered with it
   * @param branchTree whether to fork the whole submodule tree (an aggregate wrapper workspace),
   *     the same flag {@code POST /workspaces} carries
   * @param preamble the workspace's goal, in markdown. Durable — it is the workspace's own column
   *     and what a person reads on its page
   * @param instruction the agent's first turn. It rides into the launch and is stored nowhere: the
   *     preamble is the statement of the work, this is the opening of one conversation
   */
  public static record DispatchAgentRequest(
      @NotBlank String repositoryId,
      @NotBlank String branch,
      boolean branchTree,
      String preamble,
      String instruction) {}

  /**
   * Dispatch an agent, or join the dispatch that is already under way.
   *
   * <p>{@code agentLaunch} is {@code SCHEDULED} when a launch is on its way — immediately if the
   * daemon is already answering, and otherwise as soon as it does, which through a cold image pull
   * is minutes. It is {@code SKIPPED_RUNNING} when an agent command was already running, which is
   * the answer a second press gets while the first one's agent is still working.
   *
   * <p>{@code technicalProcessId} is the container start this call began or joined, watchable at
   * {@code /workspaces/api/technical-processes/{id}/events}; null when no start was needed because
   * the daemon was already up.
   */
  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "Dispatched. `fresh:false` means the branch already had a workspace and it was answered"
              + " instead — the ordinary case on a re-press, and not an error.")
  @APIResponse(
      responseCode = "400",
      description = "A blank repository or branch, or a branch name git will not take.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository. Nothing was created.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public DispatchService.Dispatch dispatch(@Valid DispatchAgentRequest request) {
    return dispatches.dispatch(
        request.repositoryId(),
        request.branch(),
        request.branchTree(),
        request.preamble(),
        request.instruction());
  }
}
