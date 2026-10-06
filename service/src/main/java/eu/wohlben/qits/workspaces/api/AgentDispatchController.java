package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.DispatchService;
import eu.wohlben.qits.workspaces.control.EntityFacts;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.control.WorkspaceSubject;
import eu.wohlben.qits.workspaces.dto.WorkspaceSubjectRefDto;
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
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
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

  @Inject WorkspaceService workspaces;

  /**
   * @param repositoryId the catalog id of the repository to work in — resolved through {@code
   *     RepositoryLookup}, so an unknown one is a 404 and nothing is created
   * @param branch the branch the work happens on, e.g. {@code ticket/fix-login}. A branch that
   *     already carries an ACTIVE workspace is answered with it
   * @param branchTree whether to fork the whole submodule tree (an aggregate wrapper workspace),
   *     the same flag {@code POST /workspaces} carries
   * @param preamble the workspace's goal, in markdown. Durable — it is the workspace's own column
   *     and what a person reads on its page. A dispatch normally sends none: what a dispatched
   *     workspace is for is the reference below, not a copy of the row it was dispatched from
   * @param ticketId the caller's ticket this dispatch is about, carried onto the workspace as a
   *     field. Optional, and resolved by nothing here — the client composes the link to it
   * @param epicId the caller's epic this dispatch is about, the same way. Both are independent and
   *     both may be absent; a caller naming both is not refused, because "at most one" is a fact
   *     about the callers and not a rule this schema enforces
   * @param entityId the subject's qualified id as qits-projects spells it — {@code
   *     <project-slug>-<number>}, e.g. {@code qits-614}. Carried onto the workspace beside {@code
   *     ticketId}/{@code epicId}, exactly the same way: optional, resolved by nothing here. Read
   *     back by the in-container daemon to name its agent sessions {@code [❗]<status square>
   *     <entityId> <title>}, with the three fields below
   * @param entityTitle the same subject's title (qits-617). Optional; stored on the row and handed
   *     to the container as {@code QITS_WORKSPACE_DAEMON_ENTITY_TITLE}
   * @param entityStatus the subject's status word, qits-projects' enum constant (e.g. {@code
   *     REFINED}). Optional and uninterpreted here — the daemon picks the square
   * @param entityBlocked whether the subject is blocked; null reads as false. A caller that sends
   *     none of the three (an older qits-projects) leaves whatever the row already holds untouched
   * @param workId the work item's entity id in qits-projects, for any archetype (qits-112).
   *     Optional; when absent the workspace takes {@code ticketId}, else {@code epicId}. A dispatch
   *     naming a work item that already has an ACTIVE workspace in this repository is answered with
   *     that workspace, whatever branch it stands on
   * @param instruction the agent's first turn. It rides into the launch and is stored nowhere: this
   *     is the opening of one conversation, not the statement of the work
   * @param gitRefs the Git refs the workspace's container may push (contract C4): exact refs such
   *     as {@code refs/heads/ticket/fix-login} and trailing {@code /*} patterns, every entry under
   *     {@code refs/heads/}. Optional; absent means the workspace's own branch. Only a fresh
   *     workspace takes it — a re-press keeps the list the workspace already has. An entry that
   *     covers the repository's default branch is dropped: that branch moves only through a
   *     release request
   */
  public static record DispatchAgentRequest(
      @NotBlank String repositoryId,
      @NotBlank String branch,
      boolean branchTree,
      String preamble,
      String ticketId,
      String epicId,
      String entityId,
      String entityTitle,
      String entityStatus,
      Boolean entityBlocked,
      String instruction,
      List<String> gitRefs,
      String workId) {}

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
   *
   * <p>{@code agentIdentity} names the principal the dispatched agent's own calls carry: the idp
   * client commissioned for a DIRECT workspace's container, or a RUNNER workspace's token subject —
   * {@code DispatchService.Dispatch}'s javadoc has the full reasoning. Null until the container is
   * commissioned, which can be well after this call returns.
   *
   * <p><b>A workspace on a workspace runner may answer {@code workspace.runtimeStatus: QUEUED}</b>
   * (qits-626): its start put it in line for a runner slot, and {@code technicalProcessId} is that
   * start. {@code agentLaunch} is still {@code SCHEDULED} — the launch is parked until a runner
   * takes the workspace, and waits for its daemon from then on. A re-press while it is queued parks
   * nothing more. A workspace whose runner is offline past the grace is refused with 409 {@code
   * RUNNER_UNAVAILABLE} naming the runner.
   */
  @POST
  @APIResponse(
      responseCode = "200",
      description =
          "Dispatched. `fresh:false` means the branch already had a workspace and it was answered"
              + " instead — the ordinary case on a re-press, and not an error. `agentIdentity` is"
              + " the principal the dispatched agent's own calls carry (the commissioned client id"
              + " on a DIRECT workspace, the workspace token's subject on a RUNNER one); null until"
              + " the container is commissioned, never a secret. A workspace on a"
              + " workspace runner may answer `workspace.runtimeStatus: QUEUED` with"
              + " `agentLaunch: SCHEDULED`: it is waiting for a runner slot, `technicalProcessId` is"
              + " the start that queued it, and the agent is launched once a runner takes it and its"
              + " daemon answers. A re-press while it is queued schedules nothing more.")
  @APIResponse(
      responseCode = "400",
      description =
          "A blank repository or branch, a branch name git will not take, or a `gitRefs` list that"
              + " breaks the rules (every entry under `refs/heads/`, `*` only as a trailing `/*`,"
              + " at most 500 entries of at most 255 characters, no duplicates).",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such repository. Nothing was created.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "RUNNER_UNAVAILABLE: the workspace already on that branch is on a workspace runner that"
              + " is offline past its reconnect grace; the message names the runner. Nothing was"
              + " started or queued: the workspace is the runner's alone, so a dispatch fails"
              + " rather than wait for it.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  // The operationId is the name a consumer pact uses for this door as the TRIGGER of the calls it
  // makes downstream (pacts/qits-workspaces-service_qits-projects-service.json,
  // `qits-trigger`). Renaming it renames the trigger there.
  @Operation(operationId = "dispatchAgent")
  public DispatchService.Dispatch dispatch(@Valid DispatchAgentRequest request) {
    return dispatches.dispatch(
        request.repositoryId(),
        request.branch(),
        request.branchTree(),
        request.preamble(),
        new WorkspaceSubject(
            request.ticketId(), request.epicId(), request.entityId(), request.workId()),
        request.instruction(),
        request.gitRefs(),
        entityFactsOf(request));
  }

  /**
   * The three subject facts, or null when the caller sent none of them — an older qits-projects,
   * whose re-press must not wipe what a newer relay already stored on the row.
   */
  private static EntityFacts entityFactsOf(
      DispatchAgentRequest request) {
    if (request.entityTitle() == null
        && request.entityStatus() == null
        && request.entityBlocked() == null) {
      return null;
    }
    return new EntityFacts(
        request.entityTitle(),
        request.entityStatus(),
        Boolean.TRUE.equals(request.entityBlocked()));
  }

  /**
   * @param repositoryId the catalog id of the repository the branch is in. Resolved by nothing here:
   *     this verb creates no workspace, so there is no repository to require and an id naming
   *     nothing simply finds no workspace — which is the same 200 as a branch with none
   * @param branch the branch whose workspace is to be spoken to. A branch with no ACTIVE workspace
   *     is answered, not refused
   * @param text the turn, exactly as it should be said. Blank is a 400 — an empty turn is a request
   *     that cannot have been meant
   * @param compactFirst ask for a {@code /compact} turn ahead of it. Honoured only when {@code
   *     qits.workspace.agent-dispatch.compact-before-turn} is on, which it is not by default
   * @param workId the work item the workspace is bound to (qits-112). Optional; when given, the
   *     ACTIVE workspace bound to it is the one spoken to, and the branch is the fallback
   */
  public static record DeliverTurnRequest(
      @NotBlank String repositoryId,
      @NotBlank String branch,
      @NotBlank String text,
      boolean compactFirst,
      String workId) {}

  /**
   * <b>Say this to the workspace's agent</b> — the same thing a person would type into its chat tab.
   *
   * <p><b>The verb the platform was missing.</b> The dispatch above can only start a conversation,
   * which is why its answer has a {@code SKIPPED_RUNNING} in it: an agent that was already working
   * could be left alone and nothing else, because a user turn had exactly one entrance — a browser
   * on the daemon's command websocket — and no machine holds one. This is the host-side entrance.
   *
   * <p>One call, three arms, and <b>the caller does not choose</b>: an agent is running, so the text
   * is delivered to it; no agent is running, so it is launched with the text as its seed turn; no
   * workspace stands on the branch, so nothing happens. {@link DispatchService#deliver} holds the
   * semantics and the reason the third one never creates anything.
   *
   * <p><b>A branch with no workspace is a 200 with a null {@code workspaceId} and a sentence, and
   * that is a decision rather than laziness.</b> The caller is a machine reacting to a status
   * change, and "there was nobody to tell" is the ordinary outcome for a ticket nobody ever
   * dispatched — most of them. A 404 would make it an error at the far end: a stack trace per
   * transition, a retry loop over a condition no retry can change, and an alert channel that
   * eventually gets muted for the one case that matters.
   *
   * <p>{@code delivered} and {@code launched} report the arm this call took, read from the
   * workspace's state as it answered; the daemon round trip happens afterwards on a thread of this
   * service's own, for the reason the dispatch's launch does. Both false with a workspace id means
   * the container is still coming up and the arm is chosen when it answers.
   */
  @POST
  @Path("/delivery")
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `workspaceId: null` means no workspace stands on that branch — nothing was"
              + " said and nothing was created, which is a normal outcome and not an error. A"
              + " workspace queued for a workspace runner answers both flags false: the text waits"
              + " until a runner takes it and its daemon answers.")
  @APIResponse(
      responseCode = "400",
      description = "A blank repository, branch or text.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "RUNNER_UNAVAILABLE: the workspace on that branch is on a workspace runner that is"
              + " offline past its reconnect grace; the message names the runner. Nothing was said,"
              + " started or queued.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public DispatchService.Delivery deliver(@Valid DeliverTurnRequest request) {
    return dispatches.deliver(
        request.repositoryId(),
        request.workId(),
        request.branch(),
        request.text(),
        request.compactFirst());
  }

  /**
   * @param repositoryId the catalog id of the repository the branch is in. Resolved by nothing
   *     here, exactly {@link DeliverTurnRequest#repositoryId()}'s reason: this verb creates no
   *     workspace, so an id naming nothing simply finds no workspace, the same 200 as a branch with
   *     none
   * @param branch the branch whose workspace is to be told. A branch with no ACTIVE workspace is
   *     answered, not refused
   * @param blocked whether the subject this workspace was dispatched for is now blocked
   * @param workId the work item the workspace is bound to (qits-112). Optional; found first when
   *     given
   */
  public static record MarkBlockedRequest(
      @NotBlank String repositoryId, @NotBlank String branch, boolean blocked, String workId) {}

  /**
   * <b>Tell the workspace's agent session whether its subject is blocked</b> — the fact qits-
   * projects' ticket/epic phase machinery relays when a row moves into or out of BLOCKED, so the
   * in-container daemon can mark — or clear — the {@code "❗ "} it prefixes onto a live Claude
   * session's name. This is the relay: nothing here knows what "blocked" means to a ticket.
   *
   * <p><b>Never creates a workspace, and never wakes a stopped one.</b> {@link #deliver} above
   * still ensures a container, because a turn is something to deliver or launch into. A blocked
   * marker is neither: {@link DispatchService#markBlocked} asks the daemon only if it is already
   * reachable, and a workspace with no container, a cold one, or none at all for the branch
   * answers {@code applied:false} at once — this door never calls {@code beginEnsureContainer},
   * never schedules a delivery and never launches an agent. A rename nobody can see yet is not
   * worth starting one.
   *
   * <p>{@code applied:false} covers three things this door's caller cannot tell apart and does not
   * need to: no workspace stands on the branch, its daemon is not answering, or it answered with
   * something other than success (including the 404 an older daemon image gives a route it does
   * not carry yet). None of them is retried from this side — qits-projects holds the subject's
   * blocked state and will say so again the next time it changes.
   *
   * <p><b>Superseded by {@link #entity}, and kept while qits-projects still sends it.</b> It now also
   * writes the flag onto the workspace row (qits-617), so the row and the daemon agree whichever door
   * said it last, and a container started later boots marked.
   */
  @POST
  @Path("/blocked")
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `workspaceId: null` means no workspace stands on that branch; `applied: false`"
              + " with a non-null `workspaceId` means the daemon was not reachable or did not take"
              + " the marker. Neither is an error — this door never creates a workspace, ensures a"
              + " container or starts an agent.")
  @APIResponse(
      responseCode = "400",
      description = "A blank repository or branch.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public DispatchService.BlockedMark blocked(@Valid MarkBlockedRequest request) {
    return dispatches.markBlocked(
        request.repositoryId(), request.workId(), request.branch(), request.blocked());
  }

  /**
   * @param repositoryId the catalog id of the repository the branch is in. Resolved by nothing here,
   *     {@link MarkBlockedRequest#repositoryId()}'s reason
   * @param branch the branch whose workspace is to be told. A branch with no ACTIVE workspace is
   *     answered, not refused
   * @param title the subject's title now, or null when the caller has none
   * @param status the subject's status word now (e.g. {@code IMPLEMENTED}), or null
   * @param blocked whether the subject is blocked now. Required — a boxed {@code Boolean} so that a
   *     body without it is a 400 rather than a silent {@code false} that would clear a real {@code ❗}
   * @param workId the work item the workspace is bound to (qits-112). Optional; found first when
   *     given
   */
  public static record MarkEntityRequest(
      @NotBlank String repositoryId,
      @NotBlank String branch,
      String title,
      String status,
      @NotNull Boolean blocked,
      String workId) {}

  /**
   * <b>Tell the workspace what its subject looks like now</b> — title, status and blocked flag at
   * once — so its daemon can rename the agent sessions to {@code [❗]<status square> <id> <title>}
   * (qits-617). The successor of {@link #blocked}: qits-projects relays every transition, block and
   * title edit here, and the blocked flag is one of the three facts rather than a door of its own.
   *
   * <p><b>Two halves, and the first one does not depend on a container.</b> The facts are written
   * onto the ACTIVE workspace row whatever its container is doing, so a stopped container — or one
   * recreated later — boots with what is true now rather than what was true at its dispatch. Then,
   * only if the daemon is already reachable, it is told live through {@code POST /agents/entity},
   * falling back to {@code POST /agents/blocked} on the 404 an older daemon image answers.
   *
   * <p><b>Never creates a workspace, never ensures a container, never launches an agent</b> — {@link
   * #blocked}'s rule, for its reason. {@code applied} reports the live half only: {@code false} with
   * a non-null {@code workspaceId} means the row was updated and the daemon was not reachable or did
   * not take it.
   */
  @POST
  @Path("/entity")
  @APIResponse(
      responseCode = "200",
      description =
          "Answered. `workspaceId: null` means no workspace stands on that branch and nothing was"
              + " stored; otherwise the facts are stored on the workspace, and `applied` says whether"
              + " its daemon also took them live. Never creates a workspace, ensures a container or"
              + " starts an agent.")
  @APIResponse(
      responseCode = "400",
      description = "A blank repository or branch, or a missing or non-boolean `blocked`.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public DispatchService.BlockedMark entity(@Valid MarkEntityRequest request) {
    return dispatches.markEntity(
        request.repositoryId(),
        request.workId(),
        request.branch(),
        new EntityFacts(
            request.title(), request.status(), request.blocked().booleanValue()));
  }

  public static record ListSubjectRefsRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorkspaceSubjectRefDto workspace) {}
    }
  }

  /**
   * Which workspaces are on these qits-projects rows — the reference this door writes on a dispatch,
   * read back.
   *
   * <p><b>It is on THIS class and not on {@code WorkspaceController}, and that is the whole of why
   * the read exists here.</b> It shipped there once (2026.911.151414) and was dead on arrival: that
   * class is {@code @RolesAllowed("qits:admin")}, a person's door, and its caller is qits-projects
   * holding a machine credential — so every lookup was a 403, the caller's never-throw contract read
   * it as "no workspaces are on this row", and the feature was deployed and inert with nothing
   * saying so. That is the 403 of 2026-09-03 for the third time, and it is exactly what this class's
   * own javadoc already warned about. A machine read belongs on a class that states {@code
   * qits:system}, and this one is the class that does.
   *
   * <p><b>Both parameters repeat, and that is the point of the route.</b> The caller is a project's
   * tickets panel with a screenful of rows, and asking once per row would be a round trip per
   * ticket. Either may be omitted; neither given answers an empty list rather than the whole table,
   * because "tell me about no rows" has exactly one honest answer.
   *
   * <p><b>A thin shape, not the listing's.</b> {@code GET /workspaces} is scoped to one repository
   * and pays a mirror refresh, a container listing and an ahead/behind computation per row, because
   * it draws a branch tree. This question crosses repositories and wants none of it — see {@link
   * WorkspaceSubjectRefDto}, and see {@code WorkspaceRepository.findBySubjects} for which rows it
   * answers with.
   *
   * <p>Rows the caller did not ask about never appear. Every workspace that names a row it did ask
   * about does, carrying its own {@code status} and {@code resolvedAt}, and whether an INTEGRATED
   * one is still interesting is the caller's call and not this door's: a ticket whose workspace
   * landed is a different thing to say than a ticket that never had one, and a door that dropped the
   * resolved row would make the two indistinguishable. Nothing over there has to clear anything
   * either way — the reference lives on the workspace, which is what lets it keep being true.
   */
  // A read, so an agent may make it too (phase 4: agents keep every read, lose writes). It
  // replaces the class's list, so the class's two roles are stated again.
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:system", "qits:agent"})
  @GET
  @Path("/references")
  public ListSubjectRefsRequest.Response references(
      @QueryParam("ticketId") List<String> ticketIds,
      @QueryParam("epicId") List<String> epicIds,
      @QueryParam("workId") List<String> workIds) {
    var entries =
        workspaces.workspacesReferencing(ticketIds, epicIds, workIds).stream()
            .map(ListSubjectRefsRequest.Response.Entry::new)
            .toList();
    return new ListSubjectRefsRequest.Response(entries);
  }
}
