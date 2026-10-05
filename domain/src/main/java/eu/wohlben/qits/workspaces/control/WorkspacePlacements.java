package eu.wohlben.qits.workspaces.control;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import java.util.Optional;

/**
 * The one rule that decides a <b>new</b> row's {@link WorkspacePlacement} (qits-837, epic
 * qits-626): RUNNER becomes the default placement for a regular workspace once an eligible runner
 * exists, and admin and editor stay DIRECT regardless. Pure, so the table is provable with no
 * database and no socket — {@link WorkspaceService#recordWorkspace} is the single row-writer that
 * calls it.
 *
 * <p>Fixed at create and never revisited: nothing here reads an existing row, and nothing downstream
 * is expected to call this twice for the same workspace.
 */
public final class WorkspacePlacements {

  private WorkspacePlacements() {}

  /**
   * @param admin the admin posture (holds the host's docker socket) — always DIRECT, whatever
   *     {@code stated} says. A RUNNER explicitly stated for an admin workspace is refused before
   *     this is ever asked ({@code WorkspaceService.refuseUnplaceable}); this method answers DIRECT
   *     for that combination too, so the rule reads the same whether or not the caller refused first.
   * @param editor the shared editor workspace — always DIRECT. The editor is written by its own
   *     door, which never calls this with {@code stated} present.
   * @param stated the placement the request named, used as stated for an ordinary (non-admin,
   *     non-editor) workspace: an explicit RUNNER with no eligible runner is the caller's problem to
   *     refuse, not this method's.
   * @param runnerEligible whether {@code WorkspaceRunnerRepository.existsEligible()} answered true —
   *     consulted only when nothing is stated and the workspace is neither admin nor editor.
   * @return the placement a new row is written with
   */
  public static WorkspacePlacement forNewRow(
      boolean admin, boolean editor, Optional<WorkspacePlacement> stated, boolean runnerEligible) {
    if (editor || admin) {
      return WorkspacePlacement.DIRECT;
    }
    if (stated != null && stated.isPresent()) {
      return stated.get();
    }
    return runnerEligible ? WorkspacePlacement.RUNNER : WorkspacePlacement.DIRECT;
  }
}
