package eu.wohlben.qits.workspaces.error;

/**
 * The refusals of moving a regular DIRECT workspace onto a runner (qits-776), each with the {@code
 * code} a client branches on — the move gate's vocabulary, spelled once. The 400 sentences follow
 * recreate's clean-tree guard: what was asked, what stands in the way, what to do about it.
 */
public final class MoveRefusals {

  /** An admin or editor workspace: DIRECT by design, and never moved. */
  public static final String NOT_REGULAR = "NOT_REGULAR";

  /** The row is RUNNER already — moved before, or created there. */
  public static final String ALREADY_MOVED = "ALREADY_MOVED";

  /** The row's container is being provisioned; its tree cannot be judged yet. */
  public static final String PROVISIONING = "PROVISIONING";

  /** The daemon reports uncommitted changes. */
  public static final String DIRTY = "DIRTY";

  /** No daemon has reported the working tree, so it cannot be called clean. */
  public static final String UNKNOWN = "UNKNOWN";

  /** The container holds commits the git host does not, or that could not be confirmed. */
  public static final String UNPUSHED = "UNPUSHED";

  private MoveRefusals() {}

  /** 400 {@link #NOT_REGULAR}. */
  public static DomainException notRegular(String workspaceId) {
    return new DomainException(
        400,
        NOT_REGULAR,
        "Cannot move workspace '"
            + workspaceId
            + "' onto a runner: it is an admin or editor workspace, and those run on the direct path"
            + " by design.");
  }

  /** 409 {@link #ALREADY_MOVED}. */
  public static ConflictException alreadyMoved(String workspaceId) {
    return new ConflictException(
        ALREADY_MOVED,
        "Workspace '" + workspaceId + "' is already placed on the runners; there is nothing to move.");
  }

  /** 400 {@link #PROVISIONING}. */
  public static DomainException provisioning(String workspaceId) {
    return new DomainException(
        400,
        PROVISIONING,
        "Cannot move workspace '"
            + workspaceId
            + "' onto a runner: its container is still being provisioned. Wait for it to finish,"
            + " then try again.");
  }

  /** 400 {@link #DIRTY} or {@link #UNKNOWN}, from the daemon's reported state. */
  public static DomainException notClean(String workspaceId, boolean known) {
    return new DomainException(
        400,
        known ? DIRTY : UNKNOWN,
        "Cannot move workspace '"
            + workspaceId
            + "' onto a runner: its working tree must be clean, but its reported state is "
            + (known ? "dirty" : "unknown")
            + ". Commit or discard changes, and ensure its daemon is connected, first.");
  }

  /** 400 {@link #UNPUSHED}. */
  public static DomainException unpushed(String workspaceId) {
    return new DomainException(
        400,
        UNPUSHED,
        "Cannot move workspace '"
            + workspaceId
            + "' onto a runner: its container holds commits the git host does not have (or that"
            + " could not be confirmed). Let its daemon push them, then try again.");
  }
}
