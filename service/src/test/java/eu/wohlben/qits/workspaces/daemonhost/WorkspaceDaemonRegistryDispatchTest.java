package eu.wohlben.qits.workspaces.daemonhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.workspaces.control.WorkspaceBootstrapDriver;
import eu.wohlben.qits.workspacedaemon.protocol.BootstrapOutcome;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The registry's sink dispatch discipline (D1's deadlock leg). websockets-next processes one
 * inbound frame per connection at a time, so a subscribed sink that blocks — the bootstrap
 * recorder's DB write — must never run on the {@code onMessage} caller: it would park the whole
 * pipeline, including the {@code ConfigView} reply a config read on another thread is awaiting.
 * Measured live (with the since-removed workspace services' sink), that starved every such read to
 * timeout. These cases pin the decoupling at the registry seam, with no socket involved.
 */
@QuarkusTest
class WorkspaceDaemonRegistryDispatchTest {

  @Inject WorkspaceDaemonRegistry registry;

  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void unblock() {
    release.countDown(); // never leave the shared dispatch thread parked for the next test
  }

  @Test
  void aBlockedOutcomeSinkDoesNotBlockTheMessagePipeline() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    registry.subscribe(
        (WorkspaceBootstrapDriver.OutcomeSink)
            (repoId, workspaceId, rowId, stepName, outcome, exitCode) -> {
              if ("dispatch-probe".equals(stepName)) {
                entered.countDown();
                try {
                  release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            });

    // No client registered for this workspace id — the fan-out tolerates that (repoId null).
    registry.onMessage(771000L, null, new BootstrapOutcome("w", "dispatch-probe", "SUCCEEDED", 0));

    // The dispatch hopped threads: the sink is (or will be) parked, yet onMessage came back —
    // reaching this line before `release` opens IS the assertion.
    assertTrue(entered.await(10, TimeUnit.SECONDS), "the sink never received the outcome");
    release.countDown();
  }

  @Test
  void anUnawaitedBootstrapOutcomeReachesTheSubscribedRecorder() throws Exception {
    // The persistent recorder gets every outcome frame — no await, no PendingBootstrap sink. This
    // is the registry half of the missing workspace_bootstrap_run rows (the daemon's own HTTP run).
    LinkedBlockingQueue<Map.Entry<Long, String>> seen = new LinkedBlockingQueue<>();
    registry.subscribe(
        (WorkspaceBootstrapDriver.OutcomeSink)
            (repoId, workspaceId, rowId, stepName, outcome, exitCode) ->
                seen.add(Map.entry(rowId, stepName + ":" + outcome)));

    registry.onMessage(771001L, null, new BootstrapOutcome("w", "own-step", "SUCCEEDED", 0));

    Map.Entry<Long, String> received = seen.poll(10, TimeUnit.SECONDS);
    assertTrue(received != null, "the recorder never saw the unawaited outcome");
    assertEquals(771001L, received.getKey());
    assertEquals("own-step:SUCCEEDED", received.getValue());
  }
}
