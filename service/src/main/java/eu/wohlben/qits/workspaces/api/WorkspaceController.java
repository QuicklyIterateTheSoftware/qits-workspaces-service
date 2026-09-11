package eu.wohlben.qits.workspaces.api;

import eu.wohlben.qits.workspaces.control.WorkspaceProcessTracker;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.dto.WorkspaceDto;
import eu.wohlben.qits.workspaces.mapper.WorkspaceMapper;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
@jakarta.annotation.security.RolesAllowed("qits:admin")
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
  @GET
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
   */
  @GET
  @Path("/{id}")
  @APIResponse(responseCode = "200", description = "The workspace.")
  @APIResponse(
      responseCode = "404",
      description = "No such ACTIVE workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public GetWorkspaceRequest.Response get(@PathParam("id") Long id) {
    return new GetWorkspaceRequest.Response(workspaceService.getWorkspace(id));
  }

  /**
   * {@code repositoryId} rides in the body rather than the path or a query parameter: a create
   * carries its scope in the payload, and the repository is not a filter on a POST.
   *
   * <p>{@code id} is the requested <em>label</em> (the path/container-name segment), not an
   * identifier — the created workspace's identifier comes back in the response.
   *
   * <p>{@code adoptExisting} lets the workspace take over a branch that already exists (the
   * branch-list "Create workspace" button on a workspaceless branch) instead of forking a new one;
   * false for the normal "branch off" flow, which creates a fresh branch and 409s on a name
   * collision. Either way the branch must have no active workspace already — it is the resource
   * being claimed.
   *
   * <p>{@code admin} asks for the <b>admin posture</b>: the workspace's container is launched with
   * the host's docker socket bound into it, so that platform administration can be done from inside
   * the workspace. It is <b>off unless the request says otherwise</b> — a field absent from the body
   * is an ordinary workspace, which is what every existing client sends — and it is decided here and
   * never again: no route promotes an existing workspace, because the point of the posture is that
   * the socket belongs to the few workspaces somebody deliberately created for it.
   *
   * <p>Who may ask is answered by this class's own {@code @RolesAllowed("qits:admin")}: creating any
   * workspace already requires the platform admin role, and the socket is granted per workspace
   * rather than per caller. A second role invented here would be a vocabulary the platform's idp
   * does not issue and a gate nothing could pass.
   */
  public static record CreateWorkspaceRequest(
      @NotBlank String repositoryId,
      @NotBlank String id,
      String parent,
      String branch,
      String preamble,
      boolean adoptExisting,
      boolean branchTree,
      boolean admin) {
    /** The form before the admin posture existed: an ordinary, socket-free workspace. */
    public CreateWorkspaceRequest(
        String repositoryId,
        String id,
        String parent,
        String branch,
        String preamble,
        boolean adoptExisting,
        boolean branchTree) {
      this(repositoryId, id, parent, branch, preamble, adoptExisting, branchTree, false);
    }

    /** Backward-compatible form used before aggregate wrapper workspaces were introduced. */
    public CreateWorkspaceRequest(
        String repositoryId,
        String id,
        String parent,
        String branch,
        String preamble,
        boolean adoptExisting) {
      this(repositoryId, id, parent, branch, preamble, adoptExisting, false, false);
    }

    /** Backward-compatible "branch off" form: create a new branch, never adopt an existing one. */
    public CreateWorkspaceRequest(
        String repositoryId, String id, String parent, String branch, String preamble) {
      this(repositoryId, id, parent, branch, preamble, false, false, false);
    }

    public record Response(WorkspaceDto workspace) {}
  }

  @POST
  public CreateWorkspaceRequest.Response create(@Valid CreateWorkspaceRequest request) {
    var wt =
        workspaceService.createWorkspace(
            request.repositoryId(),
            request.id(),
            request.parent(),
            request.branch(),
            request.preamble(),
            request.adoptExisting(),
            request.branchTree(),
            request.admin());
    return new CreateWorkspaceRequest.Response(workspaceMapper.toDto(wt));
  }

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
   * clone, submodule wiring, service auto-start — streams over the process's SSE endpoint, where
   * failures surface too (alongside the workspace's {@code runtimeError}). 404 only when the
   * workspace itself is unknown.
   */
  @POST
  @Path("/{id}/ensure-container")
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
  @GET
  @Path("/{id}/active-process")
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

  public static record MergeWorkspaceRequest(String target) {
    public record Response(String commitHash, boolean hasConflicts, String output) {}
  }

  /**
   * Merge this workspace's branch into an arbitrary target, in a host-side worktree, without a
   * push.
   *
   * <p>409 when the target resolves to the repository's default branch, which this service does not
   * write at all — a release request in qits-projects does. {@code /integrate} is the door for a
   * merge into this branch's parent.
   */
  @POST
  @Path("/{id}/merge")
  @APIResponse(responseCode = "200", description = "Merged.")
  @APIResponse(
      responseCode = "409",
      description =
          "The target is the repository's default branch. Use /integrate, or ask qits-projects for"
              + " a release request.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public MergeWorkspaceRequest.Response merge(
      @PathParam("id") Long id,
      @Valid MergeWorkspaceRequest request) {
    var result = workspaceService.mergeWorkspace(id, request.target());
    return new MergeWorkspaceRequest.Response(
        result.commitHash(), result.hasConflicts(), result.output());
  }

  /** @param summary the commit's subject after the {@code integrate(<branch>)} scope. */
  public static record IntegrateRequest(@NotBlank @Size(max = 100) String summary) {
    /** @see eu.wohlben.qits.workspaces.control.WorkspaceService.IntegrateResult */
    public record Response(String commitSha, String branch, String targetBranch) {}
  }

  /**
   * Integrate this workspace into <b>its parent branch</b>: a {@code task/…} landing on the {@code
   * epic/…} it forked from, as one pushed merge commit — {@code integrate(<branch>): <summary>}.
   *
   * <p><b>No version, and that is the difference.</b> This stamps nothing and bumps no manifest;
   * the response carries no {@code version} field for the same reason. Releasing the epic is a
   * release request in qits-projects, and a workspace forked straight off the default branch is
   * refused with a 409 naming it, because its parent is the one branch this service does not write.
   *
   * <p>The mechanics are what they always were — repository lease, merge-tree preflight, detached
   * worktree on a private mirror, {@code merge --no-ff}, one two-parent commit, a fast-forward push
   * through receive-pack, worktree cleanup in a {@code finally}, and the 409 family with its {@code
   * reason} values. The workspace resolves to {@code INTEGRATED} and its branch is deleted.
   */
  @POST
  @Path("/{id}/integrate")
  @APIResponse(responseCode = "200", description = "Merged into the parent branch.")
  @APIResponse(
      responseCode = "400",
      description = "No summary, an oversized one, or a workspace with no branch to integrate.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "404",
      description = "No such workspace.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  @APIResponse(
      responseCode = "409",
      description =
          "Nothing landed and the target is unchanged — including when the parent is the default"
              + " branch, which only the release flow writes. `reason` says which refusal.",
      content = @Content(schema = @Schema(implementation = ApiError.class)))
  public IntegrateRequest.Response integrate(
      @PathParam("id") Long id, @Valid IntegrateRequest request) {
    var result = workspaceService.integrateWorkspace(id, request.summary());
    return new IntegrateRequest.Response(
        result.commitSha(), result.branch(), result.targetBranch());
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
