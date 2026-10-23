package eu.wohlben.qits.workspaces.error;

/**
 * The refusals of a TARGET-placed workspace's verbs (epic qits-1481), and of a create that asks a
 * regular workspace off the targets (qits-774), each with the {@code code} a client branches on,
 * spelled once.
 */
public final class TargetRefusals {

  /**
   * A regular workspace was asked for on the direct path (qits-774): it runs in a WORKSPACE slot of
   * an execution target, and only admin workspaces are DIRECT.
   */
  public static final String DIRECT_PLACEMENT_REFUSED = "DIRECT_PLACEMENT_REFUSED";

  /**
   * qits-system could not be reached, or refused the lease call outright: no lease could be asked
   * for, ended or read. The row is unchanged.
   */
  public static final String TARGET_UNAVAILABLE = "TARGET_UNAVAILABLE";

  /**
   * The row's previous lease did not finish ending within its deadline: a start cannot ask for a new
   * one, and a delete-container cannot purge the work volume, while it is still ENDING.
   */
  public static final String TARGET_TIMEOUT = "TARGET_TIMEOUT";

  private TargetRefusals() {}

  /** 400 {@link #DIRECT_PLACEMENT_REFUSED}. */
  public static DomainException directPlacementRefused() {
    return new DomainException(
        400,
        DIRECT_PLACEMENT_REFUSED,
        "Regular workspaces run in a workspace slot of an execution target; only admin workspaces"
            + " use the direct path.");
  }

  /** 409 {@link #TARGET_UNAVAILABLE}, naming the workspace, what was asked and why. */
  public static ConflictException unavailable(Long rowId, String verb, String why) {
    return new ConflictException(
        TARGET_UNAVAILABLE,
        "Workspace "
            + rowId
            + " cannot "
            + verb
            + ": qits-system did not take the lease call"
            + (why == null || why.isBlank() ? "" : " (" + why + ")"));
  }

  /** 504 {@link #TARGET_TIMEOUT}: the previous lease is still ending, and nothing was written. */
  public static DomainException timeout(Long rowId, String verb) {
    return new DomainException(
        504,
        TARGET_TIMEOUT,
        "The previous lease of workspace "
            + rowId
            + " did not finish ending in time, so it cannot "
            + verb
            + " yet; try again shortly");
  }
}
