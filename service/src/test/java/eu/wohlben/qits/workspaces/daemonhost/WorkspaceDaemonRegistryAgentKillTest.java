package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.AgentActivityState;
import eu.wohlben.qits.workspaces.control.AgentKills;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qits-951's host half: an agent the OOM killer took is not a turn that finished.
 *
 * <p>The measured failure: the agent was SIGKILLed at the 4 GiB cap, the daemon survived, and the
 * workspace read RUNNING / {@code IDLE} / no {@code runtimeError} — exactly a clean completion. A
 * daemon at capability 7 now sends {@code ENDED} with an {@code OomKilled}/{@code Killed} {@code
 * hookEvent} for it; what this proves is what the host makes of that frame: the rollup stops saying
 * {@code IDLE}, the row says why, and the next turn takes it back.
 *
 * <p>Frames go through {@code onMessage} directly, the editor-state test's idiom — no socket is
 * needed to prove what a frame does. The row writes land on the registry's sink thread, so the
 * assertions on the row wait for them.
 */
@QuarkusTest
class WorkspaceDaemonRegistryAgentKillTest {

  @Inject WorkspaceDaemonRegistry registry;

  @Inject WorkspaceRepository workspaceRepository;

  private final List<Long> rows = new ArrayList<>();

  private long sequence = 1L;

  @AfterEach
  void cleanUp() {
    QuarkusTransaction.requiringNew()
        .run(
            () ->
                rows.forEach(
                    id ->
                        workspaceRepository
                            .findByIdOptional(id)
                            .ifPresent(w -> w.status = WorkspaceStatus.ABANDONED)));
    rows.clear();
  }

