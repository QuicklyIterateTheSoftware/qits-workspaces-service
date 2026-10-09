package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The command id a launch's answer names (qits-895) — {@code {"command": {"id": …}}}, the daemon's
 * {@code AgentJson.launched} — and the bodies that name none, each of which is still an accepted
 * launch and must not throw.
 */
class DaemonAgentClientLaunchAnswerTest {

  @Test
  void theCommandIdIsReadOffTheLaunchedCommand() {
    assertEquals(
        "cmd-7",
        DaemonAgentClient.launchedCommandId(
            "{\"command\":{\"id\":\"cmd-7\",\"status\":\"RUNNING\",\"kind\":\"CHAT\"}}"));
  }

  @Test
  void aBodyThatNamesNoCommandAnswersNull() {
    assertNull(DaemonAgentClient.launchedCommandId("{\"command\":{\"status\":\"RUNNING\"}}"));
    assertNull(DaemonAgentClient.launchedCommandId("{\"command\":{\"id\":\" \"}}"));
    assertNull(DaemonAgentClient.launchedCommandId("{}"));
    assertNull(DaemonAgentClient.launchedCommandId("not json"));
    assertNull(DaemonAgentClient.launchedCommandId(""));
  }
}
