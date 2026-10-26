package eu.wohlben.qits.workspaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.selection.EventSelection;
import eu.wohlben.qits.eventstream.selection.EventSelectionException;
import eu.wohlben.qits.workspaces.control.AgentWaits;
import eu.wohlben.qits.workspaces.dto.AgentWaitDto;
import eu.wohlben.qits.workspaces.entity.AgentWaitState;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /workspaces/api/agents/{agentId}/waits} — an agent's declared waits (qits-1153, W8): what
 * it waits on, and the turn it gets when that happens or the wait times out. {@link AgentWaits}
 * holds the semantics; {@code qits wait} is the caller.
 *
 * <p><b>A deliberate exception to "agents get no write doors here".</b> {@code qits:agent} (and
 * {@code qits:admin-agent}) may create, list and cancel waits, but <b>only for the agent its token
 * belongs to</b> (W4): the caller's principal must be the subject of that agent's own credential
 * ({@code AgentWorktree.commissionedTokenSubject}, context id {@code agent-<id>}). {@code
 * qits:admin} and {@code qits:system} act on any agent. So here, unlike everywhere else,
 * {@code qits:admin-agent} is not admitted wherever {@code qits:admin} is: it is an agent, and bound
 * like one. {@code AgentWaitAccessTest} pins it.
 *
 * <p><b>{@code qits:desk-admin} (qits-1621) is bound to its project, not to an agent.</b> The
 * delivery desk is no agent: its token names a project and no agent row carries its subject, so
 * the own-agent binding would refuse it every wait. On these doors it acts instead on the waits of
 * any agent of its own project, by the same {@link DeskBinding#requireAgent} the desk's agent
 * writes use, and an agent of another project (or of none it can resolve) is 403. A wait writes no
 * ref and widens no project write: the desk could already prompt those agents. A desk-admin that is
 * not a bound desk (no {@code qits:desk}) gets no such arm and is bound to its own agent like any
 * other. The desk's <em>own</em> waits are {@link DeskWaitController}'s (qits-1618). {@code
 * DeskAdminAccessTest} pins it.
 *
 * <p>The selection is bound from JSON by qits-eventstream's own reader, so its three types are
 * registered for reflection here for the native image.
 */
@Path("/agents/{agentId}/waits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:system", "qits:agent"})
@RegisterForReflection(
    targets = {EventSelection.class, EventSelection.Condition.class, EventSelection.Op.class})
public class AgentWaitController {

  /** The roles that act on any agent's waits; every other admitted role is bound to its own agent. */
  static final List<String> UNBOUND_ROLES = List.of("qits:admin", "qits:system");

  /** Bound to its desk's project rather than to an agent (qits-1621); never one of the above. */
  static final String DESK_ADMIN = "qits:desk-admin";

  @Inject AgentWaits waits;

  @Inject SecurityIdentity identity;

  @Inject DeskBinding desk;

  @Inject ObjectMapper json;

  /** The selection's JSON form, for the openapi document only. */
  @Schema(
      name = "EventSelection",
      description =
          "qits-eventstream's EventSelection: the event name, matched exactly, and `when` groups"
              + " (ORed) of conditions (ANDed). Empty `when` = every event of that name.")
  public record SelectionSchema(
      @Schema(required = true, example = "ReleaseRequestStateChanged") String event,
      List<List<ConditionSchema>> when) {}

  /** One condition, for the openapi document only. */
  @Schema(name = "EventSelectionCondition")
  public record ConditionSchema(
      @Schema(required = true, description = "Dot path; `name[]` = some element of the array")
          String path,
      @Schema(required = true, enumeration = {"exact", "prefix", "exists"}) String op,
      @Schema(
              description = "A string for exact and prefix; true or false for exists (false = absent)",
              type = SchemaType.STRING)
          Object value) {}

  /**
   * @param label what the agent calls the wait; default: the selection in one line
   * @param selection the event and conditions to wait for
   * @param timeout an ISO-8601 duration ({@code PT2H}); none = never; at most 30 days
   */
  public record CreateWaitRequest(
      String label,
      @Schema(implementation = SelectionSchema.class, required = true) JsonNode selection,
      @Schema(example = "PT2H") String timeout) {}

  /** A wait as the doors answer it. */
  public record WaitView(
      String id,
      String agentId,
      String label,
      @Schema(implementation = SelectionSchema.class) JsonNode selection,
      AgentWaitState state,
      Instant createdAt,
      Instant expiresAt,
      String matchedEventId,
      Instant closedAt) {}

  public record WaitList(List<WaitView> waits) {}

