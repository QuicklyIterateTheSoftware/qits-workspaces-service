package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * The web editor's one door: <b>there should be an editor</b>, said idempotently.
 *
 * <p>One call and not two, because the request is the same sentence whether or not anything is
 * running: a caller polls this and nothing else, and a reader who reloads mid-start rejoins the
 * editor that is already coming up instead of asking for a second one. It answers what it did — a
 * fresh start or an existing one — and what the caller acts on, which is one boolean.
 *
 * <h2>One editor, one row, one container, for the whole platform</h2>
 *
 * <p>There is a single editor workspace and the door takes no scope, because there is nothing to
 * scope it by. {@code WorkspaceService.createEditorWorkspace} finds that row or writes it — {@code
 * workspaceId} and {@code repositoryId} both {@link EditorWorkspace}'s constants, no branch, no
 * parent, {@code editor = true} — and the container derived from it is therefore a constant too:
 * {@code qits-ws-editor-editor}, on the volume {@code qits_workspace_editor}. So two people opening
 * the editor from two different projects land on the same row and the same container, which is the
 * entire point of the change and the reason this call carries no project.
 *
 * <p><b>What that replaced was a derivation.</b> The editor used to be a project's wrapper
 * repository's main workspace — one per project, launched from the editor image because of what
 * that workspace <em>was</em> — so this door took a repository id, refused anything that was not a
 * wrapper, and leaned on {@code createMainWorkspace}. None of that survives a single editor: there
 * is no project, so there is no wrapper to check and no repository to be sent at the wrong one of.
 *
 * <p><b>The answer still carries the workspace's row id, and for the same reason it always did.</b>
 * The two things a caller can do about a stuck editor are the ordinary container verbs: {@code
 * /workspaces/{id}/stop-container} and {@code /recreate-container} are aimed at that id, and they
 * are the same routes the workspace detail page uses.
 *
 * <h2>Why there are no locks here</h2>
 *
 * <p>Double-provision safety is structural and predates this door. {@code createEditorWorkspace} is
 * find-or-write, {@code uq_workspace_active_editor} makes that true under a race rather than by
 * agreement — the same arrangement {@code createMainWorkspace} has with {@code
 * uq_workspace_active_branch} — and the orchestrator's ensure is a PUT per {@code (owner, workload,
 * ref)}, so a second ensure adopts the place the first one made. What this class adds is not a lock
 * but a reason not to ask: an ensure is not started while a technical process is already running for
 * the workspace, or while its container is up with a daemon on the socket. Without that, a client
 * polling every two seconds would spawn one provision per tick through a multi-gigabyte image pull.
 */
@ApplicationScoped
public class EditorService {

  @Inject WorkspaceService workspaces;

  @Inject WorkspaceRepository workspaceRepository;

  /**
   * The technical process a start streams over. Optional like everywhere else this context touches
   * the framework — absent means "no process is running", which is also its answer when one is not.
   */
  @Inject Instance<WorkspaceProcessTracker> processes;

  /**
   * Whether the workspace's daemon holds an open control socket. Read to decide whether a container
   * that says RUNNING is worth asking about again — a free in-memory read, unlike the status call an
   * ensure makes.
   */
  @Inject Instance<WorkspaceDaemonLiveness> liveness;

  /**
   * The in-container editor's own state, as {@code WorkspaceDaemonRegistry} caches it off the
   * daemon's {@code EditorState} frame. An {@code Instance<>} like every other port here: a build
   * with no control plane answers empty, which this door reads as {@code editorState: null} /
   * {@code editorReady: false} — a caller waits, which is what an unreported editor deserves.
   */
  @Inject Instance<WorkspaceEditorState> editorStates;

