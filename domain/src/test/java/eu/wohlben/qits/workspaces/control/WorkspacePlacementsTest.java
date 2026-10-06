package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import org.junit.jupiter.api.Test;

/**
 * The pure placement rule (qits-837, qits-774), proved with no database and no socket: editor and
 * admin are always DIRECT, and a regular workspace is always RUNNER — there is no runner-eligibility
 * arm and no stated-placement arm left to consult.
 */
class WorkspacePlacementsTest {

  @Test
  void editorIsAlwaysDirect() {
    assertEquals(WorkspacePlacement.DIRECT, WorkspacePlacements.forNewRow(false, true));
  }

  @Test
  void adminIsAlwaysDirect() {
    assertEquals(WorkspacePlacement.DIRECT, WorkspacePlacements.forNewRow(true, false));
  }

  @Test
  void aRegularWorkspaceIsAlwaysRunner() {
    assertEquals(WorkspacePlacement.RUNNER, WorkspacePlacements.forNewRow(false, false));
  }
}