  @POST
  @APIResponse(
      responseCode = "201",
      description = "The wait, OPEN.",
      content = @Content(schema = @Schema(implementation = WaitView.class)))
  @APIResponse(
      responseCode = "400",
      description = "An invalid selection (the matcher's reason), or a bad timeout.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = "An agent's credential acting on another agent's waits.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "AGENT_NOT_ACTIVE (the agent is OBSOLETE or REMOVED) or TOO_MANY_WAITS (8 are OPEN).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "createAgentWait", summary = "Declare what the agent waits on")
  public Response create(@PathParam("agentId") String agentId, CreateWaitRequest request) {
    UUID id = authorized(agentId);
    AgentWaitDto created =
        waits.create(id, request == null ? null : request.label(), selectionOf(request), timeoutOf(request));
    return Response.status(Response.Status.CREATED).entity(view(created)).build();
  }

  /** The request's selection, bound by qits-eventstream's reader; a 400 with its reason otherwise. */
  static EventSelection selectionOf(CreateWaitRequest request) {
    if (request == null || request.selection() == null || request.selection().isNull()) {
      throw new BadRequestException("A wait needs a selection: {\"event\": …, \"when\": […]}.");
    }
    try {
      return EventSelection.fromJson(request.selection().toString());
    } catch (EventSelectionException invalid) {
      throw new BadRequestException("Invalid selection: " + invalid.getMessage());
    }
  }

  /** The request's timeout; null = none. A 400 for one that is not an ISO-8601 duration. */
  static Duration timeoutOf(CreateWaitRequest request) {
    if (request == null || request.timeout() == null || request.timeout().isBlank()) {
      return null;
    }
    try {
      return Duration.parse(request.timeout().trim());
    } catch (DateTimeParseException notIso) {
      throw new BadRequestException(
          "'" + request.timeout() + "' is not an ISO-8601 duration such as PT2H or P1D.");
    }
  }

  @GET
  @APIResponse(responseCode = "200", description = "The agent's waits, newest first.")
  @APIResponse(
      responseCode = "403",
      description = "An agent's credential reading another agent's waits.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "listAgentWaits", summary = "The agent's waits, optionally of one state")
  public WaitList list(@PathParam("agentId") String agentId, @QueryParam("state") String state) {
    UUID id = authorized(agentId);
    return new WaitList(waits.list(id, stateOf(state)).stream().map(this::view).toList());
  }

  @DELETE
  @Path("/{waitId}")
  @APIResponse(
      responseCode = "200",
      description = "The wait: CANCELLED, or as it was when already closed. No turn is given.")
  @APIResponse(
      responseCode = "403",
      description = "An agent's credential cancelling another agent's wait.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such agent, or no such wait of it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "cancelAgentWait", summary = "Cancel one of the agent's waits")
  public WaitView cancel(@PathParam("agentId") String agentId, @PathParam("waitId") String waitId) {
    UUID id = authorized(agentId);
    UUID wait;
    try {
      wait = UUID.fromString(waitId);
    } catch (IllegalArgumentException notAnId) {
      throw new NotFoundException("Wait not found: " + waitId);
    }
    return view(waits.cancel(id, wait));
  }

  /** The agent id, once the caller may act on it (W4). */
  private UUID authorized(String agentId) {
    UUID id = AgentController.agentId(agentId);
    if (UNBOUND_ROLES.stream().anyMatch(identity::hasRole)) {
      return id;
    }
    if (identity.hasRole(DESK_ADMIN) && desk.isBoundDesk()) {
      desk.requireAgent(id.toString());
    } else {
      waits.requireOwnAgent(id, identity.getPrincipal() == null ? null : identity.getPrincipal().getName());
    }
    return id;
  }

  static AgentWaitState stateOf(String state) {
    if (state == null || state.isBlank()) {
      return null;
    }
    try {
      return AgentWaitState.valueOf(state.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new BadRequestException(
          "Unknown wait state '" + state + "'; use OPEN, MATCHED, TIMED_OUT or CANCELLED.");
    }
  }

  private WaitView view(AgentWaitDto wait) {
    return view(json, wait);
  }

  /** A wait as both doors answer it; {@code agentId} is null for a desk's. */
  static WaitView view(ObjectMapper json, AgentWaitDto wait) {
    JsonNode selection;
    try {
      selection = json.readTree(wait.selection());
    } catch (Exception unreadable) {
      selection = json.getNodeFactory().textNode(wait.selection());
    }
    return new WaitView(
        wait.id(),
        wait.agentId(),
        wait.label(),
        selection,
        wait.state(),
        wait.createdAt(),
        wait.expiresAt(),
        wait.matchedEventId(),
        wait.closedAt());
  }
}
