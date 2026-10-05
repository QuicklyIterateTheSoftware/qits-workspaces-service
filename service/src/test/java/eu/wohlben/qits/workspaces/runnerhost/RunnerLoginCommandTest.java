package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.dto.WorkspaceRunnerDto;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The login command's one shape (qits-859), the guard on what a runner reported: the volume is
 * spliced into a line a person pastes into a shell, so only a plain docker volume name is — and
 * the gate added to stop the command being served while the runner is still pulling the image
 * (qits-859, fixing the race seen live 2026-10-05). Plain JUnit; the rendering through the runner
 * DTO, null before the volume is known, is {@code WorkspaceRunnerControllerTest}'s.
 */
class RunnerLoginCommandTest {

  private static final String IMAGE = "registry.qits.example.test/qits/workspace:2026.1003.185745";

  private static final String VOLUME = "qits-workspaces-runner-dot-claude-3f2b8f0e";

  private static final Instant SINCE = Instant.parse("2026-10-05T06:00:00Z");

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

  // --- proven: whether the login probe shows the current image is on the node ------------------

  @Test
  void noLoginAtAllIsNoProof() {
    assertFalse(RunnerLoginCommand.proven(null, SINCE, null));
  }

  @Test
  void bothHarnessesUnknownIsNoProofBecauseTheProbeCouldNotRun() {
    WorkspaceRunnerDto.Login login = new WorkspaceRunnerDto.Login("UNKNOWN", "UNKNOWN", SINCE);
    assertFalse(RunnerLoginCommand.proven(login, SINCE, null));
  }

  @Test
  void oneHarnessAnsweredAtOrAfterTheSessionStartIsProof() {
    WorkspaceRunnerDto.Login login = new WorkspaceRunnerDto.Login("ABSENT", "UNKNOWN", SINCE);
    assertTrue(RunnerLoginCommand.proven(login, SINCE, null));
    assertTrue(RunnerLoginCommand.proven(login, SINCE.minusSeconds(60), null));
  }

  @Test
  void aCheckBeforeTheSessionStartedIsNoProof() {
    WorkspaceRunnerDto.Login login = new WorkspaceRunnerDto.Login("PRESENT", "ABSENT", SINCE);
    assertFalse(RunnerLoginCommand.proven(login, SINCE.plusSeconds(1), null));
  }

  @Test
  void registrationIsTheFallbackSessionStartWhenThereIsNoCurrentConnection() {
    WorkspaceRunnerDto.Login login = new WorkspaceRunnerDto.Login("PRESENT", "UNKNOWN", SINCE);
    assertTrue(RunnerLoginCommand.proven(login, null, SINCE.minusSeconds(1)));
    assertFalse(RunnerLoginCommand.proven(login, null, SINCE.plusSeconds(1)));
    assertFalse(RunnerLoginCommand.proven(login, null, null));
  }

  // --- the composed command and the pending flag, volume + login + timestamps together -----------

  @Test
  void theCommandIsNullUntilProvenEvenWithAKnownVolume() {
    RunnerLoginCommand command = withAddresses(IMAGE);
    assertEquals(null, command.claude(VOLUME, null, SINCE, null));
    assertTrue(command.pending(VOLUME, null, SINCE, null));

    WorkspaceRunnerDto.Login unknown = new WorkspaceRunnerDto.Login("UNKNOWN", "UNKNOWN", SINCE);
    assertEquals(null, command.claude(VOLUME, unknown, SINCE, null));
    assertTrue(command.pending(VOLUME, unknown, SINCE, null));
  }

  @Test
  void theCommandAppearsOnceAHarnessAnsweredAtOrAfterTheSession() {
    RunnerLoginCommand command = withAddresses(IMAGE);
    WorkspaceRunnerDto.Login login = new WorkspaceRunnerDto.Login("ABSENT", "UNKNOWN", SINCE);
    assertEquals(
        "docker run --rm -it -v " + VOLUME + ":/claude-home -e HOME=/claude-home"
            + " -e CLAUDE_CONFIG_DIR=/claude-home " + IMAGE + " claude",
        command.claude(VOLUME, login, SINCE, null));
    assertEquals(
        "docker run --rm -it -v " + VOLUME + ":/claude-home -e HOME=/claude-home"
            + " -e CLAUDE_CONFIG_DIR=/claude-home " + IMAGE + " kimi login",
        command.kimi(VOLUME, login, SINCE, null));
    assertFalse(command.pending(VOLUME, login, SINCE, null));
  }

  @Test
  void noVolumeIsNotPendingThereIsSimplyNothingToWaitFor() {
    RunnerLoginCommand command = withAddresses(IMAGE);
    assertFalse(command.pending(null, null, SINCE, null));
  }

  private static RunnerLoginCommand withAddresses(String image) {
    RunnerLoginCommand command = new RunnerLoginCommand();
    command.addresses =
        new WorkspaceRunnerAddresses() {
          @Override
          public String workspaceImage() {
            return image;
          }
        };
    return command;
  }
}
