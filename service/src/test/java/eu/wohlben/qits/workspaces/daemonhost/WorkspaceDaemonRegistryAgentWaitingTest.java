package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.workspaces.control.AgentLaunched;
import eu.wohlben.qits.workspaces.entity.Workspace;
import eu.wohlben.qits.workspaces.entity.WorkspacePlacement;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.entity.WorkspaceStatus;
import eu.wohlben.qits.workspaces.persistence.WorkspaceRepository;
import eu.wohlben.qits.workspaces.wiring.HttpAgentWaitingReporter;
import eu.wohlben.qits.workspacedaemon.protocol.AgentActivity;
import eu.wohlben.qits.workspacedaemon.protocol.DaemonProtocol;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * qits-895's host half: the dispatched agent's {@code awaitingInput} reaches qits-projects, once
 * per flip, and nobody else's does.
 *
 * <p>Frames go through {@code onMessage} directly, {@link WorkspaceDaemonRegistryAgentKillTest}'s
 * idiom, and qits-projects is a recording {@link HttpAgentWaitingReporter} installed over the
 * shipped one — by its class, which is what {@code QuarkusMock} can replace. The relay runs on the registry's single sink thread, so "nothing more was sent" is proved by
 * a <b>sentinel</b>: a relay for a second workspace, queued after the frames under test, has landed
 * — so everything queued before it has run.
 */
@QuarkusTest
class WorkspaceDaemonRegistryAgentWaitingTest {

  @Inject WorkspaceDaemonRegistry registry;

  @Inject WorkspaceRepository workspaceRepository;

  private final List<Long> rows = new ArrayList<>();

  private Recorder reporter;

  private long sequence = 1L;

  /** One relayed report. */
  record Report(String workId, boolean waiting, String cause, String sessionId, long at) {}

  /** qits-projects' door, recorded; {@link #failures} makes the next calls throw. */
  static class Recorder extends HttpAgentWaitingReporter {
    final List<Report> reports = new CopyOnWriteArrayList<>();
    final AtomicInteger failures = new AtomicInteger();

    @Override
    public void report(String workId, boolean waiting, String cause, String sessionId, long at) {
      if (failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
        throw new IllegalStateException("qits-projects answered 500");
      }
      reports.add(new Report(workId, waiting, cause, sessionId, at));
    }

    List<Report> about(String workId) {
      return reports.stream().filter(report -> report.workId().equals(workId)).toList();
    }
  }

  @BeforeEach
  void installReporter() {
    reporter = new Recorder();
    QuarkusMock.installMockForType(reporter, HttpAgentWaitingReporter.class);
  }

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
  void onlyAFlipIsRelayed() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-d", work);

    report(row, "cmd-d", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", "s-1", Boolean.FALSE);
    report(row, "cmd-d", DaemonProtocol.AgentState.BUSY, "PreToolUse", "s-1", Boolean.FALSE);
    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", Boolean.TRUE);
    // A reconnect replays the session's state: qits-projects already has it.
    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", Boolean.TRUE);
    drain();

    List<Report> sent = reporter.about(work);
    assertEquals(2, sent.size(), sent.toString());
    assertEquals(false, sent.get(0).waiting());
    assertEquals("UserPromptSubmit", sent.get(0).cause());
    assertEquals(true, sent.get(1).waiting());
    assertEquals("Stop", sent.get(1).cause());
    assertEquals("s-1", sent.get(1).sessionId());
  }

  @Test
  void aKilledAgentIsWaitingAndTheKillIsTheCause() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-d", work);

    report(
        row,
        "cmd-d",
        DaemonProtocol.AgentState.ENDED,
        WorkspaceDaemonRegistry.OOM_KILLED_EVENT,
        "s-1",
        Boolean.TRUE);
    drain();

