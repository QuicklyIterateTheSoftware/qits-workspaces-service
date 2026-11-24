package eu.wohlben.qits.workspaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.workspaces.api.AgentWaitController.CreateWaitRequest;
import eu.wohlben.qits.workspaces.control.AgentWaits;
import eu.wohlben.qits.workspaces.control.WorkItemLookup;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.dto.ProjectAgentWaitView;
import eu.wohlben.qits.workspaces.dto.WaitView.SelectionSchema;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /workspaces/api/project-agent/waits} — a project agent's declared waits (qits-2596, epic
 * qits-2520): the long-running agent qits-agents-service runs for a project waits with {@code qits
 * wait --work <item>} as the workspace agents do, and every wait it holds names the work item it is
 * held for, so that item reads "waiting on <label>". The body is the agent door's ({@link
 * AgentWaitController}) plus {@code work}; the rules are {@link AgentWaits}' — same selection,
 * timeout at most 24 hours and 8 hours when none is given, label at most 200 characters, a timer
 * when there is no selection — with at most 8 OPEN waits per (agent, work item). A close is
 * announced as {@code ProjectAgentWaitClosed}; qits-agents-service turns it into the agent's turn.
 *
 * <p><b>Who.</b> Only a project agent ({@link ProjectAgentCaller}): {@code qits:agent} with {@code
 * context_kind} {@code project-agent} and a {@code project} claim. The holder is that bearer — no
 * path parameter names an agent — so a caller lists and cancels only its own waits, and another
 * holder's wait id is 404. Anyone else, a workspace agent and a desk included, is 403.
 */
