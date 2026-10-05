package eu.wohlben.qits.workspaces.error;

/**
 * The refusals of a RUNNER-placed workspace's verbs (epic qits-624), each with the {@code code} a
 * client branches on — the vocabulary the runners page and the workspaces SPA read, spelled once.
 */
public final class RunnerRefusals {

  /** A RUNNER workspace was asked for while no runner could ever take it. */
  public static final String NO_RUNNER = "NO_RUNNER";

  /** The row's runner is offline beyond the reconnect grace: nothing can reach its container. */
  public static final String RUNNER_UNAVAILABLE = "RUNNER_UNAVAILABLE";

  /** The row's runner did not answer a routed verb within its deadline; the row is unchanged. */
  public static final String RUNNER_TIMEOUT = "RUNNER_TIMEOUT";

  /** An agent dispatch onto a RUNNER row, which dispatch cannot wait for yet (qits-626). */
  public static final String RUNNER_DISPATCH_UNSUPPORTED = "RUNNER_DISPATCH_UNSUPPORTED";

  private RunnerRefusals() {}

  /** 409 {@link #NO_RUNNER}. */
  public static ConflictException noRunner() {
    return new ConflictException(
        NO_RUNNER,
        "No workspace runner is registered, in service and with slots, so a RUNNER workspace would"
            + " wait forever; create and register one first, or place the workspace DIRECT");
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

  /** 409 {@link #RUNNER_DISPATCH_UNSUPPORTED}. */
  public static ConflictException dispatchUnsupported(Long rowId) {
    return new ConflictException(
        RUNNER_DISPATCH_UNSUPPORTED,
        "Workspace "
            + rowId
            + " runs on a workspace runner, and an agent dispatch cannot wait for a runner yet");
  }
}
