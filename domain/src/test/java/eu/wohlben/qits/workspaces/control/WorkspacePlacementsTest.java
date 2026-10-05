package eu.wohlben.qits.workspaces.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The pure placement rule (qits-837, epic qits-626), proved with no database and no socket: editor
 * is always DIRECT, admin is always DIRECT even when RUNNER is stated, a stated placement is used
 * as stated for a regular workspace, and the no-eligible-runner / eligible-runner default for a
 * regular workspace that states nothing.
 */
class WorkspacePlacementsTest {

  @Test
  void editorIsAlwaysDirect() {
    assertEquals(
        WorkspacePlacement.DIRECT,
        WorkspacePlacements.forNewRow(
            false, true, Optional.empty(), true));
  }

  @Test
  void adminIsAlwaysDirectEvenWhenRunnerIsStated() {
    assertEquals(
        WorkspacePlacement.DIRECT,
        WorkspacePlacements.forNewRow(
            true, false, Optional.of(WorkspacePlacement.RUNNER), true));
  }

  @Test
  void adminIsDirectWithNothingStatedToo() {
    assertEquals(
        WorkspacePlacement.DIRECT,
        WorkspacePlacements.forNewRow(true, false, Optional.empty(), true));
  }

  @Test
  void aStatedDirectStaysDirect() {
    assertEquals(
        WorkspacePlacement.DIRECT,
        WorkspacePlacements.forNewRow(
            false, false, Optional.of(WorkspacePlacement.DIRECT), true));
  }

  @Test
  void aStatedRunnerStaysRunnerEvenWithNoEligibleRunner() {
    assertEquals(
        WorkspacePlacement.RUNNER,
        WorkspacePlacements.forNewRow(
            false, false, Optional.of(WorkspacePlacement.RUNNER), false));
  }

  @Test
  void noEligibleRunnerAndNothingStatedIsDirect() {
    assertEquals(
        WorkspacePlacement.DIRECT,
        WorkspacePlacements.forNewRow(false, false, Optional.empty(), false));
  }

  @Test
  void anEligibleRunnerAndNothingStatedIsRunner() {
    assertEquals(
        WorkspacePlacement.RUNNER,
        WorkspacePlacements.forNewRow(false, false, Optional.empty(), true));
  }
}
