package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * qits-1601: an agent waiting on background work it started is working, not stalled.
 *
 * <p>The measured failure: while an agent waited on its own subagent, the daemon sent {@code Stop}
 * (IDLE) and a minute later an {@code idle_prompt} (WAITING), the agents door showed exactly that,
 * and the delivery desk prompted a busy agent as stalled. A daemon at capability 12 says {@code
 * backgroundWork} on every frame; what this proves is the two readings the registry makes of it —
 * {@code reportedActivityOf} reads such an agent BUSY, {@code activityOf} (every internal gate's)
 * keeps the raw state — and that the daemon's grace expiry ({@code awaitingInput=true}) and an
 * older daemon (null) both fall back to the raw state.
 *
 * <p>Frames go through {@code onMessage} directly, the agent-kill test's idiom. The workspace row
 * id names no row: nothing here reads one, and the sinks that would write one log and drop.
 */
@QuarkusTest
class WorkspaceDaemonRegistryBackgroundWorkTest {

  @Inject WorkspaceDaemonRegistry registry;

  private long sequence = 1L;

  @Test
  void anAgentIdleOrWaitingWithBackgroundWorkInFlightReadsBusyUntilTheGraceExpires() {
    long row = -System.nanoTime();
    String agentId = UUID.randomUUID().toString();

    report(row, agentId, DaemonProtocol.AgentState.IDLE, "Stop", false, true);
    assertEquals(Optional.of(AgentActivityState.BUSY), registry.reportedActivityOf(agentId));
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.activityOf(agentId));

    report(row, agentId, DaemonProtocol.AgentState.WAITING, "Notification", null, true);
    assertEquals(Optional.of(AgentActivityState.BUSY), registry.reportedActivityOf(agentId));
    assertEquals(Optional.of(AgentActivityState.WAITING), registry.activityOf(agentId));

    // The daemon's background-work grace ran out: a genuinely abandoned agent reads as waiting.
    report(row, agentId, DaemonProtocol.AgentState.IDLE, "Notification", true, true);
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.reportedActivityOf(agentId));
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.activityOf(agentId));
  }

  @Test
  void anOlderDaemonsFrameWithoutTheFlagReadsAsItsRawState() {
    long row = -System.nanoTime();
    String agentId = UUID.randomUUID().toString();

    report(row, agentId, DaemonProtocol.AgentState.IDLE, "Stop", false, null);
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.reportedActivityOf(agentId));

    report(row, agentId, DaemonProtocol.AgentState.WAITING, "Notification", null, null);
    assertEquals(Optional.of(AgentActivityState.WAITING), registry.reportedActivityOf(agentId));
  }

  @Test
  void nothingInFlightReadsAsTheRawState() {
    long row = -System.nanoTime();
    String agentId = UUID.randomUUID().toString();

    report(row, agentId, DaemonProtocol.AgentState.IDLE, "Stop", false, false);
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.reportedActivityOf(agentId));

    report(row, agentId, DaemonProtocol.AgentState.WAITING, "Notification", null, false);
    assertEquals(Optional.of(AgentActivityState.WAITING), registry.reportedActivityOf(agentId));

    report(row, agentId, DaemonProtocol.AgentState.ENDED, "SessionEnd", null, true);
    assertEquals(
        Optional.of(AgentActivityState.ENDED),
        registry.reportedActivityOf(agentId),
        "only an idle or waiting harness is shown busy");
  }

  @Test
  void anAgentWithNoFrameReadsAsUnknown() {
    String agentId = UUID.randomUUID().toString();
    assertEquals(Optional.empty(), registry.reportedActivityOf(agentId));
    assertEquals(Optional.empty(), registry.reportedActivityOf(null));
  }

  /** One agent-activity frame naming {@code agentId}, its command id made the agent's own. */
  private void report(
      long row,
      String agentId,
      String state,
      String hookEvent,
      Boolean awaitingInput,
      Boolean backgroundWork) {
    registry.onMessage(
        row,
        null,
        new AgentActivity(
            "cmd-" + agentId,
            null,
            state,
            hookEvent,
            null,
            null,
            sequence++,
            null,
            null,
            awaitingInput,
            agentId,
            backgroundWork));
  }
}
