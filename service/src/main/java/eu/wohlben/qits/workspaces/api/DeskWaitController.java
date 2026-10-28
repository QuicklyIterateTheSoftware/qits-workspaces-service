package eu.wohlben.qits.workspaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.workspaces.api.AgentWaitController.CreateWaitRequest;
import eu.wohlben.qits.workspaces.api.AgentWaitController.WaitList;
import eu.wohlben.qits.workspaces.dto.WaitView;
import eu.wohlben.qits.workspaces.control.AgentWaits;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
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
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /workspaces/api/desks/{projectId}/waits} — a project front desk's declared waits
 * (qits-1618): the desk is a Claude session qits-projects runs, with no agent row here, and it
 * waits with {@code qits wait} like the agents do. The bodies are the agent door's ({@link
 * AgentWaitController}), {@code agentId} null; the rules are {@link AgentWaits}' — same selection,
 * timeout at most 30 days, at most 8 OPEN waits per project. A match or timeout is announced as
 * {@code DeskWaitClosed} rather than written as a turn.
 *
 * <p><b>Who.</b> {@code qits:admin} and {@code qits:system} act on any project's desk. {@code
 * qits:desk-admin} (the desk's own role, qits-1622) acts only on the desk of the project its
 * token's {@code project} claim names ({@link MachineIdentity#claimMatches}, {@link
 * QitsClaims#PROJECT}, as {@link DeskBinding} reads it); any other project is 403. Nothing else is
 * admitted: not a workspace agent's {@code qits:agent} or {@code qits:admin-agent}, and not the
 * delivery desk's plain {@code qits:desk}. {@code DeskWaitAccessTest} pins it.
 *
 * <p>A wait id of another project's desk, or of an agent, is not found here: 404, as the agent door
 * answers another agent's wait id.
 */
@Path("/desks/{projectId}/waits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:system", "qits:desk-admin"})
public class DeskWaitController {

  /** The front desk's role; bound to its token's project. */
  static final String DESK_ADMIN = "qits:desk-admin";

  /** The roles that act on any project's desk. */
  static final List<String> UNBOUND_ROLES = List.of("qits:admin", "qits:system");

  static final String OWN_DESK_ONLY =
      "A desk may declare, list and cancel only its own project's waits; this credential's project"
          + " is not ";

  private static final String FORBIDDEN =
      "A `qits:desk-admin` caller whose token's `project` claim is not this project.";

  @Inject AgentWaits waits;

  @Inject SecurityIdentity identity;

  @Inject ObjectMapper json;

  @POST
  @APIResponse(
      responseCode = "201",
      description = "The wait, OPEN.",
      content = @Content(schema = @Schema(implementation = WaitView.class)))
  @APIResponse(
      responseCode = "400",
      description = "An invalid selection (the matcher's reason), a bad timeout, or a project id that is not a UUID.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "TOO_MANY_WAITS (8 are OPEN for this project's desk).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "createDeskWait", summary = "Declare what the project's desk waits on")
  public Response create(@PathParam("projectId") String projectId, CreateWaitRequest request) {
    UUID project = authorized(projectId);
    AgentWaitDto created =
        waits.createForDesk(
            project,
            request == null ? null : request.label(),
            AgentWaitController.selectionOf(request),
            AgentWaitController.timeoutOf(request));
    return Response.status(Response.Status.CREATED)
        .entity(AgentWaitController.view(json, created))
        .build();
  }

  @GET
  @APIResponse(responseCode = "200", description = "The desk's waits, newest first.")
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "listDeskWaits", summary = "The project desk's waits, optionally of one state")
  public WaitList list(@PathParam("projectId") String projectId, @QueryParam("state") String state) {
    UUID project = authorized(projectId);
    return new WaitList(
        waits.listForDesk(project, AgentWaitController.stateOf(state)).stream()
            .map(w -> AgentWaitController.view(json, w))
            .toList());
  }

  @DELETE
  @Path("/{waitId}")
  @APIResponse(
      responseCode = "200",
      description = "The wait: CANCELLED, or as it was when already closed. Nothing is announced.")
  @APIResponse(
      responseCode = "403",
      description = FORBIDDEN,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such wait of this project's desk.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "cancelDeskWait", summary = "Cancel one of the project desk's waits")
  public WaitView cancel(@PathParam("projectId") String projectId, @PathParam("waitId") String waitId) {
    UUID project = authorized(projectId);
    UUID wait;
    try {
      wait = UUID.fromString(waitId);
    } catch (IllegalArgumentException notAnId) {
      throw new NotFoundException("Wait not found: " + waitId);
    }
    return AgentWaitController.view(json, waits.cancelForDesk(project, wait));
  }

  /** The project id, once the caller may act on its desk. */
  private UUID authorized(String projectId) {
    if (UNBOUND_ROLES.stream().noneMatch(identity::hasRole)
        && (projectId == null
            || projectId.isBlank()
            || !MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, projectId))) {
      throw new ForbiddenException(OWN_DESK_ONLY + projectId + ".");
    }
    try {
      return UUID.fromString(projectId);
    } catch (IllegalArgumentException notAnId) {
      throw new BadRequestException("'" + projectId + "' is not a project id.");
    }
  }
}
