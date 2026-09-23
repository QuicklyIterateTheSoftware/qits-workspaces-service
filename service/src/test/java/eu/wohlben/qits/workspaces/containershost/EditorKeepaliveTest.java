package eu.wohlben.qits.workspaces.containershost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.ContainerRuntime;
import eu.wohlben.qits.workspaces.control.FakeContainerRuntime;
import eu.wohlben.qits.workspaces.control.EditorWorkspace;
import eu.wohlben.qits.workspaces.control.SharedTuningProfile;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The keepalive wired up: a report about a workspace becomes one {@code touch} at the container the
 * workspace's row names, and repeated reports inside the window become no further ones.
 *
 * <p>It runs behind a profile because <b>the shipped configuration is off</b> — {@code
 * qits.editor.idle-stop-after} is blank, nothing is idle-stopped, and a keepalive would be a request
 * nothing acts on. Turning it on is what makes the wiring visible at all, and the profile is also
 * the assertion that the switch is what gates it.
 *
 * <p>The switch lives in {@link SharedTuningProfile} rather than in a profile of this class's own,
 * because a profile is an application and not an overlay: a nested {@code IdleStopOn} was one more
 * Quarkus restart, and its {@code qits.editor.touch-interval=PT30S} only restated the shipped
 * default. Thirty minutes is longer than any class sharing that profile runs, so turning the policy
 * on costs its co-tenants nothing — see that class for the rule that keeps the count at one.
 */
@QuarkusTest
@TestProfile(SharedTuningProfile.class)
public class EditorKeepaliveTest {

  @Inject EditorKeepalive keepalive;
  @Inject ContainerRuntime containers;
  @Inject WorkspaceService workspaceService;

  @Test
  void aReportTouchesTheWorkspacesOwnContainerOncePerWindow() throws Exception {
    // The editor's row, which is what the keepalive is about: one row for the platform, so the
    // container it names is the constant `qits-ws-editor-editor` rather than something derived from
    // whoever opened it.
    Long rowId = workspaceService.createEditorWorkspace().id;

    FakeContainerRuntime runtime = (FakeContainerRuntime) containers;
    String container =
        runtime.containerName(EditorWorkspace.WORKSPACE_ID, EditorWorkspace.REPOSITORY_ID);
    runtime.clearTouches();

    keepalive.touched(rowId);
    assertTrue(keepalive.awaitQuiet(10_000));
    assertEquals(1, runtime.touchCount(container), "the row names the container, not the caller");

    // Everything inside the window is silence. An editor session is a stream of frames, so this is
    // the difference between one request per half-minute and one per keystroke.
    for (int i = 0; i < 50; i++) {
      keepalive.touched(rowId);
    }
    assertTrue(keepalive.awaitQuiet(10_000));
    assertEquals(1, runtime.touchCount(container), "one touch per interval, not one per report");
  }

  @Test
  void aWorkspaceThatIsNotThereIsNotTouched() throws Exception {
    // The row is what names the container, so a report about a workspace that has since resolved
    // reaches nothing — silently, because a keepalive is best-effort by contract.
    FakeContainerRuntime runtime = (FakeContainerRuntime) containers;
    runtime.clearTouches();

    keepalive.touched(-42L);
    assertTrue(keepalive.awaitQuiet(10_000));

    assertEquals(0, runtime.touchCount("anything"));
  }
}
