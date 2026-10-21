package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.auth.MachineIdentity;
import eu.wohlben.qits.auth.QitsClaims;
import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.error.BadRequestException;
import eu.wohlben.qits.workspaces.error.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The machine doors qits-projects presses to put an agent on a work item and to talk to it
 * (qits-1152). Every door but the dispatch names the work item, never a branch: an agent is found
 * by its {@code workId}. {@link AgentService} holds the semantics.
 *
 * <p><b>A class of its own at {@code {qits:admin, qits:system}}</b> (and {@code qits:admin-agent}
 * wherever {@code qits:admin} is): a class-level {@code @RolesAllowed} binds ArC's internal calls
 * too, so a machine verb on a person's class is a 403 — the 2026-09-03 lesson. The references read
 * also admits {@code qits:agent}: every read does.
 *
 * <p><b>{@code deliver} also admits {@code qits:desk}</b> (qits-1493), and binds it: a desk token
 * may prompt only the agents of its own project, the project its token's {@code project} claim
 * names. {@code qits:admin}, {@code qits:admin-agent} and {@code qits:system} stay unbound.
 */
@Path("/agent-dispatches")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:system"})
public class AgentDispatchController {

  /**
   * The roles bound to their own project on {@link #deliver}: {@code qits:desk} alone, never {@code
   * qits:admin}/{@code qits:admin-agent}/{@code qits:system}, which this door admits unbound
   * (qits-1493).
   */
  private static final List<String> UNBOUND_DELIVERY_ROLES =
      List.of("qits:admin", "qits:admin-agent", "qits:system");

  @Inject AgentService agents;

  @Inject SecurityIdentity identity;

