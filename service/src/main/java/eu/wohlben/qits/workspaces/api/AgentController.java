package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.AgentService;
import eu.wohlben.qits.workspaces.control.WorkspaceHistoryService;
import eu.wohlben.qits.workspaces.control.WorkspaceProcessTracker;
import eu.wohlben.qits.workspaces.daemonhost.DaemonAgentBindingCheck;
import eu.wohlben.qits.workspaces.dto.AgentDto;
import eu.wohlben.qits.workspaces.entity.AgentHarness;
import eu.wohlben.qits.workspaces.entity.AgentState;
import eu.wohlben.qits.workspaces.error.NotFoundException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
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
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code /workspaces/api/agents} — agents as a person and the UI see them (qits-1152, D12, D24):
 * the reads, a person's create for an existing work item, and the discard; and since epic qits-1667
 * an agent's controls (prompt, interrupt, stop, restart, yield) and its sessions, transcripts and
 * active process.
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
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:desk"})
public class AgentController {

  @Inject AgentService agents;

  @Inject DeskBinding desk;

  @Inject SecurityIdentity identity;

  @Inject WorkspaceHistoryService history;

  @Inject Instance<WorkspaceProcessTracker> technicalProcesses;

  public static record ListAgentsRequest() {
    public record Response(List<AgentDto> agents) {}
  }

  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:agent"
  })
  @GET
  @Operation(
      operationId = "listAgents",
      summary = "Agents, newest first, each filter optional",
      description =
          "Each agent carries its OPEN waits (`waits`, oldest first, with what each waits on) and"
              + " what it needs a person for (`attention`, null when nothing).")
  public ListAgentsRequest.Response list(
      @QueryParam("workId") String workId,
      @QueryParam("workspaceId") Long workspaceId,
      @QueryParam("state") AgentState state,
      @Parameter(
              description =
                  "A project's uuid: only the agents whose repository is that project's. A"
                      + " repository whose project cannot be named is in none.")
          @QueryParam("projectId")
          String projectId) {
    return new ListAgentsRequest.Response(agents.list(workId, workspaceId, state, projectId));
  }

  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
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
    String discardedBy = identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
    return agents.discard(agentId(id), request != null && request.force(), discardedBy);
  }

  // --- a person's controls, and what an agent may read of another (epic qits-1667) ---------------
  //
  // Who may call what (the owner's ruling on qits-1575): the desk reads everything; an agent reads
  // any agent's sessions and transcripts, read-only; it may yield its own slot and nothing else.
  // "Own" is its token's subject being the agent's commissionedTokenSubject, checked in the body:
  // @RolesAllowed alone cannot say it.

  /** @param compactFirst a {@code /compact} ahead of the turn; honoured only when configured */
  public static record PromptAgentRequest(@NotBlank String text, boolean compactFirst) {}

  @POST
  @Path("/{id}/prompt")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "Answered, as the work-item delivery door answers: `delivered` (queued for the running"
              + " agent), `launched` (its first turn, once it starts) or `resumed` (a yielded or"
              + " stopped agent runs again to hear it).")
  @APIResponse(
      responseCode = "400",
      description = "A blank text.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "AGENT_REMOVED: the agent was removed; nothing was said.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = DeskBinding.AGENT_403,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "promptAgent",
      summary = "Say a turn to this agent",
      description =
          "The agent hears it once it is not busy, or after the launch window regardless. A STOPPED"
              + " agent starts again with this turn.")
  public AgentService.Delivery prompt(@PathParam("id") String id, @Valid PromptAgentRequest request) {
    UUID agentId = agentId(id);
    desk.requireAgent(id);
    return agents.deliver(agentId, request.text(), request.compactFirst(), principal());
  }

  @POST
  @Path("/{id}/interrupt")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk"
  })
  @APIResponse(responseCode = "200", description = "ESC was written into the agent's harness.")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "AGENT_NOT_RUNNING (no running session) or AGENT_NOT_INTERRUPTIBLE (the daemon's own"
              + " refusal: no harness, not a terminal, not started yet — or a daemon too old to"
              + " interrupt). Nothing was sent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "503",
      description = "AGENT_DAEMON_UNREACHABLE: the agent's workspace daemon does not answer.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = DeskBinding.AGENT_403,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "interruptAgent",
      summary = "Interrupt the agent's current turn",
      description =
          "ESC into its running TERMINAL harness, as a person at the terminal would press it. Never"
              + " changes its run state.")
  public AgentService.Interrupt interrupt(@PathParam("id") String id) {
    UUID agentId = agentId(id);
    desk.requireAgent(id);
    return agents.interrupt(agentId, principal());
  }

  @POST
  @Path("/{id}/stop")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "STOPPED (already STOPPED: unchanged). A placed agent's harness is stopped on its daemon"
              + " and its worktree and workspace stay; a QUEUED one gives up its place. A daemon"
              + " that does not answer still leaves it STOPPED.")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "AGENT_REMOVED: the agent was removed.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = DeskBinding.AGENT_403,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "stopAgent",
      summary = "Stop the agent",
      description =
          "The scheduler never places, resumes, nudges or drives a STOPPED agent. A wait that fires"
              + " for it appends its turn, heard once it is restarted or prompted.")
  public AgentDto stop(@PathParam("id") String id) {
    UUID agentId = agentId(id);
    desk.requireAgent(id);
    return agents.stop(agentId, principal());
  }

  @POST
  @Path("/{id}/restart")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "A RUNNING agent's harness is stopped and started again on its session; a STOPPED or"
              + " YIELDED one is handed back to the scheduler as YIELDED (QUEUED without a"
              + " workspace).")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "AGENT_REMOVED, or AGENT_NOT_STARTED: a QUEUED agent has nothing to restart.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "503",
      description = "AGENT_DAEMON_UNREACHABLE: a RUNNING agent's workspace daemon does not answer.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description = DeskBinding.AGENT_403,
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "restartAgent", summary = "Restart the agent's harness")
  public AgentDto restart(@PathParam("id") String id) {
    UUID agentId = agentId(id);
    desk.requireAgent(id);
    return agents.restart(agentId, principal());
  }

  @POST
  @Path("/{id}/yield")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk",
    "qits:agent"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "YIELDED: the slot is free at once and the harness keeps running. Anything the agent"
              + " should hear brings it back.")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description = "AGENT_NOT_RUNNING: only a running agent yields.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "403",
      description =
          "A `qits:agent` caller that is not this agent, or a desk caller of another project.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(
      operationId = "yieldAgent",
      summary = "Give up the agent's workspace slot",
      description = "An agent may yield its own slot.")
  public AgentDto yieldSlot(@PathParam("id") String id) {
    UUID agentId = agentId(id);
    if (agentOnly()) {
      agents.requireOwnAgent(agentId, DaemonAgentBindingCheck.subjectOf(identity));
    } else {
      desk.requireAgent(id);
    }
    return agents.yieldSlot(agentId, principal());
  }

  @GET
  @Path("/{id}/sessions")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk",
    "qits:agent"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "The agent's sessions, oldest first, read off the shared harness volume: they answer for a"
              + " REMOVED agent too. An empty list is a valid answer.")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "listAgentSessions", summary = "The agent's harness sessions")
  public WorkspaceHistoryController.ListAgentSessionsRequest.Response sessions(@PathParam("id") String id) {
    return new WorkspaceHistoryController.ListAgentSessionsRequest.Response(history.agentSessionsOf(agentId(id)));
  }

  @GET
  @Path("/{id}/sessions/{sessionId}/transcript")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk",
    "qits:agent"
  })
  @APIResponse(responseCode = "200", description = "The session's raw JSONL lines.")
  @APIResponse(
      responseCode = "404",
      description =
          "No such agent, or a session that is not this agent's: answered exactly as one that never"
              + " existed.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "getAgentTranscript", summary = "One of the agent's sessions, raw")
  public WorkspaceHistoryController.GetAgentTranscriptRequest.Response transcript(
      @PathParam("id") String id, @PathParam("sessionId") String sessionId) {
    return new WorkspaceHistoryController.GetAgentTranscriptRequest.Response(
        history.agentTranscriptOf(agentId(id), sessionId));
  }

  @GET
  @Path("/{id}/active-process")
  @jakarta.annotation.security.RolesAllowed({
    "qits:admin",
    "qits:admin-agent",
    "qits:desk-admin",
    "qits:system",
    "qits:desk",
    "qits:agent"
  })
  @APIResponse(
      responseCode = "200",
      description =
          "The technical process running against the agent's workspace, or null: none, or the"
              + " agent has no workspace (QUEUED).")
  @APIResponse(
      responseCode = "404",
      description = "No such agent.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "getAgentActiveProcess")
  public WorkspaceController.ActiveProcessRequest.Response activeProcess(@PathParam("id") String id) {
    Long workspaceId = agents.get(agentId(id)).workspaceRowId();
    return new WorkspaceController.ActiveProcessRequest.Response(
        workspaceId != null && technicalProcesses.isResolvable()
            ? technicalProcesses.get().activeFor(workspaceId).orElse(null)
            : null);
  }

  /** The caller's own principal, for the audit line. */
  private String principal() {
    return identity.getPrincipal() == null ? null : identity.getPrincipal().getName();
  }

  /** A caller holding {@code qits:agent} and none of the roles that act on any agent. */
  private boolean agentOnly() {
    return identity.hasRole("qits:agent")
        && List.of("qits:admin", "qits:admin-agent", "qits:system", DeskBinding.DESK_ADMIN, DeskBinding.DESK)
            .stream()
            .noneMatch(identity::hasRole);
  }

  static UUID agentId(String id) {
    try {
      return UUID.fromString(id);
    } catch (IllegalArgumentException | NullPointerException notAnId) {
      throw new NotFoundException("Agent not found: " + id);
    }
  }

}
