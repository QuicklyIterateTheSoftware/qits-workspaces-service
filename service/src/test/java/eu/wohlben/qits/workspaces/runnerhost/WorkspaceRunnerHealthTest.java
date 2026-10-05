package eu.wohlben.qits.workspaces.runnerhost;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.runner.protocol.Ack;
import eu.wohlben.qits.runner.protocol.Backlog;
import eu.wohlben.qits.runner.protocol.Nothing;
import eu.wohlben.qits.runner.protocol.Quarantined;
import eu.wohlben.qits.runner.protocol.Reinstated;
import eu.wohlben.qits.runner.protocol.Reserve;
import eu.wohlben.qits.workspaces.control.WorkspaceRunners;
import eu.wohlben.qits.workspaces.control.WorkspaceService;
import eu.wohlben.qits.workspaces.daemonhost.DaemonControlSocketMachineAuthTest;
import eu.wohlben.qits.workspaces.daemonhost.DaemonMachineTokens;
import eu.wohlben.qits.workspaces.entity.WorkspaceRunner;
import eu.wohlben.qits.workspaces.entity.WorkspaceRuntimeStatus;
import eu.wohlben.qits.workspacesrunner.protocol.CheckResult;
import eu.wohlben.qits.workspacesrunner.protocol.Estate;
import eu.wohlben.qits.workspacesrunner.protocol.HealthCheck;
import eu.wohlben.qits.workspacesrunner.protocol.HealthChecked;
import eu.wohlben.qits.workspacesrunner.protocol.Stop;
import eu.wohlben.qits.workspacesrunner.protocol.Stopped;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerBinary;
import eu.wohlben.qits.workspacesrunner.protocol.WorkspacesRunnerProtocol;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runner's health check gating it (qits-850), mirroring qits-ci's {@code CiRunnerHealthTest}:
 * the comeback quarantine and its check, the back-off schedule slot by slot, the unanswered check,
 * the verdicts and what they tell the runner, the report on the row and its two reads, and that a
 * quarantine leaves the workspaces a runner already runs alone.
 *
 * <p>On {@link WorkspaceRunnerSocketTest}'s arrangement and profile ({@link
 * DaemonControlSocketMachineAuthTest.GateOn}, reused: one profile is one Quarkus start). The
 * sweep's tick is stretched out of the way by the test properties, so every {@link
 * WorkspaceRunnerHealth#sweep} here happens at an instant the test names.
 */
@QuarkusTest
@TestProfile(DaemonControlSocketMachineAuthTest.GateOn.class)
class WorkspaceRunnerHealthTest {

  private static final String PIN = WorkspacesRunnerBinary.VERSION;

  private static final String RUNNERS = "/workspaces/api/runners";

  @TestHTTPResource(WorkspacesRunnerProtocol.SOCKET_PATH)
  URI endpoint;

  @Inject Vertx vertx;

  @Inject WorkspaceRunnerRegistry registry;

  @Inject WorkspaceRunnerHealth health;

  @Inject WorkspaceRunners runners;

  @Inject WorkspaceService workspaceService;

  private RunnerRows rows;

  private final List<FakeWorkspacesRunner> dialled = new ArrayList<>();

  @BeforeEach
  void addressTheRunners() {
    QuarkusMock.installMockForType(
        WorkspaceRunnerAddressesFixture.withDomain(WorkspaceRunnerAddressesFixture.DOMAIN),
        WorkspaceRunnerAddresses.class);
    rows = new RunnerRows();
  }

  @AfterEach
  void hangUp() {
    dialled.forEach(FakeWorkspacesRunner::close);
    dialled.clear();
    registry.reconnectGrace(WorkspaceRunnerRegistry.RECONNECT_GRACE);
    rows.clear();
  }

  private FakeWorkspacesRunner dial(String clientId) throws Exception {
    FakeWorkspacesRunner runner =
        FakeWorkspacesRunner.connect(
            vertx,
            endpoint,
            DaemonMachineTokens.tokenWithRoles(
                clientId, Set.of(WorkspaceRunnerSocket.RUNNER_ROLE), "qits-platform"));
    dialled.add(runner);
    return runner;
  }

