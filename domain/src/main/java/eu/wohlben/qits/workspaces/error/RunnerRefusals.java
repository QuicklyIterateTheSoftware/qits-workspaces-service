package eu.wohlben.qits.workspaces.error;

/**
 * The refusals of a RUNNER-placed workspace's verbs (epic qits-624), and of a create that asks a
 * regular workspace off the runners (qits-774), each with the {@code code} a client branches on —
 * the vocabulary the runners page and the workspaces SPA read, spelled once.
 */
public final class RunnerRefusals {

  /**
   * A regular workspace was asked for on the direct path (qits-774): it runs on a runner, and only
   * admin and editor workspaces are DIRECT.
   */
  public static final String DIRECT_PLACEMENT_REFUSED = "DIRECT_PLACEMENT_REFUSED";

  /** The row's runner is offline beyond the reconnect grace: nothing can reach its container. */
  public static final String RUNNER_UNAVAILABLE = "RUNNER_UNAVAILABLE";

  /** The row's runner did not answer a routed verb within its deadline; the row is unchanged. */
  public static final String RUNNER_TIMEOUT = "RUNNER_TIMEOUT";

  private RunnerRefusals() {}

  /** 400 {@link #DIRECT_PLACEMENT_REFUSED}. */
  public static DomainException directPlacementRefused() {
    return new DomainException(
        400,
        DIRECT_PLACEMENT_REFUSED,
        "Regular workspaces run on a workspace runner; only admin and editor workspaces use the"
            + " direct path.");
  }

  /** 409 {@link #RUNNER_UNAVAILABLE}, naming the workspace and what was asked. */
  public static ConflictException unavailable(Long rowId, String verb) {
    return new ConflictException(
        RUNNER_UNAVAILABLE,
        "Workspace "
            + rowId
            + " is on a runner that is offline; it cannot "
            + verb
            + " until the runner is back");
  }

  /** 504 {@link #RUNNER_TIMEOUT}: the runner did not answer, and nothing was written. */
  public static DomainException timeout(Long rowId, String verb) {
    return new DomainException(
        504,
        RUNNER_TIMEOUT,
        "The runner holding workspace "
            + rowId
            + " did not answer "
            + verb
            + " in time; the workspace is unchanged");
  }

  /**
   * 409 {@link #RUNNER_UNAVAILABLE}, naming the runner as well: an agent dispatch or a delivery onto
   * a row whose runner is offline past the grace (qits-626). It fails loudly rather than waiting,
   * because the row is sticky to that runner and nothing else can take it.
   *
   * @param runner the runner's name, or its id when the name cannot be read
   */
  public static ConflictException unavailableOn(Long rowId, String runner, String verb) {
    return new ConflictException(
        RUNNER_UNAVAILABLE,
        "Workspace "
            + rowId
            + " is on runner "
            + runner
            + ", which is offline; it cannot "
            + verb
            + " until the runner is back");
  }
}