  @Test
  void anOomKilledAgentSetsTheRowsErrorAndIsNotReadAsACleanIdle() throws Exception {
    Long row = running(null);
    report(row, "cmd-1", DaemonProtocol.AgentState.IDLE, "SessionStart");
    report(row, "cmd-1", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");
    // The last hook the agent fired before it died — the state the host used to keep for good.
    report(row, "cmd-1", DaemonProtocol.AgentState.IDLE, "Stop");
    assertEquals(Optional.of(AgentActivityState.IDLE), registry.activityFor(row));

    report(
        row, "cmd-1", DaemonProtocol.AgentState.ENDED, WorkspaceDaemonRegistry.OOM_KILLED_EVENT);

    assertEquals(
        Optional.of(AgentActivityState.ENDED),
        registry.activityFor(row),
        "a killed agent's session is over, not idling between turns");
    await(() -> read(row).runtimeError != null);
    Workspace killed = read(row);
    assertTrue(
        killed.runtimeError.startsWith(AgentKills.OOM_KILLED + ": "), killed.runtimeError);
    assertTrue(killed.runtimeError.contains("out-of-memory killer"), killed.runtimeError);
    assertTrue(killed.runtimeError.contains("exit code 137"), killed.runtimeError);
    assertEquals(
        WorkspaceRuntimeStatus.RUNNING,
        killed.runtimeStatus,
        "the container did survive; the row must not claim otherwise");
  }

  @Test
  void aPlainSigkillIsRecordedAsAKillToo() throws Exception {
    Long row = running(null);

    report(row, "cmd-1", DaemonProtocol.AgentState.ENDED, WorkspaceDaemonRegistry.KILLED_EVENT);

    await(() -> read(row).runtimeError != null);
    assertTrue(
        read(row).runtimeError.startsWith(AgentKills.KILLED + ": "), read(row).runtimeError);
    assertTrue(read(row).runtimeError.contains("SIGKILL"), read(row).runtimeError);
  }

  @Test
  void aCleanSessionEndRecordsNothing() throws Exception {
    Long clean = running(null);
    Long sentinel = running(null);

    report(clean, "cmd-1", DaemonProtocol.AgentState.ENDED, "SessionEnd");
    // The sink thread is single and ordered: once the sentinel's kill is on its row, anything the
    // clean end could have scheduled ahead of it has run.
    report(
        sentinel, "cmd-2", DaemonProtocol.AgentState.ENDED, WorkspaceDaemonRegistry.KILLED_EVENT);
    await(() -> read(sentinel).runtimeError != null);

    assertNull(read(clean).runtimeError);
    assertEquals(Optional.of(AgentActivityState.ENDED), registry.activityFor(clean));
  }

  @Test
  void theNextTurnTakesTheKillBack() throws Exception {
    Long row = running(null);
    report(
        row, "cmd-1", DaemonProtocol.AgentState.ENDED, WorkspaceDaemonRegistry.OOM_KILLED_EVENT);
    await(() -> read(row).runtimeError != null);

    // A relaunched agent's first prompt: the row has a working agent in it again.
    report(row, "cmd-2", DaemonProtocol.AgentState.IDLE, "SessionStart");
    report(row, "cmd-2", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");

    await(() -> read(row).runtimeError == null);
    assertNull(read(row).runtimeError);
    assertEquals(Optional.of(AgentActivityState.BUSY), registry.activityFor(row));
  }

  @Test
  void aTurnLeavesAnErrorThatIsNotAKillAlone() throws Exception {
    Long row = running("EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN 'localhost' is not a public domain");
    Long sentinel = running(null);

    report(row, "cmd-1", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit");
    report(
        sentinel, "cmd-2", DaemonProtocol.AgentState.ENDED, WorkspaceDaemonRegistry.KILLED_EVENT);
    await(() -> read(sentinel).runtimeError != null);

    assertEquals(
        "EDGE_PLANE_UNCONFIGURED: QITS_DOMAIN 'localhost' is not a public domain",
        read(row).runtimeError);
  }

  @Test
  void theDaemonsOwnSentenceWinsWhenItSentOne() {
    // What the host writes once the pinned protocol carries AgentActivity.message(): the daemon's
    // sentence, which names the memory cap the host cannot know.
    assertEquals(
        "AGENT_OOM_KILLED: the claude agent was killed by the out-of-memory killer (exit code 137,"
            + " memory cap 4 GiB)",
        AgentKills.describe(
            true,
            "cmd-1",
            "the claude agent was killed by the out-of-memory killer (exit code 137, memory cap 4"
                + " GiB)"));
    assertTrue(AgentKills.isAgentKill(AgentKills.describe(false, "cmd-1", null)));
    assertTrue(!AgentKills.isAgentKill("pull refused"));
    assertTrue(!AgentKills.isAgentKill(null));
  }

  /**
   * One agent-activity frame for {@code row}, with the fields these cases never vary. The command
   * id is made the row's own: the registry keys activity by command id across every workspace, and
   * the other suites in this application say {@code cmd-1} too.
   */
  private void report(Long row, String commandId, String state, String hookEvent) {
    registry.onMessage(
        row,
        null,
        new AgentActivity(
            commandId + "-" + row, null, state, hookEvent, null, null, sequence++));
  }

  /**
   * An ACTIVE DIRECT row whose container is up, carrying {@code runtimeError} — an admin one, since
   * the direct path is admin and editor only (qits-780).
   */
  private Long running(String runtimeError) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  String label = "k" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.workspaceId = label;
                  workspace.repositoryId = "repo-" + label;
                  workspace.branch = label;
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.placement = WorkspacePlacement.DIRECT;
                  workspace.admin = true;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                  workspace.runtimeError = runtimeError;
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  return workspace.id;
                });
    rows.add(id);
    return id;
  }

  private Workspace read(Long rowId) {
    return QuarkusTransaction.requiringNew().call(() -> workspaceRepository.findById(rowId));
  }

  /** Spin until {@code condition} holds or a 5s deadline passes. */
  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        return; // let the caller's assertion report the failure
      }
      TimeUnit.MILLISECONDS.sleep(25);
    }
  }
}
