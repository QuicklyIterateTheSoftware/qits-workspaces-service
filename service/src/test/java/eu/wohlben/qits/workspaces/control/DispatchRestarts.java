package eu.wohlben.qits.workspaces.control;

/**
 * Reaches {@link DispatchService}'s package-private restart hooks from a test in another package.
 * A test may not call {@code shutdown()} on the shared bean — it would stop the executor every later
 * test in the run dispatches through — so a restart is stood in for by forgetting what the process
 * holds in memory and running the boot's drain again.
 */
public final class DispatchRestarts {

  private DispatchRestarts() {}

  /** What a restart leaves of {@code dispatches}: the table, and nothing in memory. */
  public static void restart(DispatchService dispatches) {
    dispatches.forgetInMemory();
    dispatches.drainHeldLaunches();
  }

  /** Whether a launch is stored for {@code rowId}. */
  public static boolean isHeld(DispatchService dispatches, Long rowId) {
    return dispatches.isHeld(rowId);
  }
}