  /**
   * @param repositoryId the project's wrapper repository
   * @param wrapperBranch {@code <prefix>/<workId>}: the agent's wrapper branch; it names its
   *     submodule branches {@code <wrapperBranch>-<its choice>}
   * @param workId the work item (D24)
   * @param entityId the item's qualified id, which names the agent's session
   * @param admin refused when true: an admin agent holds the host's docker socket, so it comes
   *     only from a person's door ({@code POST /agents}, {@code qits:admin}/{@code
   *     qits:admin-agent}), never from this machine door that {@code qits:system} may press
   * @param instruction the agent's first turn
   */
  public static record DispatchAgentRequest(
      @NotBlank String repositoryId,
      @NotBlank String wrapperBranch,
      @NotBlank String workId,
      String entityId,
      String entityTitle,
      String entityStatus,
      Boolean entityBlocked,
      String ticketId,
      String epicId,
      Boolean admin,
      AgentHarness harness,
      String instruction) {}

  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "Dispatched. `fresh:false` means the work item already had an ACTIVE agent and it was"
              + " answered — a re-press, not an error. `agentLaunch` is SCHEDULED (the agent has a"
              + " workspace and starts, or hears the instruction, once its daemon answers),"
              + " SKIPPED_RUNNING (it was already running; the instruction was not sent) or QUEUED"
              + " (no workspace slot is free; `agent.workspaceRowId` is null until one is)."
              + " `agentIdentity` is the subject of the agent's own credential; null while it holds"
              + " none.")
  @APIResponse(
      responseCode = "400",
      description =
          "A blank repository, wrapper branch or work item, a branch git will not take, or"
              + " `admin: true` (admin agents come only from `POST /workspaces/api/agents`).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository. Nothing was created.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  // The operationId is the name a consumer pact uses for this door as the TRIGGER of the calls it
  // makes downstream (pacts/qits-workspaces-service_qits-projects-service.json).
  @Operation(operationId = "dispatchAgent")
  public AgentService.Dispatch dispatch(@Valid DispatchAgentRequest request) {
    if (Boolean.TRUE.equals(request.admin())) {
      throw new BadRequestException(
          "This door never creates an admin agent: an admin agent holds the host's docker socket"
              + " and comes only from a person's door, POST /workspaces/api/agents.");
    }
    return agents.dispatch(
        new AgentService.AgentRequest(
            request.repositoryId(),
            request.wrapperBranch(),
            request.workId(),
            request.entityId(),
            request.ticketId(),
            request.epicId(),
            false,
            request.harness(),
            request.instruction(),
            factsOf(request.entityTitle(), request.entityStatus(), request.entityBlocked())));
  }

  static EntityFacts factsOf(String title, String status, Boolean blocked) {
    if (title == null && status == null && blocked == null) {
      return null;
    }
    return new EntityFacts(title, status, Boolean.TRUE.equals(blocked));
  }

  /** @param compactFirst a request for a {@code /compact} ahead of the turn; honoured only when configured */
  public static record DeliverTurnRequest(
      @NotBlank String workId, @NotBlank String text, boolean compactFirst) {}

  @POST
  @Path("/delivery")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:system",
    "qits:desk"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `agentId: null` means no agent works on the item: nothing was said and"
              + " nothing created — a normal outcome. Otherwise one of `delivered` (queued for the"
              + " running agent: said once it is not busy, or after the launch window),"
              + " `launched` (its first turn, once it starts) or `resumed` (a yielded agent"
              + " resumes to hear it).")
  @APIResponse(
      responseCode = "400",
      description = "A blank work item or text.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "A `qits:desk` caller whose token's `project` claim is not the item's ACTIVE agent's"
              + " project. Nothing was said.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "deliverAgentTurn")
  public AgentService.Delivery deliver(@Valid DeliverTurnRequest request) {
    if (UNBOUND_DELIVERY_ROLES.stream().noneMatch(identity::hasRole)) {
      requireOwnProject(request.workId());
    }
    return agents.deliver(request.workId(), request.text(), request.compactFirst());
  }

  /**
   * qits:desk's binding (qits-1493): a desk token may prompt only the agents of its own project.
   * An item with no ACTIVE agent is not a binding violation — {@link #deliver} answers it with
   * {@code agentId: null}, the same as every other role's call, and a desk never learns which
   * project (if any) would have covered it.
   *
   * @throws ForbiddenException the item's ACTIVE agent belongs to another project
   */
  private void requireOwnProject(String workId) {
    Optional<String> agentProject = agents.projectOfActiveAgent(workId);
    if (agentProject.isEmpty()) {
      return;
    }
    if (!MachineIdentity.claimMatches(identity, QitsClaims.PROJECT, agentProject.get())) {
      throw new ForbiddenException(
          "The delivery desk may prompt only the agents of its own project.");
    }
  }

  public static record MarkBlockedRequest(
      @NotBlank String workId, boolean blocked, String blockSource) {}

  @POST
  @Path("/blocked")
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `agentId: null` means no agent works on the item; `applied` says whether the"
              + " running harness took the marker live. Never creates or starts anything.")
  @APIResponse(
      responseCode = "400",
      description = "A blank work item.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "markAgentDispatchBlocked")
  public AgentService.EntityMark blocked(@Valid MarkBlockedRequest request) {
    return agents.markBlocked(request.workId(), request.blocked(), request.blockSource());
  }

  public static record MarkEntityRequest(
      @NotBlank String workId,
      String title,
      String status,
      @NotNull Boolean blocked,
      String blockSource) {}

  @POST
  @Path("/entity")
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `agentId: null` means no agent works on the item and nothing was stored;"
              + " otherwise the facts are stored on the agent (the cleanup sweep reads the status),"
              + " and `applied` says whether its running harness took them live.")
  @APIResponse(
      responseCode = "400",
      description = "A blank work item, or a missing or non-boolean `blocked`.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "markAgentDispatchEntity")
  public AgentService.EntityMark entity(@Valid MarkEntityRequest request) {
    return agents.markEntity(
        request.workId(),
        new EntityFacts(request.title(), request.status(), request.blocked().booleanValue()),
        request.blockSource());
  }

  public static record ListAgentRefsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(AgentDto agent) {}
    }
  }

  /**
   * The agents of these work items, in every state, newest first — one call per caller's listing.
   * {@code ticketId} and {@code epicId} are read as work ids too, for a caller that still sends
   * them.
   */
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:system",
    "qits:agent"
  })
  @GET
  @Path("/references")
  @Operation(operationId = "listAgentDispatchReferences")
  public ListAgentRefsRequest.Response references(
      @QueryParam("workId") List<String> workIds,
      @QueryParam("ticketId") List<String> ticketIds,
      @QueryParam("epicId") List<String> epicIds) {
    List<String> all = new ArrayList<>();
    for (List<String> ids : List.of(nullSafe(workIds), nullSafe(ticketIds), nullSafe(epicIds))) {
      ids.stream().filter(id -> id != null && !id.isBlank()).forEach(all::add);
    }
    return new ListAgentRefsRequest.Response(
        agents.referencing(all).stream().map(ListAgentRefsRequest.Response.Entry::new).toList());
  }

  private static List<String> nullSafe(List<String> ids) {
    return ids == null ? List.of() : ids;
  }
}
