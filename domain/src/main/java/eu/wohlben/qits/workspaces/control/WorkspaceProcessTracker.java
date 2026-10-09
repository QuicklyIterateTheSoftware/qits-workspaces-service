package eu.wohlben.qits.workspaces.control;

import java.util.Optional;

/**
 * Frames a long-running workspace operation — bringing a container up, recreating it — as a
 * segmented, streamable technical process, so a UI can follow {@code container} → {@code clone} →
 * {@code container-start} live instead of watching a spinner.
 *
 * <p>A port, not an implementation: the technical-process framework itself is a cross-context
 * streaming primitive (it also carries repository push/pull), so it stays with the owning
 * application and this context consumes only the verbs it actually calls.
 *
 * <p>Injected as {@code Instance<WorkspaceProcessTracker>} and genuinely optional. With no
 * implementation present the work still happens, on the same worker thread, with the same result —
 * only the streamed narration is absent and the returned process id is {@code null}, which the
 * ensure/recreate responses already permit.
 */
public interface WorkspaceProcessTracker {

  /** Begin tracking an operation on this workspace. */
  Handle begin(String repoId, String workspaceId, Long workspaceRowId);

  /**
   * The id of the operation currently live for this workspace, if any. Takes the workspace's own
   * id: this is the route-facing half of the port, and a workspace is addressed by its identifier.
   */
  Optional<String> activeFor(Long id);

  /**
   * {@link #activeFor(Long)} keyed the way {@link #begin} registered it, with no row read behind it.
   * This is the form a caller uses where no session may be open — the stop's after-commit hook,
   * which ends a start the stop overtook (qits-1076).
   */
  Optional<String> activeFor(String repoId, String workspaceId);

  /**
   * End a live operation as failed, whatever phase it is in, with {@code message} as the last line
   * of its stream. Unlike {@link Handle#failProvision}, which is a no-op once the provision phase
   * has reported, this also ends an operation that is past its provision and waiting on the
   * bootstrap chain — the window a start spends with its row already RUNNING.
   *
   * <p>It exists for one caller: a stop (or a container delete) that lands while a start is still
   * open (qits-1076). The start will never bring the container up now, and a process left open
   * would be joined by the next ensure-container as if it would. No-op when {@code processId} is
   * unknown or already ended.
   *
   * @return whether a live operation was ended
   */
  boolean end(String processId, String message);

  /** One tracked operation. Segment names are free-form and shared with the frontend. */
  interface Handle {

    /** The process id, as handed back to the caller of ensure/recreate. */
    String id();

    /** Open a segment; output appended afterwards belongs to it until it settles. */
    void openSegment(String name);

    /** Append one line of output to an open segment. */
    void appendLine(String segmentName, String line);

    /** Close a segment, successfully or not. */
    void settleSegment(String segmentName, boolean ok);

    /** Close a segment that had nothing to do, with the reason shown in its place. */
    void completeNoOp(String segmentName, String note);

    /** Terminal: the operation finished. */
    void finishProvision(boolean ok);

    /** Terminal: the operation failed, with the message shown to the user. */
    void failProvision(String message);
  }
}