  /**
   * What the door answers, and the whole of it.
   *
   * @param workspaceId the workspace's <b>row id</b> — the identity every route addresses, carried
   *     as a String because that is how JSON spells an identifier and because the client hands it
   *     straight back to the container verbs
   * @param containerStatus the workspace's runtime status, as this service last recorded it
   * @param editorState what the daemon last reported about the editor, or null when nothing has been
   *     reported — a plain workspace, a container that is not up, and a first frame that has not
   *     arrived are one answer here, because they deserve the same one
   * @param editorReady the readiness, and the only field a caller should act on: the container is
   *     running <em>and</em> the editor inside it says it is serving. A client that judged from
   *     {@code editorState} would be deciding for itself when the editor answers requests
   * @param fresh whether this call started something. It is not part of the body — it is what
   *     becomes 201 rather than 200
   */
  public record EditorSession(
      String workspaceId,
      String containerStatus,
      EditorLifecycle editorState,
      boolean editorReady,
      boolean fresh) {}

  /**
   * Find or start the editor. It takes no argument, because there is one editor.
   *
   * <p>No refusals of its own, and that is the shape of the change: the 400 for "that repository is
   * not a project's wrapper" and the 404 for "no such repository" both existed to stop a caller
   * polling a workspace that could never become ready, and neither can be asked for any more.
   */
  public EditorSession ensure() {
    // Find-or-write, and that is what makes this door one: the existing editor row is handed back, a
    // missing one is written, and two callers racing are settled by the partial unique index rather
    // than by either of them holding anything.
    Workspace workspace = workspaces.createEditorWorkspace();
    Long rowId = workspace.id;

    EditorLifecycle editorState = editorState(rowId);
    WorkspaceRuntimeStatus status = workspace.runtimeStatus;
    boolean fresh = false;
    if (worthStarting(rowId, status)) {
      workspaces.beginEnsureContainer(rowId);
      fresh = true;
      // Re-read rather than reporting the status this call was handed: beginEnsureContainer marks
      // the row on its own thread and the caller's next poll is two seconds away, so the value read
      // a line ago is the one thing here that is already stale.
      status = runtimeStatus(rowId).orElse(status);
    }

    boolean ready = status == WorkspaceRuntimeStatus.RUNNING && editorState == EditorLifecycle.RUNNING;
    return new EditorSession(Long.toString(rowId), status.name(), editorState, ready, fresh);
  }

  /**
   * Whether to ask for a container at all.
   *
   * <p><b>Two reasons not to, and both are about a poll rather than about correctness.</b> A
   * technical process already running for this workspace <em>is</em> the start this call would make.
   * And a container that is RUNNING with a daemon on its socket is up: the editor inside it may still
   * be STARTING, and an ensure would short-circuit on the orchestrator's status call and complete a
   * process as a no-op — once every two seconds, for as long as the editor takes to boot.
   *
   * <p>A RUNNING row whose daemon is <em>not</em> live is asked about anyway, deliberately: that is
   * what a container which died out-of-band looks like, and the ensure ladder's own first step is to
   * find that out. Falling the other way would leave the one broken state with no way back except a
   * person pressing Recreate.
   */
  private boolean worthStarting(Long rowId, WorkspaceRuntimeStatus status) {
    if (processes.isResolvable() && processes.get().activeFor(rowId).isPresent()) {
      return false;
    }
    return !(status == WorkspaceRuntimeStatus.RUNNING && daemonLive(rowId));
  }

  private boolean daemonLive(Long rowId) {
    if (!liveness.isResolvable()) {
      return false;
    }
    try {
      return liveness.get().isDaemonLive(rowId);
    } catch (RuntimeException e) {
      return false;
    }
  }

  /** The daemon's report, or null while the port that carries it is unsatisfied. */
  private EditorLifecycle editorState(Long rowId) {
    if (!editorStates.isResolvable()) {
      return null;
    }
    try {
      return editorStates.get().editorStateFor(rowId).orElse(null);
    } catch (RuntimeException e) {
      return null; // an unreadable report is "nothing reported", which is what waiting means
    }
  }

  /** The row's runtime status in a transaction of its own — the caller may have none. */
  private Optional<WorkspaceRuntimeStatus> runtimeStatus(Long rowId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> workspaceRepository.findActiveById(rowId).map(w -> w.runtimeStatus));
  }
}