  /** Dial and say hello at the pin as a runner in service; its greeting is read up to the backlog. */
  private FakeWorkspacesRunner greeted(String clientId, int slots) throws Exception {
    FakeWorkspacesRunner runner = dial(clientId);
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    assertEquals(slots, runner.expect(Ack.class).slots());
    runner.expect(Estate.class);
    runner.expect(Backlog.class);
    return runner;
  }

  private static RequestSpecification as(String role) {
    return given().header("X-Qits-User", "someone").header("X-Qits-Roles", role);
  }

  private void awaitDisconnected(WorkspaceRunner row) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (registry.connected(row.id) && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertFalse(registry.connected(row.id));
  }

  /** Waits until the runner's pending check has been settled by its answer. */
  private void awaitSettled(WorkspaceRunner row) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (health.pendingCheck(row.id) != null && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertNull(health.pendingCheck(row.id), "the answer was not settled");
  }

  // --- the comeback -------------------------------------------------------------------------------

  /**
   * A runner whose first socket comes after the reconnect grace comes back quarantined ({@code
   * reconnected after being offline}): {@code ack{0}}, {@code quarantined}, its estate and backlog,
   * and {@code healthCheck}. Its passing answer reinstates it with its slots.
   */
  @Test
  void aRunnerBackAfterTheGraceIsQuarantinedAndChecked() throws Exception {
    registry.reconnectGrace(Duration.ofMillis(300));
    WorkspaceRunner row = rows.eligible("wr-came-back", 2);
    greeted("wr-came-back", 2).close();
    awaitDisconnected(row);
    Thread.sleep(600);

    FakeWorkspacesRunner back = dial("wr-came-back");
    back.send(FakeWorkspacesRunner.hello(PIN, List.of()));

    assertEquals(0, back.expect(Ack.class).slots());
    assertEquals(WorkspaceRunnerHealth.RECONNECTED, back.expect(Quarantined.class).reason());
    back.expect(Estate.class);
    back.expect(Backlog.class);
    HealthCheck check = back.expect(HealthCheck.class);
    assertNotNull(check.requestId());
    assertEquals(check.requestId(), health.pendingCheck(row.id));
    assertEquals(WorkspaceRunnerHealth.RECONNECTED, rows.runner(row.id).quarantineReason);
    back.send(new Reserve());
    back.expect(Nothing.class);

    back.send(new HealthChecked(true, "all passed", check.requestId(), List.of()));

    assertEquals(WorkspaceRunnerHealth.BY_HEALTH_CHECK, back.await(Reinstated.class).by());
    assertEquals(2, back.expect(Ack.class).slots());
    assertFalse(rows.runner(row.id).quarantined());
  }

  /** A reconnect inside the grace is not a comeback: no quarantine, no check. */
  @Test
  void aReconnectInsideTheGraceIsNoComeback() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-blinked", 1);
    greeted("wr-blinked", 1).close();
    awaitDisconnected(row);

    FakeWorkspacesRunner again = greeted("wr-blinked", 1);

