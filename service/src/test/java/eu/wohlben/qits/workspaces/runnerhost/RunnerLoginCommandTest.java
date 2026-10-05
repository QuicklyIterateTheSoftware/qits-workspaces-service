package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The login command's one shape (qits-859), and the guard on what a runner reported: the volume is
 * spliced into a line a person pastes into a shell, so only a plain docker volume name is. Plain
 * JUnit; the rendering through the runner DTO, null before the volume is known, is {@code
 * WorkspaceRunnerControllerTest}'s.
 */
class RunnerLoginCommandTest {

  private static final String IMAGE = "registry.qits.example.test/qits/workspace:2026.1003.185745";

  private static final String VOLUME = "qits-workspaces-runner-dot-claude-3f2b8f0e";

  @Test
  void theCommandRendersExactlyForAVolumeAndAnImage() {
    assertEquals(
        "docker run --rm -it -v qits-workspaces-runner-dot-claude-3f2b8f0e:/claude-home"
            + " -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home "
            + IMAGE
            + " claude",
        RunnerLoginCommand.command(VOLUME, IMAGE, "claude"));
    assertEquals(
        "docker run --rm -it -v qits-workspaces-runner-dot-claude-3f2b8f0e:/claude-home"
            + " -e HOME=/claude-home -e CLAUDE_CONFIG_DIR=/claude-home "
            + IMAGE
            + " kimi login",
        RunnerLoginCommand.command(VOLUME, IMAGE, "kimi login"));
  }

  @Test
  void onlyAPlainDockerVolumeNameIsSplicedIntoTheLine() {
    assertTrue(RunnerLoginCommand.splicable(VOLUME));
    assertFalse(RunnerLoginCommand.splicable(null));
    assertFalse(RunnerLoginCommand.splicable(""));
    assertFalse(RunnerLoginCommand.splicable("vol; rm -rf /"));
    assertFalse(RunnerLoginCommand.splicable("$(whoami)"));
    assertFalse(RunnerLoginCommand.splicable("-v /:/host"));
  }
}
