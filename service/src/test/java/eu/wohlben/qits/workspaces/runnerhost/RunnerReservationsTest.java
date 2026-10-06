package eu.wohlben.qits.workspaces.runnerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.runner.protocol.RunnerMessage;
import eu.wohlben.qits.workspaces.control.WorkspaceAddressPlanes;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspaces.error.ConflictException;
import eu.wohlben.qits.workspaces.error.RunnerRefusals;
import eu.wohlben.qits.workspacesrunner.protocol.Delete;
import eu.wohlben.qits.workspacesrunner.protocol.Deleted;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.LaunchFailed;
import eu.wohlben.qits.workspacesrunner.protocol.Launched;
import eu.wohlben.qits.workspacesrunner.protocol.Mount;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.Take;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspaceSpec;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reserve is the claim (qits-851), and the verbs routed to the owner (qits-853's port, driven by
 * {@link RunnerPlacementDriver}), over a real socket from {@link FakeWorkspacesRunner}s: one take
 * between two runners racing for one row, a sticky row only for its runner, a full or quarantined
 * runner answered nothing, a failed launch freeing its slot, and stop/delete awaited on the
 * runner's reply. The compare-and-swap itself is the domain's {@code RunnerClaimsTest}'s; this is
 * the wire around it.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class RunnerReservationsTest {

  private static final String PIN = WorkspacesRunnerBinary.VERSION;

  @TestHTTPResource(WorkspacesRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject RunnerPlacementDriver placement;

  @Inject WorkspaceService workspaceService;

  @Inject WorkspaceRunners workspaceRunners;

  private RunnerRows rows;

  private final List<FakeWorkspacesRunner> dialled = new ArrayList<>();

  @BeforeEach
  void addressTheRunners() {
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.planesWithDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceAddressPlanes.class);
    rows = new RunnerRows();
  }

  @AfterEach
  void hangUp() {
    dialled.forEach(FakeWorkspacesRunner::close);
    dialled.clear();
    rows.clear();
  }

  private FakeWorkspacesRunner greeted(String clientId) throws Exception {
    FakeWorkspacesRunner runner =
        FakeWorkspacesRunner.connect(
            vertx,
            endpoint,
            DaemonMachineTokens.tokenWithRoles(
                clientId, Set.of(WorkspaceRunnerSocket.RUNNER_ROLE), "qits-platform"));
    dialled.add(runner);
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    runner.expect(Ack.class);
    runner.expect(Estate.class);
    runner.expect(Backlog.class);
    return runner;
  }

  /** The answer to a reserve: the first {@code take} or {@code nothing}, past any push. */
  private static RunnerMessage answer(FakeWorkspacesRunner runner) throws Exception {
    long deadline = System.nanoTime() + FakeWorkspacesRunner.SOON.toNanos();
    while (System.nanoTime() < deadline) {
      RunnerMessage next = runner.poll(Duration.ofMillis(100));
      if (next instanceof Take || next instanceof Nothing) {
        return next;
      }
    }
    return fail("neither take nor nothing arrived");
  }

  // --- reserve ------------------------------------------------------------------------------------

  /**
   * A never-placed row is taken: PROVISIONING on this runner, its spec on the wire with the public
   * image, the four logical mounts and the plane's public addresses — and the runner is sent its
   * new estate. Its {@code launched}, and then its daemon's report (qits-802), make it RUNNING.
   */
  @Test
  void aQueuedRowIsTakenWithItsSpecAndLaunched() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-taker", 1);
    Long queued = rows.queued(null, Instant.now());
    FakeWorkspacesRunner runner = greeted("wr-taker");

    runner.send(new Reserve());

    Take take = (Take) answer(runner);
    assertEquals(queued.longValue(), take.rowId());
    WorkspaceSpec spec = take.spec();
    assertEquals(
        "registry.qits."
            + WorkspaceRunnerAddressesFixture.DOMAIN
            + "/qits/workspace:"
            + rows.imageVersion(),
        spec.image());
    assertEquals(
        List.of(
            Mount.Volume.WORKSPACE, Mount.Volume.DOT_CLAUDE, Mount.Volume.M2, Mount.Volume.PNPM),
        spec.mounts().stream().map(Mount::volume).toList());
    assertEquals("/workspace", spec.mounts().get(0).target());
    assertEquals(
        "wss://workspaces.qits."
            + WorkspaceRunnerAddressesFixture.DOMAIN
            + "/workspaces/daemon/"
            + queued,
        spec.env().get("QITS_WORKSPACE_DAEMON_URL"),
        "every address is the plane's public one (qits-799)");
    assertFalse(
        spec.env().keySet().stream().anyMatch(k -> k.startsWith("QITS_COMMISSIONED_")),
        "no client pair");
    // The row's workspace token is the credential (qits-802), read off the row at the claim.
    assertEquals(rows.read(queued).commissionedToken, spec.env().get("QITS_TOKEN"));
    assertEquals(rows.read(queued).commissionedTokenSubject, spec.env().get("QITS_TOKEN_SUBJECT"));
    assertEquals(
        "githost.qits." + WorkspaceRunnerAddressesFixture.DOMAIN,
        spec.env().get("QITS_GIT_AUTH_HOST"));
    assertFalse(
        spec.labels().keySet().stream().anyMatch(k -> k.startsWith("qits.workspaces.runner.")),
        "never a label in the runner's own namespace");
    assertTrue(spec.init());
    assertEquals(List.of(queued), runner.await(Estate.class).owned());
    assertEquals(WorkspaceRuntimeStatus.PROVISIONING, rows.read(queued).runtimeStatus);
    assertEquals(row.id, rows.read(queued).runnerId);

    runner.send(new Launched(queued, "c-1"));

    awaitStatus(queued, WorkspaceRuntimeStatus.RUNNING);
  }

  /**
   * The take carries the runner row's memory limits as they are at the take (qits-951): an edit
   * made after the runner was greeted reaches it, a memory with no swap is a hard cap, and a runner
   * that sets none launches under the platform's defaults.
   */
  @Test
  void theTakeCarriesTheRunnersMemoryLimitsReadAtTheTake() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-memory", 2);
    FakeWorkspacesRunner runner = greeted("wr-memory");

    Long defaulted = rows.queued(row.id, Instant.now());
    runner.send(new Reserve());
    Take first = (Take) answer(runner);
    assertEquals(defaulted.longValue(), first.rowId());
    WorkspaceSpec platform = first.spec();
    assertEquals("4g", platform.memoryLimit(), "no row limit is the platform default");
    assertEquals("8g", platform.memorySwapLimit());

    // Edited after the greeting: the session's copy of the row is stale, the take's is not.
    workspaceRunners.patch(row.id, null, null, "12g", null);
    rows.queued(row.id, Instant.now());
    runner.send(new Reserve());
    WorkspaceSpec own = ((Take) answer(runner)).spec();
    assertEquals("12g", own.memoryLimit());
    assertEquals("12g", own.memorySwapLimit(), "never the default 8g beside a 12g memory");
  }

  /** Two runners racing for one never-placed row: exactly one is answered {@code take}. */
  @Test
  void twoRunnersRacingForOneRowGetOneTakeBetweenThem() throws Exception {
    rows.eligible("wr-race-a", 1);
    rows.eligible("wr-race-b", 1);
    Long queued = rows.queued(null, Instant.now());
    FakeWorkspacesRunner a = greeted("wr-race-a");
    FakeWorkspacesRunner b = greeted("wr-race-b");

    CompletableFuture<RunnerMessage> toA = CompletableFuture.supplyAsync(() -> reserve(a));
    CompletableFuture<RunnerMessage> toB = CompletableFuture.supplyAsync(() -> reserve(b));
    List<RunnerMessage> answers =
        List.of(toA.get(15, TimeUnit.SECONDS), toB.get(15, TimeUnit.SECONDS));

    assertEquals(1, answers.stream().filter(m -> m instanceof Take).count(), answers.toString());
    assertEquals(1, answers.stream().filter(m -> m instanceof Nothing).count(), answers.toString());
    assertEquals(
        queued.longValue(),
        answers.stream().filter(m -> m instanceof Take).map(m -> ((Take) m).rowId()).findFirst()
            .orElseThrow());
  }

  private static RunnerMessage reserve(FakeWorkspacesRunner runner) {
    try {
      runner.send(new Reserve());
      return answer(runner);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** A row sticky to one runner is never another's, and is its own runner's. */
  @Test
  void aStickyRowGoesOnlyToItsRunner() throws Exception {
    WorkspaceRunner owner = rows.eligible("wr-sticky-owner", 1);
    rows.eligible("wr-sticky-other", 1);
    Long sticky = rows.queued(owner.id, Instant.now());
    FakeWorkspacesRunner other = greeted("wr-sticky-other");
    FakeWorkspacesRunner mine = greeted("wr-sticky-owner");

    other.send(new Reserve());
    assertTrue(answer(other) instanceof Nothing);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, rows.read(sticky).runtimeStatus);

    mine.send(new Reserve());
    assertEquals(sticky.longValue(), ((Take) answer(mine)).rowId());
  }

  /** The server's count wins: a runner whose live rows fill its slots is answered nothing. */
  @Test
  void aFullRunnerIsAnsweredNothingAndTheRowStaysQueued() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-full", 1);
    rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    Long queued = rows.queued(null, Instant.now());
    FakeWorkspacesRunner runner = greeted("wr-full");

    runner.send(new Reserve());

    assertTrue(answer(runner) instanceof Nothing);
    assertEquals(WorkspaceRuntimeStatus.QUEUED, rows.read(queued).runtimeStatus);
    assertNull(rows.read(queued).runnerId);
  }

  /** A failed launch is FAILED with the runner's words, and frees the slot for the next row. */
  @Test
  void aFailedLaunchFreesTheSlot() throws Exception {
    rows.eligible("wr-unlucky", 1);
    Long first = rows.queued(null, Instant.now().minusSeconds(10));
    Long second = rows.queued(null, Instant.now());
    FakeWorkspacesRunner runner = greeted("wr-unlucky");

    runner.send(new Reserve());
    assertEquals(first.longValue(), ((Take) answer(runner)).rowId());
    runner.send(new LaunchFailed(first, "pull access denied"));
    awaitStatus(first, WorkspaceRuntimeStatus.FAILED);
    assertEquals("pull access denied", rows.read(first).runtimeError);

    runner.send(new Reserve());
    assertEquals(second.longValue(), ((Take) answer(runner)).rowId());
  }

  // --- the verbs routed to the owner --------------------------------------------------------------

  /** Stop is sent to the owner and awaited; its {@code stopped} makes the row STOPPED. */
  @Test
  void aStopIsRoutedToTheOwnerAndAwaited() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-stopper", 1);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = greeted("wr-stopper");

    CompletableFuture<Void> stopping =
        CompletableFuture.runAsync(() -> workspaceService.stopContainer(running));
    Stop stop = runner.await(Stop.class);
    assertEquals(running.longValue(), stop.rowId());
    assertFalse(stopping.isDone(), "the verb waits for the runner");
    runner.send(new Stopped(running));

    stopping.get(10, TimeUnit.SECONDS);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, rows.read(running).runtimeStatus);
    assertEquals(row.id, rows.read(running).runnerId, "still sticky");
  }

  /** Delete-container is routed and awaited; the row is STOPPED on no runner, and the estate sent. */
  @Test
  void aDeleteIsRoutedAwaitedAndUnplacesTheRow() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-deleter", 1);
    Long stopped = rows.placedOn(row.id, WorkspaceRuntimeStatus.STOPPED);
    FakeWorkspacesRunner runner = greeted("wr-deleter");

    CompletableFuture<Void> deleting =
        CompletableFuture.runAsync(() -> workspaceService.deleteContainer(stopped));
    assertEquals(stopped.longValue(), runner.await(Delete.class).rowId());
    runner.send(new Deleted(stopped));

    deleting.get(10, TimeUnit.SECONDS);
    assertNull(rows.read(stopped).runnerId);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, rows.read(stopped).runtimeStatus);
    assertEquals(List.of(), runner.await(Estate.class).owned());
  }

  /** An owner with no socket: 409 RUNNER_UNAVAILABLE, and the row is untouched. */
  @Test
  void aStopForAnOwnerThatIsNotConnectedIs409() {
    WorkspaceRunner row = rows.eligible("wr-away", 1);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);

    ConflictException refused =
        assertThrows(ConflictException.class, () -> workspaceService.stopContainer(running));
    assertEquals(RunnerRefusals.RUNNER_UNAVAILABLE, refused.code());
    assertEquals(WorkspaceRuntimeStatus.RUNNING, rows.read(running).runtimeStatus);
  }

  /** A runner that never answers: the wait ends at its deadline as a timeout. */
  @Test
  void anUnansweredRequestTimesOut() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-silent", 1);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = greeted("wr-silent");

    WorkspaceRunnerRegistry.Reply reply =
        registry.request(row.id, "stop", running, new Stop(running), Duration.ofMillis(500));

    assertEquals(WorkspaceRunnerRegistry.Reply.TIMEOUT, reply);
    runner.await(Stop.class);
  }

  /** A resolved row's runner is told to delete it, and nobody waits for the answer. */
  @Test
  void aReleasedRowIsSentDeleteWithoutWaiting() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-released", 1);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = greeted("wr-released");

    placement.released(rows.read(running));

    assertEquals(running.longValue(), runner.await(Delete.class).rowId());
  }

  private void awaitStatus(Long rowId, WorkspaceRuntimeStatus status) throws Exception {
    long deadline = System.nanoTime() + FakeWorkspacesRunner.SOON.toNanos();
    while (rows.read(rowId).runtimeStatus != status && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertEquals(status, rows.read(rowId).runtimeStatus);
  }
}