    assertNull(again.poll(Duration.ofMillis(500)), "nothing after the backlog");
    assertFalse(rows.runner(row.id).quarantined());
    assertNull(health.pendingCheck(row.id));
  }

  // --- the schedule -------------------------------------------------------------------------------

  /**
   * qits-ci's back-off, counted from the quarantine: +1, +15, +30, +60, +90, +120, +180, then
   * hourly — a check exactly at each slot and none before it. A failed check keeps the quarantine's
   * instant, so the schedule carries on, and takes the newer reason.
   */
  @Test
  void theSweepChecksExactlyAtTheBackingOffSlots() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-backing-off", 1);
    FakeWorkspacesRunner runner = greeted("wr-backing-off", 1);
    runners.quarantine(row.id, "for the test");
    // Read back rather than taken off the returned entity: the column keeps microseconds.
    Instant quarantinedAt = rows.runner(row.id).quarantinedAt;

    for (long minutes : List.of(1L, 15L, 30L, 60L, 90L, 120L, 180L, 240L, 300L)) {
      Instant slot = quarantinedAt.plus(Duration.ofMinutes(minutes));
      health.sweep(slot.minusSeconds(30));
      assertNull(health.pendingCheck(row.id), "not before the +" + minutes + "m slot");

      health.sweep(slot);

      String sent = health.pendingCheck(row.id);
      assertNotNull(sent, "the +" + minutes + "m slot");
      assertEquals(sent, runner.await(HealthCheck.class).requestId());
      runner.send(new HealthChecked(false, "still sick", sent, List.of()));
      awaitSettled(row);
    }

    WorkspaceRunner after = rows.runner(row.id);
    assertEquals(quarantinedAt, after.quarantinedAt, "the schedule's origin is kept");
    assertEquals("health check failed: still sick", after.quarantineReason);
    assertEquals(Boolean.FALSE, after.lastHealthCheckOk);
  }

  /** The sweep asks only quarantined runners that are connected and have no check pending. */
  @Test
  void theSweepLeavesARunnerInServiceAndOneWithACheckPendingAlone() throws Exception {
    WorkspaceRunner inService = rows.eligible("wr-in-service", 1);
    FakeWorkspacesRunner fine = greeted("wr-in-service", 1);
    WorkspaceRunner waiting = rows.eligible("wr-waiting", 1);
    FakeWorkspacesRunner busy = greeted("wr-waiting", 1);
    String asked = health.request(waiting.id);
    busy.await(HealthCheck.class);
    runners.quarantine(waiting.id, "for the test");

    health.sweep(Instant.now().plus(Duration.ofMinutes(2)));

    assertNull(health.pendingCheck(inService.id), "in service: nothing to prove");
    assertEquals(asked, health.pendingCheck(waiting.id), "one pending check is enough");
    assertNull(fine.poll(Duration.ofMillis(300)));
    assertNull(busy.poll(Duration.ofMillis(300)));
  }

  // --- the verdicts -------------------------------------------------------------------------------

  /** A check not answered within the timeout is settled failed, "no answer", and quarantines. */
  @Test
  void aCheckNobodyAnsweredIsSettledFailedNoAnswer() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-silent", 1);
    FakeWorkspacesRunner runner = greeted("wr-silent", 1);
    String asked = health.request(row.id);
    runner.await(HealthCheck.class);

    health.sweep(Instant.now().plus(Duration.ofMinutes(4)));
    assertEquals(asked, health.pendingCheck(row.id), "still inside the timeout");
    health.sweep(Instant.now().plus(Duration.ofMinutes(6)));

    assertNull(health.pendingCheck(row.id));
    WorkspaceRunner after = rows.runner(row.id);
    assertTrue(after.quarantined());
    assertEquals("health check failed: no answer", after.quarantineReason);
    assertEquals(Boolean.FALSE, after.lastHealthCheckOk);
    assertEquals(
        "health check failed: no answer", runner.await(Quarantined.class).reason());
    assertEquals(0, runner.expect(Ack.class).slots());
    as("qits:admin")
        .when()
        .get(RUNNERS + "/" + row.id + "/health")
        .then()
        .statusCode(200)
        .body("ok", is(false))
        .body("detail", is(WorkspaceRunnerHealth.NO_ANSWER))
        .body("requestId", is(asked));
  }

  /**
   * A new runner's first check passes: it is reinstated with its slots, and the whole report — the
   * node inventory's data included — is on the row, read in full by {@code GET …/health} and
   * without the data in the listing. An agent may read both.
   */
  @Test
  void aPassingCheckReinstatesAndItsReportIsReadInFull() throws Exception {
    WorkspaceRunner row = rows.registered("wr-reported", 2);
    as("qits:agent").when().get(RUNNERS + "/" + row.id + "/health").then().statusCode(204);
    FakeWorkspacesRunner runner = dial("wr-reported");
    runner.send(FakeWorkspacesRunner.hello(PIN, List.of()));
    assertEquals(0, runner.expect(Ack.class).slots());
    runner.expect(Quarantined.class);
    runner.expect(Estate.class);
    runner.expect(Backlog.class);
    HealthCheck check = runner.expect(HealthCheck.class);
    Map<String, Object> runnerContainer = new LinkedHashMap<>();
    runnerContainer.put("name", "qits-workspaces-runner-x");
    runnerContainer.put("startedAt", null);

    runner.send(
        new HealthChecked(
            true,
            "6 checks passed",
            check.requestId(),
            List.of(
                new CheckResult("docker", true, "27.1", Map.of("version", "27.1")),
                new CheckResult(
                    "nodeInventory",
                    true,
                    "1 container, 0 volumes",
                    Map.of(
                        "containers",
                        List.of(Map.of("name", "qits-ws-a", "rowId", 7, "state", "running")),
                        "volumes",
                        List.of(),
                        "runnerContainer",
                        runnerContainer)))));

    assertEquals(WorkspaceRunnerHealth.BY_HEALTH_CHECK, runner.await(Reinstated.class).by());
    assertEquals(2, runner.expect(Ack.class).slots());
    assertFalse(rows.runner(row.id).quarantined());
    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + row.id + "/health")
        .then()
        .statusCode(200)
        .body("ok", is(true))
        .body("requestId", is(check.requestId()))
        .body("checks.name", is(List.of("docker", "nodeInventory")))
        .body("checks[1].data.containers[0].name", is("qits-ws-a"))
        .body("checks[1].data.containers[0].rowId", is(7))
        .body("checks[1].data.runnerContainer", hasKey("startedAt"));
    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + row.id)
        .then()
        .statusCode(200)
        .body("health.ok", is(true))
        .body("health.detail", is("6 checks passed"))
        .body("health.checks[1].name", is("nodeInventory"))
        .body("health.checks[1]", not(hasKey("data")));
  }

  /** A failed check quarantines a runner in service and says why; {@code ack{0}} follows. */
  @Test
  void aFailedCheckQuarantinesWithTheReason() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-failing", 1);
    FakeWorkspacesRunner runner = greeted("wr-failing", 1);

    runner.send(new HealthChecked(false, "selfTest: the image would not start"));

    assertEquals(
        "health check failed: selfTest: the image would not start",
        runner.expect(Quarantined.class).reason());
    assertEquals(0, runner.expect(Ack.class).slots());
    assertEquals(
        "health check failed: selfTest: the image would not start",
        rows.runner(row.id).quarantineReason);
  }

  // --- the doors ----------------------------------------------------------------------------------

  /**
   * An agent may ask for a check: 202 with its {@code requestId}, which is what the runner is sent.
   * Asked again while it is pending, the same id comes back and the runner is asked nothing more.
   */
  @Test
  void anAgentAsksForACheckAndAPendingOneIsNotSentTwice() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-asked", 1);
    FakeWorkspacesRunner runner = greeted("wr-asked", 1);

    String requestId =
        as("qits:agent")
            .when()
            .post(RUNNERS + "/" + row.id + "/healthcheck")
            .then()
            .statusCode(202)
            .extract()
            .path("requestId");

    assertEquals(requestId, runner.expect(HealthCheck.class).requestId());
    as("qits:system")
        .when()
        .post(RUNNERS + "/" + row.id + "/healthcheck")
        .then()
        .statusCode(202)
        .body("requestId", is(requestId));
    assertNull(runner.poll(Duration.ofMillis(300)), "no second check while one is pending");

    // An answer naming no request — a runner older than the field — settles the pending one.
    runner.send(new HealthChecked(true, "pulled, ran, removed"));
    awaitSettled(row);
    as("qits:agent")
        .when()
        .get(RUNNERS + "/" + row.id + "/health")
        .then()
        .statusCode(200)
        .body("requestId", is(requestId))
        .body("checks.size()", is(0));
  }

  // --- what a quarantine leaves alone -------------------------------------------------------------

  /** A quarantined runner still receives a routed stop for a workspace it runs, and answers it. */
  @Test
  void aQuarantinedRunnerStillReceivesARoutedStop() throws Exception {
    WorkspaceRunner row = rows.eligible("wr-out-but-running", 1);
    Long running = rows.placedOn(row.id, WorkspaceRuntimeStatus.RUNNING);
    FakeWorkspacesRunner runner = greeted("wr-out-but-running", 1);
    runner.send(new HealthChecked(false, "docker is slow"));
    runner.await(Quarantined.class);
    assertTrue(rows.runner(row.id).quarantined());

    CompletableFuture<Void> stopping =
        CompletableFuture.runAsync(() -> workspaceService.stopContainer(running));
    assertEquals(running.longValue(), runner.await(Stop.class).rowId());
    runner.send(new Stopped(running));

    stopping.get(10, TimeUnit.SECONDS);
    assertEquals(WorkspaceRuntimeStatus.STOPPED, rows.read(running).runtimeStatus);
  }
}
