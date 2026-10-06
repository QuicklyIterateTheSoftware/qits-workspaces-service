package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;

/**
 * The one rule that decides a <b>new</b> row's {@link WorkspacePlacement} (qits-837, qits-774): a
 * regular workspace always runs on a workspace runner, and only admin and editor workspaces use the
 * direct path. Whether a runner is registered, connected or eligible does not enter into it — a
 * RUNNER row with nothing to take it simply waits QUEUED. Pure, so the table is provable with no
 * database and no socket — {@link WorkspaceService#recordWorkspace} is the single row-writer that
 * calls it.
 *
 * <p>Fixed at create and never revisited: nothing here reads an existing row, and nothing downstream
 * is expected to call this twice for the same workspace.
 */
public final class WorkspacePlacements {

  private WorkspacePlacements() {}

  /**
   * @param admin the admin posture (holds the host's docker socket) — always DIRECT
   * @param editor the shared editor workspace — always DIRECT
   * @return the placement a new row is written with: DIRECT for admin or editor, RUNNER otherwise
   */
  public static WorkspacePlacement forNewRow(boolean admin, boolean editor) {
    return admin || editor ? WorkspacePlacement.DIRECT : WorkspacePlacement.RUNNER;
  }
}
