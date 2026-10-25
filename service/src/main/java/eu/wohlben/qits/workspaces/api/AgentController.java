package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /workspaces/api/agents} — agents as a person and the UI see them (qits-1152, D12, D24):
 * the reads, a person's create for an existing work item, and the discard.
 *
 * <p>Roles per method, as everywhere here: the reads take {@code qits:agent} beside the person's
 * roles; the writes are {@code qits:admin} and {@code qits:admin-agent}, the standing rule for a
 * person's door, and {@code qits:desk} beside them (qits-1596), bound to its project by {@link
 * DeskBinding}. The desk never creates an ADMIN agent: that would mint a {@code workspace-admin}
 * credential, the coordinator's own rights, pushes included.
 */
@Path("/agents")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk"})
public class AgentController {

  @Inject AgentService agents;

  @Inject DeskBinding desk;

  public static record ListAgentsRequest() {
    public record Response(List<AgentDto> agents) {}
  }

  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:system",
    "qits:agent"
  })
  @GET
  @Operation(operationId = "listAgents", summary = "Agents, newest first, each filter optional")
  public ListAgentsRequest.Response list(
      @QueryParam("workId") String workId,
      @QueryParam("workspaceId") Long workspaceId,
      @QueryParam("state") AgentState state) {
    return new ListAgentsRequest.Response(agents.list(workId, workspaceId, state));
  }

  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:system",
    "qits:agent"
  })
  @GET
  @Path("/{id}")
  @APIResponse(responseCode = "200", description = "The agent, in any state.")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "getAgent")
  public AgentDto get(@PathParam("id") String id) {
    return agents.get(agentId(id));
  }

  /**
   * A person's agent for an existing work item (D24). {@code wrapperBranch} defaults to {@code
   * <ticket|epic>/<entityId>} — {@code epic/} when {@code kind} is {@code EPIC} — and to the work id
   * where no entity id is given.
   */
  public static record CreateAgentRequest(
      @NotBlank String repositoryId,
      @NotBlank String workId,
      String entityId,
      String kind,
      String wrapperBranch,
      boolean admin,
      AgentHarness harness,
      String instruction) {}

  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "The agent: created, or the item's ACTIVE agent (`fresh:false`). Same answer as the"
              + " dispatch door.")
  @APIResponse(
      responseCode = "400",
      description = "No work item, or a wrapper branch git will not take.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "A `qits:desk` caller asking for an ADMIN agent, or whose token's `project` claim is not"
              + " the repository's project. Nothing was created.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  // The trigger name of this door's call to qits-projects in the consumer pact.
  @Operation(operationId = "createAgent")
  public AgentService.Dispatch create(@Valid CreateAgentRequest request) {
    if (request.admin()) {
      desk.refuse(
          "The delivery desk may not create an admin agent: it would hold a workspace-admin"
              + " credential, pushes included.");
    }
    desk.requireRepository(request.repositoryId());
    String prefix = "EPIC".equalsIgnoreCase(request.kind()) ? "epic/" : "ticket/";
    String branch =
        request.wrapperBranch() != null && !request.wrapperBranch().isBlank()
            ? request.wrapperBranch()
            : prefix
                + (request.entityId() != null && !request.entityId().isBlank()
                    ? request.entityId()
                    : request.workId());
    return agents.dispatch(
        new AgentService.AgentRequest(
            request.repositoryId(),
            branch,
            request.workId(),
            request.entityId(),
            "EPIC".equalsIgnoreCase(request.kind()) ? null : request.workId(),
            "EPIC".equalsIgnoreCase(request.kind()) ? request.workId() : null,
            request.admin(),
            request.harness(),
            request.instruction(),
            null));
  }

  /** @param force remove despite leftover work, or with a daemon that does not answer */
  public static record DiscardAgentRequest(boolean force) {}

  @POST
  @Path("/{id}/discard")
  @APIResponse(responseCode = "200", description = "Removed: worktrees, credential and draft gone.")
  @APIResponse(
      responseCode = "409",
      description =
          "AGENT_BUSY (harness still working in the background), AGENT_HAS_LEFTOVER_WORK"
              + " (uncommitted or unpushed work, named) or AGENT_DAEMON_UNREACHABLE; nothing was"
              + " removed. Send `force: true` to remove anyway.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "A `qits:desk` caller whose token's `project` claim is not the agent's project. Nothing"
              + " was removed.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "discardAgent")
  public AgentDto discard(@PathParam("id") String id, DiscardAgentRequest request) {
    desk.requireAgent(id);
    return agents.discard(agentId(id), request != null && request.force());
  }

  static UUID agentId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAnId) {
      throw new NotFoundException("Agent not found: " + id);
    }
  }

}
