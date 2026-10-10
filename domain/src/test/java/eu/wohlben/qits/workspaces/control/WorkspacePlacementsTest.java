package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import org.junit.jupiter.api.Test;

/**
 * The pure placement rule (qits-837, qits-774), proved with no database and no socket: admin is
 * always DIRECT, and a regular workspace is always RUNNER — there is no runner-eligibility
 * arm and no stated-placement arm left to consult.
 */
class WorkspacePlacementsTest {

  @Test
  void adminIsAlwaysDirect() {
    assertEquals(WorkspacePlacement.DIRECT, WorkspacePlacements.forNewRow(true));
  }

  @Test
  void aRegularWorkspaceIsAlwaysRunner() {
    assertEquals(WorkspacePlacement.RUNNER, WorkspacePlacements.forNewRow(false));
  }

  // --- the router's refusal (qits-780) -----------------------------------------------------------

  private static Workspace row(boolean admin) {
    Workspace row = new Workspace();
    row.id = 42L;
    row.placement = WorkspacePlacement.DIRECT;
    row.admin = admin;
    return row;
  }

  @Test
  void theDirectPathAdmitsAdminRows() {
    assertDoesNotThrow(() -> WorkspacePlacements.requireDirectAllowed(row(true)));
  }

  @Test
  void theDirectPathRefusesARegularRow() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> WorkspacePlacements.requireDirectAllowed(row(false)));
    assertEquals("direct placement refused for regular workspace 42", refused.getMessage());
  }
}
