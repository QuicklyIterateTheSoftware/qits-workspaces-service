package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.entity.Workspace;
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

  // --- the router's refusal (qits-780) -----------------------------------------------------------

  private static Workspace row(boolean admin, boolean editor) {
    Workspace row = new Workspace();
    row.id = 42L;
    row.placement = WorkspacePlacement.DIRECT;
    row.admin = admin;
    row.editor = editor;
    return row;
  }

  @Test
  void theDirectPathAdmitsAdminAndEditorRows() {
    assertDoesNotThrow(() -> WorkspacePlacements.requireDirectAllowed(row(true, false)));
    assertDoesNotThrow(() -> WorkspacePlacements.requireDirectAllowed(row(false, true)));
  }

  @Test
  void theDirectPathRefusesARegularRow() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspacePlacements.requireDirectAllowed(row(false, false)));
    assertEquals("direct placement refused for regular workspace 42", refused.getMessage());
  }
}