    assertEquals(
        List.of(WorkspaceDaemonRegistry.OOM_KILLED_EVENT),
        reporter.about(work).stream().map(Report::cause).toList());
  }

  @Test
  void aPersonsOtherSessionIsIgnored() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-d", work);

    report(row, "cmd-chat", DaemonProtocol.AgentState.IDLE, "Stop", "s-2", Boolean.TRUE);
    drain();

    assertEquals(List.of(), reporter.about(work));
  }

  @Test
  void anUnknownWaitingStateIsNeverRelayed() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-d", work);

    // What every frame from a daemon older than capability 8 looks like.
    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", null);
    drain();

    assertEquals(List.of(), reporter.about(work));
  }

  @Test
  void aRowBoundToNoWorkItemRelaysNothing() throws Exception {
    Long row = dispatched("cmd-d", null);

    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", Boolean.TRUE);
    drain();

    assertEquals(
        List.of(),
        reporter.reports.stream().filter(r -> !r.workId().startsWith("sentinel-")).toList());
  }

  @Test
  void aNewLaunchFollowsTheNewCommandAndForgetsWhatWasSent() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-old", work);
    report(row, "cmd-old", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", Boolean.TRUE);

    registry.onAgentLaunched(new AgentLaunched(row, "cmd-new-" + row, work));
    // The old agent is no longer the dispatched one.
    report(row, "cmd-old", DaemonProtocol.AgentState.BUSY, "UserPromptSubmit", "s-1", Boolean.FALSE);
    // The new agent's first turn ending is news, though the old one's last turn ended the same way.
    report(row, "cmd-new", DaemonProtocol.AgentState.IDLE, "Stop", "s-2", Boolean.TRUE);
    drain();

    List<Report> sent = reporter.about(work);
    assertEquals(2, sent.size(), sent.toString());
    assertEquals("s-1", sent.get(0).sessionId());
    assertEquals("s-2", sent.get(1).sessionId());
    assertEquals(true, sent.get(1).waiting());
  }

  @Test
  void aFailedRelayIsDroppedAndTheNextFrameTriesAgain() throws Exception {
    String work = UUID.randomUUID().toString();
    Long row = dispatched("cmd-d", work);
    reporter.failures.set(1);

    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Stop", "s-1", Boolean.TRUE);
    report(row, "cmd-d", DaemonProtocol.AgentState.IDLE, "Notification", "s-1", Boolean.TRUE);
    drain();

    assertEquals(
        List.of("Notification"), reporter.about(work).stream().map(Report::cause).toList());
  }

  /** One agent-activity frame for {@code row}, carrying {@code awaitingInput}. */
  private void report(
      Long row,
      String commandId,
      String state,
      String hookEvent,
      String sessionId,
      Boolean awaitingInput) {
    registry.onMessage(
        row,
        null,
        new AgentActivity(
            commandId + "-" + row,
            sessionId,
            state,
            hookEvent,
            null,
            null,
            sequence++,
            awaitingInput));
  }

  /**
   * Queue a relay for a fresh workspace and wait for it to land: the sink thread is single and
   * ordered, so everything queued before it has run by then.
   */
  private void drain() throws Exception {
    String sentinel = "sentinel-" + UUID.randomUUID();
    Long row = dispatched("cmd-s", sentinel);
    report(row, "cmd-s", DaemonProtocol.AgentState.IDLE, "Stop", null, Boolean.TRUE);
    await(() -> !reporter.about(sentinel).isEmpty());
    assertEquals(1, reporter.about(sentinel).size(), "the sink never reached the sentinel");
  }

  /**
   * An ACTIVE DIRECT admin row whose container is up, its agent launched as {@code commandId} (made
   * the row's own, as {@link #report} makes it) and bound to {@code workId}.
   */
  private Long dispatched(String commandId, String workId) {
    Long id =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  Workspace workspace = new Workspace();
                  String label = "w" + UUID.randomUUID().toString().substring(0, 8);
                  workspace.workspaceId = label;
                  workspace.repositoryId = "repo-" + label;
                  workspace.branch = label;
                  workspace.status = WorkspaceStatus.ACTIVE;
                  workspace.placement = WorkspacePlacement.DIRECT;
                  workspace.admin = true;
                  workspace.runtimeStatus = WorkspaceRuntimeStatus.RUNNING;
                  workspace.workId = workId;
                  workspaceRepository.persist(workspace);
                  workspaceRepository.flush();
                  workspaceRepository.recordDispatchCommand(
                      workspace.id, commandId + "-" + workspace.id);
                  return workspace.id;
                });
    rows.add(id);
    return id;
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
