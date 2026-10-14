package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceProcessTracker;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.mapper.WorkspaceMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Workspaces, addressed by their own id.
 *
 * <p><strong>A workspace is not a sub-resource of a repository here.</strong> This context does not
 * own repositories — it holds a repository id as a string, with no foreign key and no join, in a
 * different database. Addressing these routes as {@code /repositories/{repoId}/workspaces/...}
 * asserted a containment the model deliberately does not have. So the entity leads: a single
 * workspace is {@code {id}} alone — {@link
 * eu.wohlben.qits.workspaces.entity.Workspace#id}, the generated key every FK'd child table already
 * uses — and the repository is <em>scope</em> on the collection, which is what it actually is.
 *
 * <p>Not the string {@code workspaceId}: that is a branch-derived label, unique only per repository
 * and reusable once a workspace resolves, which is why it needed a {@code repoId} beside it to
 * identify anything. A unique id is already unique.
 */
@Path("/workspaces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent"})
public class WorkspaceController {

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceMapper workspaceMapper;

  /**
   * Optional, like everywhere else this context touches the technical-process framework: with no
   * tracker installed {@code /active-process} answers null, which is already its "none is live"
   * response.
   */
  @Inject Instance<WorkspaceProcessTracker> technicalProcesses;

  public static record ListWorkspacesRequest() {
    public record Response(List<Entry> entries) {
      public record Entry(WorkspaceDto workspace) {}
    }
  }

  /** The repository is a filter on the collection, not a parent segment. */
  // The three reads here also take qits:agent (phase 4: agents keep every read, lose writes). A
  // method-level list replaces the class's, so each read states the admin role again.
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @GET
  @Operation(operationId = "listWorkspaces", summary = "A repository's ACTIVE workspaces")
  public ListWorkspacesRequest.Response list(@QueryParam("repositoryId") String repositoryId) {
    var entries =
        workspaceService.listWorkspaces(repositoryId).stream()
            .map(ListWorkspacesRequest.Response.Entry::new)
            .toList();
    return new ListWorkspacesRequest.Response(entries);
  }

  public static record GetWorkspaceRequest() {
    public record Response(WorkspaceDto workspace) {}
  }