@Path("/project-agent/waits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("qits:agent")
public class ProjectAgentWaitController {

  static final String FORBIDDEN =
      "Not a project agent: `qits:agent` with `context_kind` project-agent and a `project` claim.";

  @Inject AgentWaits waits;

  @Inject SecurityIdentity identity;

  /**
   * @param work the work item the wait is held for: its qualified id ({@code qits-123}) or uuid; an
   *     EPIC or a TICKET of the caller's project; required
   */
  public record CreateProjectAgentWaitRequest(
      @Schema(
              description =
                  "What the agent calls the wait; default: the selection in one line, or"
                      + " `timer until <expiresAt>` for a timer")
          String label,
      @Schema(implementation = SelectionSchema.class) JsonNode selection,
      @Schema(
              example = "PT2H",
              description =
                  "ISO-8601; none = the platform's ceiling of 8 hours; at most 24 hours (PT24H)."
                      + " With no selection (left out, or null) it declares a timer, which closes"
                      + " TIMED_OUT when the timeout passes. A timer needs a timeout.")
          String timeout,
      @Schema(
              required = true,
              example = "qits-123",
              description =
                  "The work item the wait is held for, by qualified id or uuid: an EPIC or a"
                      + " TICKET of the caller's project. The item reads `waiting on <label>`"
                      + " while the wait is open.")
          String work) {

    CreateWaitRequest declaration() {
      return new CreateWaitRequest(label, selection, timeout);
    }
  }

  public record ProjectAgentWaitList(List<ProjectAgentWaitView> waits) {}

  @POST
  @APIResponse(
      responseCode = "201",
      description = "The wait, OPEN (or already closed by its lookup); a timer when the body named no selection.",
      content = @Content(schema = @Schema(implementation = ProjectAgentWaitView.class)))
  @APIResponse(
      responseCode = "400",
      description =
          "No `work`; a work item of another project, or one that is not an EPIC or a TICKET;"
              + " neither a selection nor a timeout; an invalid selection; or a bad timeout.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "qits-projects knows no such work item.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "TOO_MANY_WAITS (8 are OPEN for this agent on this work item).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "createProjectAgentWait",
      summary = "Declare what the project agent waits on, for one work item",
      description =
          "A selection waits for an event; no selection and a timeout is a timer. The close is"
              + " announced as ProjectAgentWaitClosed.")
  public Response create(CreateProjectAgentWaitRequest request) {
    ProjectAgentCaller caller = ProjectAgentCaller.require(identity);
    if (request == null || request.work() == null || request.work().isBlank()) {
      throw new BadRequestException("a project agent's wait names its work item: work");
    }
    CreateWaitRequest declaration = request.declaration();
    // The body's own checks first: they cost no call to qits-projects.
    var selection = AgentWaitController.selectionOf(declaration);
    var timeout = AgentWaitController.timeoutOf(declaration);
    WorkItemLookup.WorkItem item = waits.requireProjectAgentWork(caller.projectId(), request.work());
    UUID workId;
    try {
      workId = UUID.fromString(item.id());
    } catch (IllegalArgumentException notAnId) {
      throw new IllegalStateException("qits-projects named work item " + request.work() + " by '" + item.id() + "'.");
    }
    AgentWaitDto created =
        waits.createForProjectAgent(
            caller.subject(),
            caller.projectId(),
            workId,
            item.qualifiedId(),
            declaration.label(),
            selection,
            timeout);
    return Response.status(Response.Status.CREATED).entity(ProjectAgentWaitView.of(created)).build();
  }

  @GET
  @APIResponse(responseCode = "200", description = "The caller's waits, newest first.")
  @APIResponse(
      responseCode = "400",
      description = "A `state` that is neither open nor all.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "qits-projects knows no such work item.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "listProjectAgentWaits",
      summary = "The project agent's waits, on one work item or all, open or all")
  public ProjectAgentWaitList list(
      @QueryParam("work") String work, @QueryParam("state") String state) {
    ProjectAgentCaller caller = ProjectAgentCaller.require(identity);
    AgentWaitState only = stateOf(state);
    UUID workId = null;
    if (work != null && !work.isBlank()) {
      workId = workIdOf(work.trim());
      if (workId == null) {
        WorkItemLookup.WorkItem item = waits.requireProjectAgentWork(caller.projectId(), work);
        workId = UUID.fromString(item.id());
      }
    }
    return new ProjectAgentWaitList(
        waits.listForProjectAgent(caller.subject(), caller.projectId(), workId, only).stream()
            .map(ProjectAgentWaitView::of)
            .toList());
  }

  @DELETE
  @Path("/{waitId}")
  @APIResponse(
      responseCode = "204",
      description = "The wait is CANCELLED (or was closed already); ProjectAgentWaitClosed CANCELLED is announced.")
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such wait of this project agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "cancelProjectAgentWait", summary = "Cancel one of the project agent's waits")
  public Response cancel(@PathParam("waitId") String waitId) {
    ProjectAgentCaller caller = ProjectAgentCaller.require(identity);
    UUID wait;
    try {
      wait = UUID.fromString(waitId);
    } catch (IllegalArgumentException notAnId) {
      throw new NotFoundException("Wait not found: " + waitId);
    }
    waits.cancelForProjectAgent(caller.subject(), caller.projectId(), wait);
    return Response.noContent().build();
  }

  /** {@code open} (the default) is OPEN only; {@code all} every state. */
  static AgentWaitState stateOf(String state) {
    if (state == null || state.isBlank()) {
      return AgentWaitState.OPEN;
    }
    return switch (state.trim().toLowerCase(Locale.ROOT)) {
      case "open" -> AgentWaitState.OPEN;
      case "all" -> null;
      default -> throw new BadRequestException("Unknown wait state '" + state + "'; use open or all.");
    };
  }

  /**
   * The item's uuid when {@code work} is one, so a listing needs no lookup: a wait records the uuid
   * its item had when it was declared. Null for a qualified id, which is looked up.
   */
  private static UUID workIdOf(String work) {
    try {
      return UUID.fromString(work);
    } catch (IllegalArgumentException qualifiedId) {
      return null;
    }
  }
}