  /**
   * One workspace, by its own id — the live view the collection above serves per row, without
   * needing to know the repository first.
   *
   * <p>The repository was never an identity here, only a filter, so a detail page can be opened
   * from a bare id. It is <em>not</em> a second shape: this is the same {@link WorkspaceDto} the
   * list and four of the mutations already return, so a client's row cache and its detail cache
   * hold one type. {@code /history/{id}} is the other read on an id and answers a different
   * question — the narrative record, which survives resolution and carries no branch, runtime or
   * daemon fields.
   *
   * <p>ACTIVE only, and 404 otherwise. A resolved workspace has no container, no daemon and no
   * branch to be ahead of anything, so serving one here would answer with a row whose live half is
   * uniformly null; {@code /history/{id}} is where it is readable, and the client routes there.
   *
   * <p>Costs the repository's whole listing internally (ahead/behind is computed per workspace
   * against the mirror), which is why it is a read and not a poll — the workspace's SSE channel is
   * what says when to call it again.
   *
   * <p><b>The editor workspace answers here too, and it is the one row that does not cost that.</b>
   * It belongs to no repository, so there is no listing to run and no mirror to refresh: it is read
   * straight off its row, with the live container/daemon half asked exactly as the listing asks it.
   * The fields a repository would have supplied — the main branch, ahead, behind — come back null,
   * because there is nothing for an editor to be ahead OF, and a client reads them the way it reads
   * every other unknown on this shape.
   */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @GET
  @Path("/{id}")
  @APIResponse(responseCode = "200", description = "The workspace.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @Operation(operationId = "getWorkspace")
  public GetWorkspaceRequest.Response get(@PathParam("id") Long id) {
    return new GetWorkspaceRequest.Response(workspaceService.getWorkspace(id));
  }

  // POST /workspaces (the ad-hoc create) is gone (qits-1152): a workspace is a slot the agent
  // scheduler creates when an agent needs one. A person asks for an agent on a work item instead,
  // POST /workspaces/api/agents (D24).

  public static record EnsureContainerRequest() {
    /**
     * The workspace's state at submit time plus the technical process streaming the start — watch
     * it live (replay + live + terminal {@code done}) at {@code
     * /workspaces/api/technical-processes/{technicalProcessId}/events}.
     */
    public record Response(WorkspaceDto workspace, String technicalProcessId) {}
  }

  /**
   * Start (or recreate) the workspace's container on demand — the container is a recreatable cache
   * of the durable branch. Idempotent (a running container is left as-is). The provision runs
   * asynchronously: this returns a technical-process id immediately and the work — docker run,
   * clone, submodule wiring, the bootstrap chain — streams over the process's SSE endpoint, where
   * failures surface too (alongside the workspace's {@code runtimeError}). 404 only when the
   * workspace itself is unknown.
   *
   * <p>A start already under way is joined (qits-853), but only one that can still bring the
   * container up (qits-1076): 409 {@code WORKSPACE_STOPPED_DURING_START} when the workspace was
   * stopped while a start was open and that start's process has not ended yet. The message names
   * the process; retrying once it has ended starts the workspace afresh.
   */
  @POST
  @Path("/{id}/ensure-container")
  @APIResponse(
      responseCode = "200",
      description = "The start this call made, or the start under way that it joined.")
  @APIResponse(
      responseCode = "409",
      description =
          "The workspace was stopped while it was starting and that start's process is still"
              + " open. `code` is WORKSPACE_STOPPED_DURING_START and the message names the process;"
              + " retry once it has ended.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public EnsureContainerRequest.Response ensureContainer(
      @PathParam("id") Long id) {
    String technicalProcessId = workspaceService.beginEnsureContainer(id);
    return new EnsureContainerRequest.Response(
        workspaceService.getWorkspace(id), technicalProcessId);
  }

  public static record ActiveProcessRequest() {
    /** The workspace's currently-running technical process, or null when none is live. */
    public record Response(String technicalProcessId) {}
  }

  /**
   * Discovery for the workspace detail route's transient process tab: the id of the technical
   * process currently running against this workspace (null once it completes). Announced over the
   * workspace's payload-free SSE channel as a {@code process} hint, so clients re-fetch this
   * instead of polling.
   */
  @jakarta.annotation.security.RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
  @GET
  @Path("/{id}/active-process")
  @Operation(operationId = "getWorkspaceActiveProcess")
  public ActiveProcessRequest.Response activeProcess(
      @PathParam("id") Long id) {
    return new ActiveProcessRequest.Response(
        technicalProcesses.isResolvable()
            ? technicalProcesses.get().activeFor(id).orElse(null)
            : null);
  }

  /**
   * Gracefully stop the workspace's container: its branch is pushed to origin first (so committed
   * work survives), then the container is removed and the workspace is left ACTIVE/STOPPED for lazy
   * recreate. Returns the refreshed workspace.
   */
  @POST
  @Path("/{id}/stop-container")
  public WorkspaceDto stopContainer(
      @PathParam("id") Long id) {
    workspaceService.stopContainer(id);
    return workspaceService.getWorkspace(id);
  }

  /**
   * Delete the workspace's container outright ({@code docker rm}) while keeping its branch and the
   * workspace row — the destructive counterpart to the graceful {@link #stopContainer}, which
   * pauses in place. Reclaims the (stopped) container and any uncommitted changes in it; the next
   * Start re-clones a fresh container from the durable branch. Does NOT delete the branch — that is
   * {@code discard} (Abandon). Returns the refreshed workspace; 404 only when the workspace is
   * unknown.
   */
  @POST
  @Path("/{id}/delete-container")
  public WorkspaceDto deleteContainer(
      @PathParam("id") Long id) {
    workspaceService.deleteContainer(id);
    return workspaceService.getWorkspace(id);
  }

  public static record RecreateContainerRequest() {
    /**
     * The workspace's state at submit time plus the technical process streaming the recreate —
     * watch it live at {@code /workspaces/api/technical-processes/{technicalProcessId}/events}.
     */
    public record Response(WorkspaceDto workspace, String technicalProcessId) {}
  }

  /**
   * Recreate the workspace's container on the current image — the way to move it onto a newer
   * workspace-daemon build once its version badge shows it is outdated
   * (docs/epics/qits-workspace-registry/). The old container is torn down and a fresh one
   * provisioned from the durable branch; the work streams over the returned technical process like
   * {@code ensure-container}. Refuses with 400 unless the working tree is provably clean — a dirty
   * or unknown (no live daemon) state is rejected, since recreating would risk losing work. 404
   * only when the workspace itself is unknown.
   */
  @POST
  @Path("/{id}/recreate-container")
  public RecreateContainerRequest.Response recreateContainer(
      @PathParam("id") Long id) {
    String technicalProcessId = workspaceService.beginRecreateContainer(id);
    return new RecreateContainerRequest.Response(
        workspaceService.getWorkspace(id), technicalProcessId);
  }

  // POST /{workspaceId}/fast-forward and /{workspaceId}/update-from-parent used to live here. Both
  // were `docker exec git fetch/merge --ff-only/merge --no-edit/push` against the checkout inside
  // the container — the host reaching past the daemon into the workspace it owns. They moved to the
  // workspace-daemon's own HTTP API, where the checkout is a local java.nio path and the git runs
  // in-process, alongside /files, /detection and /component-map. The host keeps only the state it
  // owns: the Workspace row, the parent, and the UPDATED_FROM_PARENT event.

  public static record DiscardWorkspaceRequest(String result) {
    public record Response(boolean success) {}
  }

  /**
   * {@code ?ignore-changes=true} overrides the clean-working-tree guard: the discard proceeds even
   * while the daemon reports uncommitted changes, which are then lost with the container and the
   * branch. It rides the URL and not the body <b>on purpose</b>: the ordinary discard request must
   * be incapable of carrying the override by accident, so a client first discards plainly, is
   * refused by the guard, and only a person who confirmed that refusal re-sends with the parameter
   * spelled out. Absent means guarded, always.
   */
  @POST
  @Path("/{id}/discard")
  public DiscardWorkspaceRequest.Response discard(
      @PathParam("id") Long id,
      @QueryParam("ignore-changes") @DefaultValue("false") boolean ignoreChanges,
      @Valid DiscardWorkspaceRequest request) {
    workspaceService.discardWorkspace(
        id, request == null ? null : request.result(), ignoreChanges);
    return new DiscardWorkspaceRequest.Response(true);
  }

}
